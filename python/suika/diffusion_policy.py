"""
Diffusion Policy for Suika (ROADMAP §IV.11).

Implements score-based denoising diffusion for action generation:
  x_T ~ N(0,I)  →  [denoise]  →  x_0  (clean action)

The model learns to predict the score ∇_x log p(x|obs) via noise-prediction
(Ho et al., 2020). During inference, DDPM or DDIM sampling produces an action
conditioned on the observation.

Requires: torch

Usage::

    from suika.diffusion_policy import DiffusionPolicy, train_diffusion
    from suika.bc import DemoDataset

    dataset = DemoDataset.from_recordings("demos/")
    policy  = DiffusionPolicy(obs_dim=584, action_dim=1, T=20)
    train_diffusion(policy, dataset, epochs=50)
    policy.save("models/diffusion_policy.pt")
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    from suika.bc import DemoDataset

try:
    import torch
    import torch.nn as nn
    import torch.optim as optim
    HAS_TORCH = True
except ImportError:
    HAS_TORCH = False


# ---------------------------------------------------------------------------
# Score network (noise predictor)
# ---------------------------------------------------------------------------

def _build_score_net(obs_dim: int, action_dim: int, hidden: int) -> "nn.Module":
    if not HAS_TORCH:
        raise ImportError("PyTorch is required for DiffusionPolicy")
    return nn.Sequential(
        nn.Linear(obs_dim + action_dim + 1, hidden),   # +1 for timestep embedding
        nn.SiLU(),
        nn.Linear(hidden, hidden),
        nn.SiLU(),
        nn.Linear(hidden, hidden),
        nn.SiLU(),
        nn.Linear(hidden, action_dim),
    )


# ---------------------------------------------------------------------------
# DiffusionPolicy
# ---------------------------------------------------------------------------

class DiffusionPolicy:
    """
    DDPM-style diffusion policy conditioned on the game observation.

    The score network predicts the noise added at each diffusion step.
    Inference runs DDIM sampling for fast (T_inference << T_train) generation.
    """

    def __init__(
        self,
        obs_dim:    int   = 584,
        action_dim: int   = 1,
        T:          int   = 50,
        hidden:     int   = 256,
        beta_start: float = 1e-4,
        beta_end:   float = 0.02,
    ) -> None:
        if not HAS_TORCH:
            raise ImportError("PyTorch is required: pip install torch")

        self.obs_dim    = obs_dim
        self.action_dim = action_dim
        self.T          = T

        self.score_net = _build_score_net(obs_dim, action_dim, hidden)

        # Cosine beta schedule (Nichol & Dhariwal, 2021)
        betas = self._cosine_betas(T, beta_start, beta_end)
        alphas = 1.0 - betas
        self._alpha_bar = torch.cumprod(alphas, dim=0)
        self._betas     = betas
        self._alphas    = alphas

    # ------------------------------------------------------------------
    # Training
    # ------------------------------------------------------------------

    def loss(self, obs: "torch.Tensor", actions: "torch.Tensor") -> "torch.Tensor":
        """DDPM denoising score matching loss."""
        B = actions.shape[0]
        t = torch.randint(0, self.T, (B,), device=actions.device)
        noise = torch.randn_like(actions)

        alpha_bar_t = self._alpha_bar[t].view(-1, 1).to(actions.device)
        noisy_a = (alpha_bar_t.sqrt() * actions + (1 - alpha_bar_t).sqrt() * noise)

        t_embed = (t.float() / self.T).view(-1, 1)
        net_in  = torch.cat([obs, noisy_a, t_embed], dim=-1)
        pred    = self.score_net(net_in)
        return nn.functional.mse_loss(pred, noise)

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    @torch.no_grad()
    def sample(self, obs: "torch.Tensor", n_steps: int = 10) -> "torch.Tensor":
        """
        DDIM sampling (Song et al., 2021) with ``n_steps`` inference steps.

        Returns a (batch, action_dim) action tensor in [-1, 1].
        """
        B   = obs.shape[0]
        x   = torch.randn(B, self.action_dim, device=obs.device)
        seq = list(range(0, self.T, max(1, self.T // n_steps)))[::-1]

        for i, t_idx in enumerate(seq):
            t_prev = seq[i + 1] if i + 1 < len(seq) else 0
            t_vec  = torch.full((B, 1), t_idx / self.T, device=obs.device)
            net_in = torch.cat([obs, x, t_vec], dim=-1)
            eps    = self.score_net(net_in)

            ab_t  = self._alpha_bar[t_idx].to(obs.device)
            ab_tp = self._alpha_bar[t_prev].to(obs.device)
            x0    = (x - (1 - ab_t).sqrt() * eps) / ab_t.sqrt()
            x0    = x0.clamp(-1, 1)
            x     = ab_tp.sqrt() * x0 + (1 - ab_tp).sqrt() * eps

        return x.clamp(-1, 1)

    def predict_action(self, obs: list[float] | np.ndarray, n_steps: int = 10) -> float:
        """Single-observation inference; returns a continuous action in [-1, 1]."""
        arr   = torch.from_numpy(np.asarray(obs, dtype=np.float32)).unsqueeze(0)
        act   = self.sample(arr, n_steps=n_steps)
        return float(act[0, 0].item())

    # ------------------------------------------------------------------
    # Serialisation
    # ------------------------------------------------------------------

    def save(self, path: str | Path) -> None:
        torch.save({
            "score_net": self.score_net.state_dict(),
            "obs_dim":   self.obs_dim,
            "action_dim": self.action_dim,
            "T":          self.T,
        }, str(path))

    @classmethod
    def load(cls, path: str | Path, **kwargs) -> "DiffusionPolicy":
        ckpt = torch.load(str(path), map_location="cpu")
        policy = cls(
            obs_dim=ckpt.get("obs_dim", 584),
            action_dim=ckpt.get("action_dim", 1),
            T=ckpt.get("T", 50),
            **kwargs,
        )
        policy.score_net.load_state_dict(ckpt["score_net"])
        return policy

    # ------------------------------------------------------------------

    @staticmethod
    def _cosine_betas(T: int, beta_start: float, beta_end: float) -> "torch.Tensor":
        steps = torch.linspace(0, T, T + 1) / T
        f     = torch.cos((steps + 0.008) / 1.008 * math.pi / 2) ** 2
        betas = torch.clamp(1 - f[1:] / f[:-1], beta_start, beta_end)
        return betas


# ---------------------------------------------------------------------------
# Training helper
# ---------------------------------------------------------------------------

def train_diffusion(
    policy:     DiffusionPolicy,
    dataset:    "DemoDataset",
    epochs:     int   = 50,
    batch_size: int   = 256,
    lr:         float = 1e-4,
    device:     str   = "cpu",
) -> list[float]:
    """Train a DiffusionPolicy on demonstration data. Returns per-epoch losses."""
    if not HAS_TORCH:
        raise ImportError("PyTorch is required: pip install torch")

    policy.score_net.to(device)
    opt    = optim.AdamW(policy.score_net.parameters(), lr=lr, weight_decay=1e-4)
    losses = []

    import random as stdlib_random

    for epoch in range(epochs):
        idxs = list(range(len(dataset)))
        stdlib_random.shuffle(idxs)
        epoch_loss = 0.0
        n_batches  = 0

        for start in range(0, len(idxs), batch_size):
            batch = idxs[start : start + batch_size]
            obs_np  = np.stack([dataset._obs[i] for i in batch])
            act_np  = np.array([dataset._actions[i] / 31.0 * 2 - 1.0
                                 for i in batch], dtype=np.float32).reshape(-1, 1)
            obs_t   = torch.from_numpy(obs_np).to(device)
            act_t   = torch.from_numpy(act_np).to(device)

            opt.zero_grad()
            loss = policy.loss(obs_t, act_t)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(policy.score_net.parameters(), 1.0)
            opt.step()
            epoch_loss += float(loss.item())
            n_batches  += 1

        avg = epoch_loss / max(n_batches, 1)
        losses.append(avg)
        if (epoch + 1) % 10 == 0:
            print(f"  epoch {epoch+1:4d}/{epochs}  loss={avg:.4f}")

    return losses
