"""
Decision Transformer (DT) for Suika (ROADMAP §IV.8).

Implements Chen et al. (2021): models (return-to-go, state, action) sequences
with a causal transformer and generates actions conditioned on a desired return.

Requires: torch

Usage::

    from suika.decision_transformer import DecisionTransformer, train_dt
    from suika.bc import DemoDataset

    # Collect trajectory data with per-step returns
    dataset = TrajectoryDataset.from_recordings("demos/")
    dt = DecisionTransformer(obs_dim=584, action_dim=32, ctx_len=20)
    train_dt(dt, dataset, epochs=50)
    dt.save("models/dt.pt")

    # Inference
    obs_hist  = [obs0, obs1, ...]   # last ctx_len observations
    act_hist  = [act0, act1, ...]   # last ctx_len-1 actions (padding for first)
    rtg_hist  = [rtg0, rtg1, ...]   # decreasing return-to-go targets
    action    = dt.predict(obs_hist, act_hist, rtg_hist, target_return=2000)
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.optim as optim
    HAS_TORCH = True
except ImportError:
    HAS_TORCH = False


# ---------------------------------------------------------------------------
# Causal transformer building blocks
# ---------------------------------------------------------------------------

class _CausalSelfAttention(nn.Module if HAS_TORCH else object):
    def __init__(self, d_model: int, n_heads: int, dropout: float = 0.1):
        super().__init__()
        self.n_heads = n_heads
        self.d_head  = d_model // n_heads
        self.qkv     = nn.Linear(d_model, 3 * d_model)
        self.proj    = nn.Linear(d_model, d_model)
        self.drop    = nn.Dropout(dropout)

    def forward(self, x):
        B, T, C = x.shape
        qkv = self.qkv(x).view(B, T, 3, self.n_heads, self.d_head).permute(2, 0, 3, 1, 4)
        q, k, v = qkv[0], qkv[1], qkv[2]
        scale = math.sqrt(self.d_head)
        att   = (q @ k.transpose(-2, -1)) / scale
        mask  = torch.tril(torch.ones(T, T, device=x.device)).view(1, 1, T, T)
        att   = att.masked_fill(mask == 0, float("-inf"))
        att   = torch.softmax(att, dim=-1)
        att   = self.drop(att)
        out   = (att @ v).transpose(1, 2).reshape(B, T, C)
        return self.proj(out)


class _TransformerBlock(nn.Module if HAS_TORCH else object):
    def __init__(self, d_model: int, n_heads: int, dropout: float = 0.1):
        super().__init__()
        self.ln1  = nn.LayerNorm(d_model)
        self.attn = _CausalSelfAttention(d_model, n_heads, dropout)
        self.ln2  = nn.LayerNorm(d_model)
        self.mlp  = nn.Sequential(
            nn.Linear(d_model, 4 * d_model),
            nn.GELU(),
            nn.Linear(4 * d_model, d_model),
            nn.Dropout(dropout),
        )

    def forward(self, x):
        x = x + self.attn(self.ln1(x))
        x = x + self.mlp(self.ln2(x))
        return x


# ---------------------------------------------------------------------------
# DecisionTransformer
# ---------------------------------------------------------------------------

class DecisionTransformer(nn.Module if HAS_TORCH else object):
    """
    Decision Transformer (Chen et al., 2021) for Suika.

    Sequence format per timestep: [RTG | state | action]
    Total sequence length: ctx_len × 3 tokens.
    """

    def __init__(
            self,
            obs_dim:    int = 584,
            action_dim: int = 32,
            ctx_len:    int = 20,
            d_model:    int = 128,
            n_heads:    int = 4,
            n_layers:   int = 3,
            dropout:    float = 0.1,
            rtg_scale:  float = 5000.0,
    ) -> None:
        if not HAS_TORCH:
            raise ImportError("PyTorch is required: pip install torch")
        super().__init__()
        self.obs_dim    = obs_dim
        self.action_dim = action_dim
        self.ctx_len    = ctx_len
        self.rtg_scale  = rtg_scale

        # Position embeddings map to timesteps. Support up to 10k steps.
        self.embed_rtg    = nn.Linear(1, d_model)
        self.embed_state  = nn.Linear(obs_dim, d_model)
        self.embed_action = nn.Embedding(action_dim, d_model)
        self.pos_embed    = nn.Embedding(10000, d_model)
        self.drop         = nn.Dropout(dropout)

        self.blocks = nn.ModuleList([
            _TransformerBlock(d_model, n_heads, dropout) for _ in range(n_layers)
        ])
        self.ln_out     = nn.LayerNorm(d_model)
        self.action_head = nn.Linear(d_model, action_dim)

        self.apply(self._init_weights)

    @staticmethod
    def _init_weights(m):
        if isinstance(m, (nn.Linear, nn.Embedding)):
            nn.init.normal_(m.weight, std=0.02)
            if isinstance(m, nn.Linear) and m.bias is not None:
                nn.init.zeros_(m.bias)

    def forward(
            self,
            rtg:       "torch.Tensor",   # (B, T)    return-to-go, normalised
            states:    "torch.Tensor",   # (B, T, obs_dim)
            actions:   "torch.Tensor",   # (B, T)    integer action indices (0 = pad)
            timesteps: "torch.Tensor",   # (B, T)    chronological timesteps
    ) -> "torch.Tensor":            # (B, T, action_dim) logits
        B, T = rtg.shape
        device = rtg.device

        e_rtg    = self.embed_rtg(rtg.unsqueeze(-1))         # B T d
        e_state  = self.embed_state(states)                  # B T d
        e_action = self.embed_action(actions)                # B T d

        # Interleave: [RTG₀ S₀ A₀ RTG₁ S₁ A₁ …]
        tokens = torch.stack([e_rtg, e_state, e_action], dim=2)  # B T 3 d
        tokens = tokens.view(B, T * 3, -1)                        # B 3T d

        # Timestep alignment: s_t, a_t, r_t share the same position embedding
        time_tokens = torch.repeat_interleave(timesteps, 3, dim=1) # B 3T
        tokens  = self.drop(tokens + self.pos_embed(time_tokens))

        for block in self.blocks:
            tokens = block(tokens)
        tokens = self.ln_out(tokens)

        # Predict action from state token positions (indices 1, 4, 7, …)
        state_positions = torch.arange(1, T * 3, 3, device=device)
        state_tokens    = tokens[:, state_positions, :]   # B T d
        return self.action_head(state_tokens)             # B T action_dim

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    @torch.no_grad()
    def predict(
            self,
            obs_history:    list[list[float]],
            action_history: list[int],
            rtg_history:    list[float],
            timesteps:      list[int] | None = None,
            target_return:  float = 2000.0,
    ) -> int:
        """
        Predict the next action given context histories.

        :param obs_history:    list of up to ctx_len observations (oldest first)
        :param action_history: list of ctx_len actions (0-padded at start)
        :param rtg_history:    list of ctx_len return-to-go values
        :param timesteps:      absolute game timesteps corresponding to each step
        :param target_return:  desired cumulative return (affects the last RTG)
        :returns: integer action index
        """
        T = min(len(obs_history), self.ctx_len)

        obs_t = torch.zeros(1, T, self.obs_dim)
        act_t = torch.zeros(1, T, dtype=torch.long)
        rtg_t = torch.zeros(1, T)
        time_t = torch.zeros(1, T, dtype=torch.long)

        for i in range(T):
            obs_t[0, i] = torch.tensor(obs_history[-T + i], dtype=torch.float32)
            act_t[0, i] = int(action_history[-T + i]) if i < len(action_history) else 0
            rtg_t[0, i] = rtg_history[-T + i] / self.rtg_scale
            if timesteps is not None:
                time_t[0, i] = int(timesteps[-T + i])
            else:
                time_t[0, i] = i

        # Override the last RTG with the target
        rtg_t[0, -1] = target_return / self.rtg_scale

        logits = self.forward(rtg_t, obs_t, act_t, time_t)  # 1 T action_dim
        return int(logits[0, -1].argmax().item())

    # ------------------------------------------------------------------
    # Serialisation
    # ------------------------------------------------------------------

    def save(self, path: str | Path) -> None:
        torch.save({
            "state_dict": self.state_dict(),
            "obs_dim":    self.obs_dim,
            "action_dim": self.action_dim,
            "ctx_len":    self.ctx_len,
            "rtg_scale":  self.rtg_scale,
        }, str(path))

    @classmethod
    def load(cls, path: str | Path, **kwargs) -> "DecisionTransformer":
        ckpt = torch.load(str(path), map_location="cpu")
        dt = cls(
            obs_dim=ckpt.get("obs_dim", 584),
            action_dim=ckpt.get("action_dim", 32),
            ctx_len=ckpt.get("ctx_len", 20),
            rtg_scale=ckpt.get("rtg_scale", 5000.0),
            **kwargs,
        )
        dt.load_state_dict(ckpt["state_dict"])
        return dt


# ---------------------------------------------------------------------------
# Trajectory dataset
# ---------------------------------------------------------------------------

@dataclass
class Trajectory:
    observations: list[list[float]]
    actions:      list[int]
    rewards:      list[float]

    def returns_to_go(self, gamma: float = 1.0) -> list[float]:
        rtg = [0.0] * len(self.rewards)
        running = 0.0
        for i in reversed(range(len(self.rewards))):
            running = self.rewards[i] + gamma * running
            rtg[i]  = running
        return rtg


class TrajectoryDataset:
    """Collection of full trajectories for offline DT training."""

    def __init__(self) -> None:
        self._trajs: list[Trajectory] = []

    def add(self, traj: Trajectory) -> None:
        self._trajs.append(traj)

    def __len__(self) -> int:
        return sum(len(t.actions) for t in self._trajs)

    @classmethod
    def from_recordings(cls, directory: str | Path) -> "TrajectoryDataset":
        ds = cls()
        p  = Path(directory)
        for path in sorted(p.glob("*.npz")):
            data = np.load(path)
            ds.add(Trajectory(
                observations=data["observations"].tolist(),
                actions=data["actions"].tolist(),
                rewards=data["rewards"].tolist(),
            ))
        return ds

    def sample_batch(
            self,
            batch_size: int,
            ctx_len:    int,
            gamma:      float = 1.0,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        obs_batch = []
        act_batch = []
        rtg_batch = []
        time_batch = []

        for _ in range(batch_size):
            traj  = random.choice(self._trajs)
            T     = len(traj.actions)
            start = random.randint(0, max(0, T - ctx_len))
            end   = min(start + ctx_len, T)
            L     = end - start

            rtg = traj.returns_to_go(gamma)[start:end]
            obs = traj.observations[start:end]
            act = traj.actions[start:end]
            timesteps = list(range(start, end))

            # Pad to ctx_len
            pad = ctx_len - L
            obs_batch.append(np.pad(obs, ((pad, 0), (0, 0))))
            act_batch.append(np.pad(act, (pad, 0)))
            rtg_batch.append(np.pad(rtg, (pad, 0)))
            time_batch.append(np.pad(timesteps, (pad, 0)))

        return (np.array(obs_batch, dtype=np.float32),
                np.array(act_batch, dtype=np.int64),
                np.array(rtg_batch, dtype=np.float32),
                np.array(time_batch, dtype=np.int64))


# ---------------------------------------------------------------------------
# Training helper
# ---------------------------------------------------------------------------

def default_tb_logdir() -> str:
    return str(Path.home() / ".suikai" / "tb_logs" / "dt")


def resolve_device(device: str = "auto") -> str:
    if device != "auto":
        return device
    if HAS_TORCH and torch.cuda.is_available():
        return "cuda"
    return "cpu"


def train_dt(
        dt:          DecisionTransformer,
        dataset:     TrajectoryDataset,
        epochs:      int   = 50,
        batch_size:  int   = 128,
        ctx_len:     int   = 20,
        lr:          float = 1e-4,
        device:      str   = "cpu",
        tb_logdir:   str | None = None,
        tb_detailed: bool  = False,
) -> list[float]:
    """Train a DecisionTransformer on trajectory data. Returns per-epoch losses."""
    if not HAS_TORCH:
        raise ImportError("PyTorch is required: pip install torch")

    device = resolve_device(device)
    dt.to(device)
    print(f"Device: {device}" + (f"  |  TensorBoard: {tb_logdir}" if tb_logdir else ""))

    writer = None
    if tb_logdir:
        try:
            from torch.utils.tensorboard import SummaryWriter
            writer = SummaryWriter(log_dir=tb_logdir)
        except ImportError:
            print("Warning: tensorboard not installed — skipping TB logging")

    opt    = optim.AdamW(dt.parameters(), lr=lr, weight_decay=1e-4)
    losses = []
    global_step = 0

    for epoch in range(epochs):
        obs_np, act_np, rtg_np, time_np = dataset.sample_batch(
            batch_size * 4, ctx_len, gamma=1.0)

        epoch_loss = 0.0
        n_batches  = 0

        for start in range(0, len(obs_np), batch_size):
            obs_t = torch.from_numpy(obs_np[start:start+batch_size]).to(device)
            act_t = torch.from_numpy(act_np[start:start+batch_size]).to(device)
            rtg_t = torch.from_numpy(rtg_np[start:start+batch_size]).to(device)
            time_t = torch.from_numpy(time_np[start:start+batch_size]).to(device)
            rtg_t = rtg_t / dt.rtg_scale

            opt.zero_grad()
            logits = dt(rtg_t, obs_t, act_t, time_t)          # B T action_dim
            B, T, A = logits.shape
            loss = nn.functional.cross_entropy(
                logits.view(B * T, A), act_t.view(B * T))
            loss.backward()
            grad_norm = torch.nn.utils.clip_grad_norm_(dt.parameters(), 0.25)
            opt.step()
            batch_loss = float(loss.item())
            epoch_loss += batch_loss
            n_batches  += 1
            global_step += 1

            if writer is not None and tb_detailed:
                writer.add_scalar("train/batch_loss", batch_loss, global_step)
                writer.add_scalar("train/grad_norm", float(grad_norm), global_step)

        avg = epoch_loss / max(n_batches, 1)
        losses.append(avg)
        if writer is not None:
            writer.add_scalar("train/epoch_loss", avg, epoch)
            if tb_detailed and (epoch + 1) % 10 == 0:
                for name, param in dt.named_parameters():
                    if param.dim() < 2:
                        continue
                    writer.add_histogram(f"weights/{name}", param.detach().cpu().numpy().flatten(), epoch)
        if (epoch + 1) % 10 == 0:
            print(f"  epoch {epoch+1:4d}/{epochs}  loss={avg:.4f}")

    if writer is not None:
        writer.flush()
        writer.close()
    return losses


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(description="Train a Decision Transformer on Suika demo trajectories")
    parser.add_argument("--data-dir",  type=str, required=True,
                        help="Directory of .npz trajectory recordings.")
    parser.add_argument("--out",       type=str, default="models/dt.pt")
    parser.add_argument("--epochs",    type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--ctx-len",   type=int, default=20)
    parser.add_argument("--lr",        type=float, default=1e-4)
    parser.add_argument("--rtg-scale", type=float, default=5000.0)
    parser.add_argument("--device",    type=str, default="auto")
    parser.add_argument("--tb-logdir", type=str, default=None)
    parser.add_argument("--tb-detailed", action="store_true")
    args = parser.parse_args()

    if not HAS_TORCH:
        raise SystemExit("PyTorch is required: pip install torch")

    dataset = TrajectoryDataset.from_recordings(args.data_dir)
    if len(dataset) == 0:
        raise SystemExit(f"No trajectories found in {args.data_dir}")

    dt = DecisionTransformer(rtg_scale=args.rtg_scale, ctx_len=args.ctx_len)
    train_dt(
        dt, dataset,
        epochs=args.epochs,
        batch_size=args.batch_size,
        ctx_len=args.ctx_len,
        lr=args.lr,
        device=args.device,
        tb_logdir=args.tb_logdir or default_tb_logdir(),
        tb_detailed=args.tb_detailed,
    )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    dt.save(out)
    print(f"Saved checkpoint: {out}")


if __name__ == "__main__":
    main()