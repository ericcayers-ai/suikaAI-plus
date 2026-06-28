"""
SuikaEnv — Gymnasium-compatible stub.

In Phase 0 this is a Python-only stub that validates the API surface.
Phase 2 wires it to the Java backend via gRPC sidecar or JEP.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Any

try:
    import gymnasium as gym
    import numpy as np
    HAS_GYMNASIUM = True
except ImportError:
    HAS_GYMNASIUM = False


@dataclass
class StubObservation:
    """Minimal observation matching GameState fields (placeholder for Phase 2)."""
    fruits: list = field(default_factory=list)
    current_fruit_tier: int = 1
    next_fruit_tier: int = 1
    score: int = 0
    game_over: bool = False
    step_count: int = 0


class SuikaEnv:
    """
    Gymnasium-shaped Suika environment.

    Phase 0: pure-Python stub; validates the API contract.
    Phase 2: backed by Java suika-core via gRPC / JEP.
    """

    metadata = {"render_modes": ["human", "rgb_array"]}

    def __init__(
        self,
        observation: str = "state",
        action_space_type: str = "discrete",
        action_bins: int = 32,
        seed: int = 0,
        render_mode: str | None = None,
    ):
        self.observation_type = observation
        self.action_space_type = action_space_type
        self.action_bins = action_bins
        self._seed = seed
        self.render_mode = render_mode

        self._rng = random.Random(seed)
        self._score = 0
        self._step = 0
        self._done = False

        # --- Action space ---
        if HAS_GYMNASIUM:
            if action_space_type == "discrete":
                self.action_space = gym.spaces.Discrete(action_bins)
            else:
                self.action_space = gym.spaces.Box(low=-1.0, high=1.0, shape=(1,))
        else:
            self.action_space = None

    # ------------------------------------------------------------------
    # Gymnasium API
    # ------------------------------------------------------------------

    def reset(self, seed: int | None = None, options: dict | None = None):
        if seed is not None:
            self._seed = seed
            self._rng = random.Random(seed)
        self._score = 0
        self._step = 0
        self._done = False
        obs = self._get_obs()
        info = {}
        return obs, info

    def step(self, action: Any):
        if self._done:
            raise RuntimeError("Call reset() before stepping a terminated environment.")

        # Stub: random reward proportional to action index
        reward = self._rng.random() * 0.1
        self._score += reward
        self._step += 1
        terminated = self._step >= 50  # stub episode length
        truncated = False
        self._done = terminated

        obs = self._get_obs()
        info = {
            "score": self._score,
            "step": self._step,
            "merges": [],
            "reward_terms": {"score_delta": reward},
        }
        return obs, reward, terminated, truncated, info

    def render(self):
        pass

    def close(self):
        pass

    # ------------------------------------------------------------------

    def _get_obs(self) -> Any:
        obs = StubObservation(
            score=int(self._score),
            step_count=self._step,
            game_over=self._done,
        )
        if HAS_GYMNASIUM:
            import numpy as np
            return np.zeros((1,), dtype=np.float32)
        return obs
