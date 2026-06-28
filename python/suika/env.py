"""
SuikaEnv — Gymnasium-compatible environment for the Suika AI Sandbox.

Backend modes:
  - "standalone": pure-Python simulation (no Java required, fast for prototyping)
  - "java":       backed by Java suika-core via the bridge sidecar (ROADMAP §II.4)

The standalone mode implements the same physics contract as suika-core so that
policies trained here transfer to the real engine with minimal finetuning.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from typing import Any

try:
    import gymnasium as gym
    import numpy as np
    HAS_GYMNASIUM = True
except ImportError:
    HAS_GYMNASIUM = False


# ---------------------------------------------------------------------------
# Fruit ladder (mirrors FruitTier.java)
# ---------------------------------------------------------------------------

FRUIT_TIERS = [
    # (tier, radius, merge_score)
    (1,  0.50, 0),
    (2,  0.70, 1),
    (3,  0.90, 3),
    (4,  1.15, 6),
    (5,  1.40, 10),
    (6,  1.65, 15),
    (7,  1.90, 21),
    (8,  2.15, 28),
    (9,  2.45, 36),
    (10, 2.80, 45),
    (11, 3.20, 55),
]

CONTAINER_WIDTH  = 10.0
CONTAINER_HEIGHT = 15.0
DROP_X_MIN       = 0.2
DROP_X_MAX       = 9.8
DROP_Y           = 16.0
DEADLINE_Y       = 13.5
DEADLINE_GRACE   = 3.0
GRAVITY          = -9.8
FIXED_DT         = 1.0 / 60.0
MAX_FRUITS       = 64
DROPPABLE_TIERS  = [1, 2, 3, 4, 5]

DOUBLE_WATERMELON_BONUS = 100


@dataclass
class FruitState:
    tier:   int
    x:      float
    y:      float
    vx:     float = 0.0
    vy:     float = 0.0
    id:     int   = 0

    @property
    def radius(self) -> float:
        return FRUIT_TIERS[self.tier - 1][1]


# ---------------------------------------------------------------------------
# Standalone physics simulation
# ---------------------------------------------------------------------------

class StandaloneSimulator:
    """
    Simplified Euler-integration physics that mirrors suika-core's contract.
    Uses circle-circle collision impulses and wall clipping rather than a full
    rigid-body solver, which keeps the Python-only path fast and dependency-free.
    """

    def __init__(self, seed: int = 0):
        self._rng    = random.Random(seed)
        self._fruits: list[FruitState] = []
        self._next_id = 0
        self._score  = 0
        self._best   = 0
        self._over   = False
        self._deadline_timer = 0.0
        self._step_count = 0
        self._current_tier = self._draw_tier()
        self._next_tier    = self._draw_tier()

    def reset(self, seed: int | None = None) -> None:
        if seed is not None:
            self._rng.seed(seed)
        self.__init__(0)
        if seed is not None:
            self._rng.seed(seed)
        self._current_tier = self._draw_tier()
        self._next_tier    = self._draw_tier()

    def drop(self, x: float) -> tuple[float, list[tuple], bool]:
        """Drop current fruit at x; return (reward, merges, done)."""
        x = max(DROP_X_MIN, min(DROP_X_MAX, x))
        f = FruitState(tier=self._current_tier, x=x, y=DROP_Y, id=self._next_id)
        self._next_id += 1
        self._fruits.append(f)

        merges = self._simulate_until_settled()

        score_gained = sum(FRUIT_TIERS[tier - 1][2] for _, tier in merges)
        self._score += score_gained
        if self._score > self._best:
            self._best = self._score

        self._current_tier = self._next_tier
        self._next_tier    = self._draw_tier()
        self._step_count  += 1

        reward = float(score_gained) + (-10.0 if self._over else 0.0)
        return reward, merges, self._over

    def encode_observation(self, num_bins: int = 32) -> list[float]:
        """Encode state as flat float vector matching StateObservationEncoder.java."""
        obs = [0.0] * (8 + MAX_FRUITS * 9)
        obs[0] = (self._current_tier - 1) / 11.0
        obs[1] = (self._next_tier - 1)    / 11.0
        obs[2] = min(1.0, self._score / 5000.0)
        obs[3] = min(1.0, self._deadline_timer / DEADLINE_GRACE)
        obs[4] = len(self._fruits) / MAX_FRUITS
        obs[5] = 1.0 if self._over else 0.0
        obs[6] = DEADLINE_Y / CONTAINER_HEIGHT
        obs[7] = self._step_count / 1000.0

        for i, f in enumerate(self._fruits[:MAX_FRUITS]):
            base = 8 + i * 9
            obs[base]     = f.x / CONTAINER_WIDTH
            obs[base + 1] = f.y / CONTAINER_HEIGHT
            obs[base + 2] = f.vx / 20.0
            obs[base + 3] = f.vy / 20.0
            obs[base + 4] = 0.0  # angle (not tracked in simple sim)
            obs[base + 5] = 0.0  # angular velocity
            obs[base + 6] = (f.tier - 1) / 11.0
            obs[base + 7] = f.radius / 3.5
            obs[base + 8] = 0.0  # asleep flag
        return obs

    # ------------------------------------------------------------------

    def _draw_tier(self) -> int:
        roll = self._rng.randint(0, 9)
        if roll <= 3:
            return 1
        if roll <= 6:
            return 2
        if roll <= 8:
            return 3
        return 4

    def _simulate_until_settled(self, max_steps: int = 600) -> list[tuple]:
        all_merges = []
        for _ in range(max_steps):
            self._physics_step()
            self._check_deadline()
            if self._over:
                break
            merges = self._detect_merges()
            all_merges.extend(merges)
            if not merges and self._is_settled():
                break
        return all_merges

    def _physics_step(self) -> None:
        dt = FIXED_DT
        for f in self._fruits:
            f.vy += GRAVITY * dt
            f.x  += f.vx * dt
            f.y  += f.vy * dt
            # Wall clamp
            if f.x - f.radius < 0.0:
                f.x  = f.radius
                f.vx = abs(f.vx) * 0.3
            if f.x + f.radius > CONTAINER_WIDTH:
                f.x  = CONTAINER_WIDTH - f.radius
                f.vx = -abs(f.vx) * 0.3
            # Floor
            if f.y - f.radius < 0.0:
                f.y  = f.radius
                f.vy = abs(f.vy) * 0.05
        # Fruit-fruit collisions
        for i in range(len(self._fruits)):
            for j in range(i + 1, len(self._fruits)):
                self._resolve_collision(self._fruits[i], self._fruits[j])

    def _resolve_collision(self, a: FruitState, b: FruitState) -> None:
        dx = b.x - a.x
        dy = b.y - a.y
        dist = math.hypot(dx, dy)
        min_dist = a.radius + b.radius
        if dist >= min_dist or dist < 1e-9:
            return
        nx = dx / dist
        ny = dy / dist
        overlap = min_dist - dist
        a.x -= nx * overlap * 0.5
        a.y -= ny * overlap * 0.5
        b.x += nx * overlap * 0.5
        b.y += ny * overlap * 0.5
        rel_vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
        if rel_vn > 0:
            return
        j = -rel_vn * 0.3
        a.vx -= nx * j
        a.vy -= ny * j
        b.vx += nx * j
        b.vy += ny * j

    def _detect_merges(self) -> list[tuple]:
        merges = []
        merged_ids = set()
        fruits = self._fruits
        for i in range(len(fruits)):
            if fruits[i].id in merged_ids:
                continue
            for j in range(i + 1, len(fruits)):
                if fruits[j].id in merged_ids:
                    continue
                a, b = fruits[i], fruits[j]
                if a.tier != b.tier:
                    continue
                dist = math.hypot(b.x - a.x, b.y - a.y)
                if dist > (a.radius + b.radius) * 1.1:
                    continue
                new_tier = a.tier + 1 if a.tier < 11 else None
                mx = (a.x + b.x) / 2
                my = (a.y + b.y) / 2
                merges.append((mx, my, new_tier if new_tier else 11))
                merged_ids.add(a.id)
                merged_ids.add(b.id)
                if new_tier:
                    new_f = FruitState(tier=new_tier, x=mx, y=my, vy=0.5,
                                       id=self._next_id)
                    self._next_id += 1
                    fruits.append(new_f)
                break

        if merged_ids:
            self._fruits = [f for f in self._fruits if f.id not in merged_ids]
        return merges

    def _is_settled(self) -> bool:
        return all(abs(f.vx) < 0.1 and abs(f.vy) < 0.1 for f in self._fruits)

    def _check_deadline(self) -> None:
        above = any(f.y + f.radius > DEADLINE_Y and abs(f.vy) < 0.15
                    for f in self._fruits)
        if above:
            self._deadline_timer += FIXED_DT
            if self._deadline_timer >= DEADLINE_GRACE:
                self._over = True
        else:
            self._deadline_timer = max(0.0, self._deadline_timer - FIXED_DT)

    @property
    def score(self) -> int:     return self._score
    @property
    def game_over(self) -> bool: return self._over
    @property
    def step_count(self) -> int: return self._step_count
    @property
    def current_tier(self) -> int: return self._current_tier
    @property
    def next_tier(self) -> int:    return self._next_tier
    @property
    def fruits(self) -> list[FruitState]: return list(self._fruits)


# ---------------------------------------------------------------------------
# SuikaEnv — Gymnasium-compatible wrapper
# ---------------------------------------------------------------------------

class SuikaEnv:
    """
    Gymnasium-compatible Suika environment.

    Supports both standalone (pure-Python) and Java-backed modes.

    Parameters
    ----------
    observation : "state" | "pixels"
        State: flat float vector of length 584 (StateObservationEncoder).
        Pixels: 4×84×84 float32 raster (SoftwarePixelEncoder).
    action_space_type : "discrete" | "continuous"
    action_bins : number of discrete bins (ignored for continuous)
    seed : initial RNG seed
    backend : "standalone" | "java"
    render_mode : "human" | "rgb_array" | None
    """

    metadata = {"render_modes": ["human", "rgb_array"]}

    OBS_DIM      = 8 + MAX_FRUITS * 9   # 584
    PIXEL_FRAMES = 4
    PIXEL_H      = 84
    PIXEL_W      = 84

    def __init__(
        self,
        observation: str = "state",
        action_space_type: str = "discrete",
        action_bins: int = 32,
        seed: int = 0,
        backend: str = "standalone",
        render_mode: str | None = None,
    ):
        self.observation_type  = observation
        self.action_space_type = action_space_type
        self.action_bins       = action_bins
        self._seed             = seed
        self.backend           = backend
        self.render_mode       = render_mode

        self._sim: StandaloneSimulator | None = None
        self._bridge = None

        if HAS_GYMNASIUM:
            if action_space_type == "discrete":
                self.action_space = gym.spaces.Discrete(action_bins)
            else:
                self.action_space = gym.spaces.Box(
                    low=-1.0, high=1.0, shape=(1,), dtype=np.float32)

            if observation == "pixels":
                self.observation_space = gym.spaces.Box(
                    low=0.0, high=1.0,
                    shape=(self.PIXEL_FRAMES, self.PIXEL_H, self.PIXEL_W),
                    dtype=np.float32)
            else:
                self.observation_space = gym.spaces.Box(
                    low=-1.0, high=1.0,
                    shape=(self.OBS_DIM,), dtype=np.float32)
        else:
            self.action_space      = None
            self.observation_space = None

    # ------------------------------------------------------------------
    # Gymnasium API
    # ------------------------------------------------------------------

    def reset(self, seed: int | None = None, options: dict | None = None):
        if seed is not None:
            self._seed = seed
        self._sim = StandaloneSimulator(seed=self._seed)
        obs = self._get_obs()
        return obs, {}

    def step(self, action: Any):
        if self._sim is None or self._sim.game_over:
            raise RuntimeError("Call reset() before stepping a terminated environment.")

        x = self._action_to_x(action)
        reward, merges, done = self._sim.drop(x)

        obs  = self._get_obs()
        info = {
            "score":        self._sim.score,
            "step":         self._sim.step_count,
            "merges":       merges,
            "reward_terms": {"score_delta": reward},
        }
        return obs, float(reward), done, False, info

    def render(self):
        if self.render_mode == "rgb_array" and HAS_GYMNASIUM:
            return self._render_rgb()

    def close(self):
        if self._bridge is not None:
            self._bridge.close()
            self._bridge = None

    # ------------------------------------------------------------------

    def _action_to_x(self, action: Any) -> float:
        if self.action_space_type == "discrete":
            a = int(action)
            a = max(0, min(self.action_bins - 1, a))
            return DROP_X_MIN + (a / (self.action_bins - 1)) * (DROP_X_MAX - DROP_X_MIN)
        else:
            t = (float(action) + 1.0) / 2.0
            return DROP_X_MIN + max(0.0, min(1.0, t)) * (DROP_X_MAX - DROP_X_MIN)

    def _get_obs(self) -> Any:
        if self._sim is None:
            flat = [0.0] * self.OBS_DIM
        else:
            flat = self._sim.encode_observation(self.action_bins)

        if HAS_GYMNASIUM:
            arr = np.array(flat, dtype=np.float32)
            if self.observation_type == "pixels":
                return self._state_to_pixels(flat)
            return arr
        return flat

    def _state_to_pixels(self, flat_obs: list[float]) -> "np.ndarray":
        frame = np.zeros((self.PIXEL_H, self.PIXEL_W), dtype=np.float32)
        if self._sim is None:
            return np.stack([frame] * self.PIXEL_FRAMES)

        x_range = CONTAINER_WIDTH
        y_range = CONTAINER_HEIGHT

        def to_col(x: float) -> int:
            return int(round((x / x_range) * (self.PIXEL_W - 1)))

        def to_row(y: float) -> int:
            return int(round((1.0 - y / y_range) * (self.PIXEL_H - 1)))

        # Draw deadline
        dr = to_row(DEADLINE_Y)
        if 0 <= dr < self.PIXEL_H:
            frame[dr, :] = 0.4

        for f in self._sim.fruits:
            intensity = f.tier / 11.0
            r_px_x = max(1, int(round((f.radius / x_range) * self.PIXEL_W)))
            r_px_y = max(1, int(round((f.radius / y_range) * self.PIXEL_H)))
            cx, cy = to_col(f.x), to_row(f.y)
            for dy in range(-r_px_y, r_px_y + 1):
                for dx in range(-r_px_x, r_px_x + 1):
                    nx_ = (dx / (r_px_x + 1e-9)) ** 2 + (dy / (r_px_y + 1e-9)) ** 2
                    if nx_ <= 1.0:
                        row = cy + dy
                        col = cx + dx
                        if 0 <= row < self.PIXEL_H and 0 <= col < self.PIXEL_W:
                            frame[row, col] = intensity

        return np.stack([frame] * self.PIXEL_FRAMES)

    def _render_rgb(self) -> "np.ndarray":
        if self._sim is None:
            return np.zeros((self.PIXEL_H, self.PIXEL_W, 3), dtype=np.uint8)
        pixels = self._state_to_pixels([])
        grey = (pixels[0] * 255).astype(np.uint8)
        return np.stack([grey, grey, grey], axis=-1)
