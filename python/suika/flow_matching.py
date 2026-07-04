"""
Flow Matching policy for Suika (ROADMAP §IV.11).

Implements Conditional Flow Matching (Lipman et al., 2022) as an alternative
to diffusion. Trains a velocity field v_t(x, obs) such that the ODE
  dx/dt = v_t(x, obs)
maps noise N(0,I) to the action distribution conditioned on obs.

Inference integrates the ODE from t=0 to t=1 using Euler or RK4 steps.

Requires: torch

Usage::

    from suika.flow_matching import FlowMatchingPolicy, train_flow
    from suika.bc import DemoDataset

    dataset = DemoDataset.from_recordings("demos/")
    policy  = FlowMatchingPolicy(obs_dim=584, action_dim=1)
    train_flow(policy, dataset, epochs=50)
    policy.save("models/flow_policy.pt")
"""

from __future__ import annotations

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
# Velocity network
# ---------------------------------------------------------------------------

def _build_velocity_net(obs_dim: int, action_dim: int, hidden: int) -> "nn.Module":
    if not HAS_TORCH:
        raise ImportError("PyTorch is required for FlowMatchingPolicy")
    return nn.Sequential(
        nn.Linear(obs_dim + action_dim + 1, hidden),   # +1 for time t ∈ [0,1]
        nn.SiLU(),
        nn.Linear(hidden, hidden),
        nn.SiLU(),
        nn.Linear(hidden, hidden),
        nn.SiLU(),
        nn.Linear(hidden, action_dim),
    )


# ---------------------------------------------------------------------------
# FlowMatchingPolicy
# ---------------------------------------------------------------------------

class FlowMatchingPolicy:
    """
    Conditional Flow Matching (CFM) policy.

    Training: regress the velocity field on straight-line paths between noise
    (x0 ~ N(0,I)) and demonstrations (x1 = action).

    Inference: integrate the learned velocity field from t=0 to t=1.
    """

    def __init__(
            self,
            obs_dim:    int   = 584,
            action_dim: int   = 1,
            hidden:     int   = 256,
            sigma:      float = 0.01,
    ) -> None:
        if not HAS_TORCH:
            raise ImportError("PyTorch is required: pip install torch")

        self.obs_dim    = obs_dim
        self.action_dim = action_dim
        self.sigma      = sigma
        self.velocity   = _build_velocity_net(obs_dim, action_dim, hidden)

    # ------------------------------------------------------------------
    # Training
    # ------------------------------------------------------------------

    def loss(self, obs: "torch.Tensor", x1: "torch.Tensor") -> "torch.Tensor":
        """
        Conditional Flow Matching loss.

        Target velocity: u_t(x | x0, x1) = x1 - x0  (straight-line path).
        """
        B  = x1.shape[0]
        t  = torch.rand(B, 1, device=x1.device)
        x0 = torch.randn_like(x1)

        # Straight-line interpolation with small Gaussian noise (σ-path)
        xt = (1 - (1 - self.sigma) * t) * x0 + t * x1

        target   = x1 - (1 - self.sigma) * x0  # = xt'(t)
        net_in   = torch.cat([obs, xt, t], dim=-1)
        pred     = self.velocity(net_in)
        return nn.functional.mse_loss(pred, target)

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    @torch.no_grad()
    def sample(self, obs: "torch.Tensor", n_steps: int = 20) -> "torch.Tensor":
        """
        Integrate the velocity ODE from t=0 to t=1 with Euler steps.

        Returns a (batch, action_dim) action tensor in approximately [-1, 1].
        """
        B  = obs.shape[0]
        x  = torch.randn(B, self.action_dim, device=obs.device)
        dt = 1.0 / n_steps

        for step in range(n_steps):
            t  = torch.full((B, 1), step * dt, device=obs.device)
            net_in = torch.cat([obs, x, t], dim=-1)
            v  = self.velocity(net_in)
            x  = x + v * dt

        return x.clamp(-1.5, 1.5)

    def predict_action(self, obs: list[float] | np.ndarray, n_steps: int = 20) -> float:
        """Single-observation inference; returns a continuous action in ~[-1, 1]."""
        # FIX: Ensure observation is cast to the active device to prevent CPU↔GPU mismatch
        device = next(self.velocity.parameters()).device
        arr = torch.from_numpy(np.asarray(obs, dtype=np.float32)).unsqueeze(0).to(device)
        act = self.sample(arr, n_steps=n_steps)
        return float(act[0, 0].item())

    # ------------------------------------------------------------------
    # Serialisation
    # ------------------------------------------------------------------

    def save(self, path: str | Path) -> None:
        torch.save({
            "velocity":   self.velocity.state_dict(),
            "obs_dim":    self.obs_dim,
            "action_dim": self.action_dim,
            "sigma":      self.sigma,
        }, str(path))

    @classmethod
    def load(cls, path: str | Path, **kwargs) -> "FlowMatchingPolicy":
        ckpt = torch.load(str(path), map_location="cpu")
        policy = cls(
            obs_dim=ckpt.get("obs_dim", 584),
            action_dim=ckpt.get("action_dim", 1),
            sigma=ckpt.get("sigma", 0.01),
            **kwargs,
        )
        policy.velocity.load_state_dict(ckpt["velocity"])
        return policy


# ---------------------------------------------------------------------------
# Training helper
# ---------------------------------------------------------------------------

def train_flow(
        policy:     FlowMatchingPolicy,
        dataset:    "DemoDataset",
        epochs:     int   = 50,
        batch_size: int   = 256,
        lr:         float = 1e-4,
        device:     str   = "cpu",
) -> list[float]:
    """Train a FlowMatchingPolicy on demonstration data. Returns per-epoch losses."""
    if not HAS_TORCH:
        raise ImportError("PyTorch is required: pip install torch")

    policy.velocity.to(device)
    opt    = optim.AdamW(policy.velocity.parameters(), lr=lr, weight_decay=1e-4)
    losses = []

    import random as stdlib_random

    for epoch in range(epochs):
        idxs = list(range(len(dataset)))
        stdlib_random.shuffle(idxs)
        epoch_loss = 0.0
        n_batches  = 0

        for start in range(0, len(idxs), batch_size):
            batch = idxs[start : start + batch_size]
            obs_np = np.stack([dataset._obs[i] for i in batch])
            act_np = np.array([dataset._actions[i] / 31.0 * 2 - 1.0
                               for i in batch], dtype=np.float32).reshape(-1, 1)
            obs_t  = torch.from_numpy(obs_np).to(device)
            act_t  = torch.from_numpy(act_np).to(device)

            opt.zero_grad()
            loss = policy.loss(obs_t, act_t)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(policy.velocity.parameters(), 1.0)
            opt.step()
            epoch_loss += float(loss.item())
            n_batches  += 1

        avg = epoch_loss / max(n_batches, 1)
        losses.append(avg)
        if (epoch + 1) % 10 == 0:
            print(f"  epoch {epoch+1:4d}/{epochs}  loss={avg:.4f}")

    return losses