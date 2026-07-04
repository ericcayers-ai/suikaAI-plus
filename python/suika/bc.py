"""
Behavioral Cloning (BC) trainer — ROADMAP §IV.6.

Trains an MLP policy from demonstration data via cross-entropy supervised learning.
Supports both numpy-only (no GPU) and torch backends.

Usage::

    from suika.bc import BCTrainer, DemoDataset
    from suika.env import SuikaEnv

    dataset = DemoDataset.from_recordings("demos/")
    trainer = BCTrainer(obs_dim=584, num_actions=32, lr=1e-3)
    trainer.train(dataset, epochs=20)
    trainer.save("policy_bc.npz")   # numpy checkpoint
    trainer.save_onnx("policy.onnx") # ONNX for suika-app
"""

from __future__ import annotations

import math
import random
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
# Dataset
# ---------------------------------------------------------------------------

class DemoDataset:
    """Collection of (observation, action) demonstration pairs."""

    def __init__(self) -> None:
        self._obs:     list[np.ndarray] = []
        self._actions: list[int]        = []

    def add(self, obs: list[float] | np.ndarray, action: int) -> None:
        self._obs.append(np.asarray(obs, dtype=np.float32))
        self._actions.append(int(action))

    def __len__(self) -> int:
        return len(self._obs)

    def sample_batch(self, batch_size: int) -> tuple[np.ndarray, np.ndarray]:
        idxs = random.sample(range(len(self)), min(batch_size, len(self)))
        obs     = np.stack([self._obs[i]     for i in idxs])
        actions = np.array([self._actions[i] for i in idxs], dtype=np.int64)
        return obs, actions

    @classmethod
    def from_recordings(cls, directory: str | Path) -> "DemoDataset":
        """Load .npz demo files created by ReplayRecorder integration."""
        ds  = cls()
        p   = Path(directory)
        for path in sorted(p.glob("*.npz")):
            data = np.load(path)
            for obs, act in zip(data["observations"], data["actions"]):
                ds.add(obs, int(act))
        return ds

    def save(self, path: str | Path) -> None:
        np.savez_compressed(
            path,
            observations=np.stack(self._obs),
            actions=np.array(self._actions, dtype=np.int64),
        )


# ---------------------------------------------------------------------------
# Numpy MLP (no-torch path)
# ---------------------------------------------------------------------------

class NumpyMLP:
    """Two-layer MLP with tanh hidden layer, implemented in pure numpy."""

    def __init__(self, in_dim: int, hidden: int, out_dim: int, rng: np.random.Generator) -> None:
        scale = math.sqrt(2.0 / (in_dim + hidden))
        self.W1 = rng.standard_normal((hidden, in_dim)).astype(np.float32)  * scale
        self.b1 = np.zeros(hidden, dtype=np.float32)
        self.W2 = (rng.standard_normal((out_dim, hidden)).astype(np.float32)
                   * math.sqrt(2.0 / (hidden + out_dim)))
        self.b2 = np.zeros(out_dim, dtype=np.float32)

    def forward(self, x: np.ndarray) -> np.ndarray:
        h = np.tanh(self.W1 @ x + self.b1)
        return self.W2 @ h + self.b2

    def forward_batch(self, X: np.ndarray) -> np.ndarray:
        H = np.tanh(X @ self.W1.T + self.b1)
        return H @ self.W2.T + self.b2

    def predict(self, obs: np.ndarray) -> int:
        logits = self.forward(obs.astype(np.float32))
        return int(np.argmax(logits))

    def softmax_loss(self, logits: np.ndarray, labels: np.ndarray) -> float:
        logits = logits - logits.max(axis=1, keepdims=True)
        exp    = np.exp(logits)
        probs  = exp / exp.sum(axis=1, keepdims=True)
        n      = labels.shape[0]
        return -np.log(probs[np.arange(n), labels] + 1e-9).mean()

    def param_list(self) -> list[np.ndarray]:
        return [self.W1, self.b1, self.W2, self.b2]


# ---------------------------------------------------------------------------
# BCTrainer
# ---------------------------------------------------------------------------

class BCTrainer:
    """
    Behavioral Cloning trainer.

    Uses PyTorch if available (GPU support, Adam optimizer, gradient clipping).
    Falls back to pure-numpy SGD when PyTorch is not installed.
    """

    def __init__(
        self,
        obs_dim:     int   = 584,
        num_actions: int   = 32,
        hidden:      int   = 64,
        lr:          float = 1e-3,
        batch_size:  int   = 256,
        seed:        int   = 0,
        device:      str   = "auto",
    ) -> None:
        self.obs_dim     = obs_dim
        self.num_actions = num_actions
        self.hidden      = hidden
        self.lr          = lr
        self.batch_size  = batch_size

        self._rng = np.random.default_rng(seed)

        if HAS_TORCH:
            # "auto" prefers CUDA when available — a real backprop training loop
            # genuinely benefits from the GPU at larger batch sizes, unlike the tiny
            # forward-only inference this architecture also serves elsewhere.
            if device == "auto":
                device = "cuda" if torch.cuda.is_available() else "cpu"
            self.device = device
            self._model = nn.Sequential(
                nn.Linear(obs_dim, hidden),
                nn.Tanh(),
                nn.Linear(hidden, num_actions),
            ).to(device)
            self._opt = optim.Adam(self._model.parameters(), lr=lr)
        else:
            self.device = "cpu"
            self._model = NumpyMLP(obs_dim, hidden, num_actions, self._rng)
            self._opt   = None

        self.train_losses: list[float] = []

    def train(self, dataset: DemoDataset, epochs: int = 10) -> None:
        """Train for ``epochs`` passes over the dataset."""
        if len(dataset) == 0:
            raise ValueError("Dataset is empty")
        for epoch in range(epochs):
            loss = self._train_epoch(dataset)
            self.train_losses.append(loss)

    def predict(self, obs: list[float] | np.ndarray) -> int:
        arr = np.asarray(obs, dtype=np.float32)
        if HAS_TORCH:
            with torch.no_grad():
                logits = self._model(torch.from_numpy(arr).unsqueeze(0).to(self.device))
            return int(logits.argmax(dim=1).item())
        return self._model.predict(arr)

    def save(self, path: str | Path) -> None:
        """Save weights as a numpy .npz checkpoint."""
        if HAS_TORCH:
            state = {k: v.cpu().numpy() for k, v in self._model.state_dict().items()}
            np.savez_compressed(path, **state)
        else:
            params = self._model.param_list()
            np.savez_compressed(path, W1=params[0], b1=params[1],
                                W2=params[2], b2=params[3])

    def save_onnx(self, path: str | Path) -> None:
        """Export to ONNX for loading by OnnxPolicyRunner.java."""
        if not HAS_TORCH:
            raise RuntimeError("PyTorch is required for ONNX export")
        try:
            import torch.onnx
            dummy = torch.zeros(1, self.obs_dim)
            torch.onnx.export(
                self._model, dummy, str(path),
                input_names=["observation"],
                output_names=["policy_logits"],
                opset_version=17,
                dynamic_axes={"observation": {0: "batch"}, "policy_logits": {0: "batch"}},
            )
        except Exception as e:
            raise RuntimeError(f"ONNX export failed: {e}") from e

    @classmethod
    def load(cls, path: str | Path, obs_dim: int = 584, num_actions: int = 32,
             hidden: int = 64) -> "BCTrainer":
        trainer = cls(obs_dim, num_actions, hidden)
        data = np.load(path)
        if HAS_TORCH:
            state = {k: torch.from_numpy(v) for k, v in data.items()}
            trainer._model.load_state_dict(state)
        else:
            m = trainer._model
            m.W1[:] = data["W1"]
            m.b1[:] = data["b1"]
            m.W2[:] = data["W2"]
            m.b2[:] = data["b2"]
        return trainer

    # ------------------------------------------------------------------

    def _train_epoch(self, dataset: DemoDataset) -> float:
        total_loss = 0.0
        n_batches  = 0

        indices = list(range(len(dataset)))
        random.shuffle(indices)

        for start in range(0, len(indices), self.batch_size):
            batch_idx = indices[start : start + self.batch_size]
            obs_arr = np.stack([dataset._obs[i]     for i in batch_idx])
            act_arr = np.array([dataset._actions[i] for i in batch_idx], dtype=np.int64)

            if HAS_TORCH:
                loss = self._torch_step(obs_arr, act_arr)
            else:
                loss = self._numpy_step(obs_arr, act_arr)

            total_loss += loss
            n_batches  += 1

        return total_loss / max(n_batches, 1)

    def _torch_step(self, obs: np.ndarray, actions: np.ndarray) -> float:
        self._opt.zero_grad()
        x   = torch.from_numpy(obs).to(self.device)
        y   = torch.from_numpy(actions).to(self.device)
        out = self._model(x)
        loss = nn.functional.cross_entropy(out, y)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(self._model.parameters(), 1.0)
        self._opt.step()
        return float(loss.item())

    def _numpy_step(self, obs: np.ndarray, actions: np.ndarray) -> float:
        logits = self._model.forward_batch(obs)
        loss   = self._model.softmax_loss(logits, actions)

        # Finite-difference gradient for numpy path (slow, for fallback use only)
        eps = 1e-3
        for param in self._model.param_list():
            grad = np.zeros_like(param)
            flat = param.ravel()
            for k in range(flat.size):
                old = float(flat[k])
                flat[k] = old + eps
                l_plus = self._model.softmax_loss(
                    self._model.forward_batch(obs), actions)
                flat[k] = old - eps
                l_minus = self._model.softmax_loss(
                    self._model.forward_batch(obs), actions)
                flat[k] = old
                grad.ravel()[k] = (l_plus - l_minus) / (2 * eps)
            param -= self.lr * grad
        return loss
