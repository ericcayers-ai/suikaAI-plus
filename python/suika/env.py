"""
Gymnasium-shaped environment and simulator for Suika.
"""

from __future__ import annotations

import math
import random
from pathlib import Path
import numpy as np

try:
    import gymnasium as gym
    HAS_GYM = True
except ImportError:
    HAS_GYM = False


# --- Architecture dimensions ---
MAX_FRUITS   = 64
PER_FRUIT    = 9
GLOBAL_DIMS  = 8
OBS_DIM      = GLOBAL_DIMS + MAX_FRUITS * PER_FRUIT

CONTAINER_WIDTH  = 10.0
CONTAINER_HEIGHT = 15.0
DEADLINE_Y       = CONTAINER_HEIGHT - 1.5
DROP_X_MIN       = 0.2
DROP_X_MAX       = 9.8

DOUBLE_WATERMELON_BONUS = 100


class FruitTier:
    def __init__(self, tier: int, radius: float, merge_score: int) -> None:
        self.tier = tier
        self.radius = radius
        self.merge_score = merge_score

    @property
    def is_droppable(self) -> bool:
        return self.tier <= 5


FRUIT_TIERS = [
    FruitTier(1,  0.59,   0),  # Cherry
    FruitTier(2,  0.83,   1),  # Strawberry
    FruitTier(3,  1.07,   3),  # Grape
    FruitTier(4,  1.37,   6),  # Dekopon
    FruitTier(5,  1.66,  10),  # Persimmon
    FruitTier(6,  1.97,  15),  # Apple
    FruitTier(7,  2.26,  21),  # Pear
    FruitTier(8,  2.56,  28),  # Peach
    FruitTier(9,  2.92,  36),  # Pineapple
    FruitTier(10, 3.33,  45),  # Melon
    FruitTier(11, 3.80,  55),  # Watermelon
]


class FruitState:
    def __init__(self, id_val: int, tier: int, x: float, y: float, vx: float, vy: float) -> None:
        self.id = id_val
        self.tier_idx = tier # 1-based
        self.x = x
        self.y = y
        self.vx = vx
        self.vy = vy

    @property
    def radius(self) -> float:
        return FRUIT_TIERS[self.tier_idx - 1].radius


class StandaloneSimulator:
    """Deterministic, pure-python headless physics fallback."""

    def __init__(self, seed: int = 0) -> None:
        self.seed = seed
        self._rng = np.random.default_rng(seed)
        self.fruits: list[FruitState] = []
        self.score = 0
        self.step_count = 0
        self.gameOver = False
        self._next_id = 0
        self.current_tier = self._draw_droppable()
        self.next_tier = self._draw_droppable()

    def reset(self, seed: int | None = None) -> None:
        if seed is not None:
            self.seed = seed
        self._rng = np.random.default_rng(self.seed)
        self.fruits.clear()
        self.score = 0
        self.step_count = 0
        self.gameOver = False
        self._next_id = 0
        self.current_tier = self._draw_droppable()
        self.next_tier = self._draw_droppable()

    def _draw_droppable(self) -> int:
        roll = self._rng.integers(10)
        if roll <= 3: return 1  # Cherry
        if roll <= 6: return 2  # Strawberry
        if roll <= 8: return 3  # Grape
        return 4                # Dekopon

    def drop(self, x: float) -> tuple[float, list[tuple[float, float, int | None]], bool]:
        if self.gameOver:
            return 0.0, [], True

        r = FRUIT_TIERS[self.current_tier - 1].radius
        x_clamped = max(DROP_X_MIN + r, min(DROP_X_MAX - r, x))

        # Spawn fruit at drop height
        new_f = FruitState(self._next_id, self.current_tier, x_clamped, CONTAINER_HEIGHT, 0.0, -10.0)
        self._next_id += 1
        self.fruits.append(new_f)

        # Basic settle-down and merge iteration
        merges = []
        self._settle_physics(merges)

        # Calculate score with double-watermelon bonus
        score_gained = 0
        for _, _, tier in merges:
            if tier is None:
                score_gained += DOUBLE_WATERMELON_BONUS
            else:
                score_gained += FRUIT_TIERS[tier - 1][2]

        self.score += score_gained
        self._check_game_over()

        self.current_tier = self.next_tier
        self.next_tier = self._draw_droppable()
        self.step_count += 1

        return float(score_gained), merges, self.gameOver

    def encode_observation(self, num_bins: int = 32) -> list[float]:
        obs = [0.0] * OBS_DIM
        obs[0] = (self.current_tier - 1) / 11.0
        obs[1] = (self.next_tier - 1) / 11.0
        obs[2] = min(1.0, self.score / 5000.0)
        obs[3] = 0.0  # deadline timer
        obs[4] = len(self.fruits) / MAX_FRUITS
        obs[5] = 1.0 if self.gameOver else 0.0
        obs[6] = DEADLINE_Y / CONTAINER_HEIGHT
        obs[7] = self.step_count / 1000.0

        sorted_fruits = sorted(self.fruits, key=lambda f: f.id)[:MAX_FRUITS]
        for i, f in enumerate(sorted_fruits):
            base = 8 + i * 9
            obs[base]     = f.x / CONTAINER_WIDTH
            obs[base + 1] = f.y / CONTAINER_HEIGHT
            obs[base + 2] = f.vx / 20.0
            obs[base + 3] = f.vy / 20.0
            obs[base + 4] = 0.0
            obs[base + 5] = 0.0
            obs[base + 6] = (f.tier_idx - 1) / 11.0
            obs[base + 7] = f.radius / 4.0
            obs[base + 8] = 1.0  # asleep
        return obs

    def _settle_physics(self, merges: list[tuple[float, float, int | None]]):
        # Semi-Euler relaxation solver
        for _ in range(120):
            # Apply gravity
            for f in self.fruits:
                f.vy = max(-20.0, f.vy + GRAVITY * FIXED_DT)
                f.x += f.vx * FIXED_DT
                f.y += f.vy * FIXED_DT

                # Wall constraints
                if f.x - f.radius < 0.0:
                    f.x = f.radius
                    f.vx = abs(f.vx) * 0.1
                if f.x + f.radius > CONTAINER_WIDTH:
                    f.x = CONTAINER_WIDTH - f.radius
                    f.vx = -abs(f.vx) * 0.1
                # Floor constraints
                if f.y - f.radius < 0.0:
                    f.y = f.radius
                    f.vy = abs(f.vy) * 0.05
                    f.vx *= 0.9

            # Resolve fruit collisions
            for i in range(len(self.fruits)):
                for j in range(i + 1, len(self.fruits)):
                    self._resolve_col(self.fruits[i], self.fruits[j])

            # Process merges
            merged = self._process_merges(merges)
            if not merged and self._is_settled():
                break

    def _resolve_col(self, a: FruitState, b: FruitState):
        dx = b.x - a.x
        dy = b.y - a.y
        dist = math.hypot(dx, dy)
        min_dist = a.radius + b.radius
        if dist >= min_dist or dist < 1e-9:
            return
        nx, ny = dx / dist, dy / dist
        overlap = min_dist - dist
        a.x -= nx * overlap * 0.5
        a.y -= ny * overlap * 0.5
        b.x += nx * overlap * 0.5
        b.y += ny * overlap * 0.5

        # Damped elastic collision
        rvx = b.vx - a.vx
        rvy = b.vy - a.vy
        rvn = rvx * nx + rvy * ny
        if rvn < 0:
            j = -rvn * 0.2
            a.vx -= nx * j
            a.vy -= ny * j
            b.vx += nx * j
            b.vy += ny * j

    def _process_merges(self, merges: list[tuple[float, float, int | None]]) -> bool:
        merged_ids = set()
        to_add = []
        for i in range(len(self.fruits)):
            if self.fruits[i].id in merged_ids:
                continue
            for j in range(i + 1, len(self.fruits)):
                if self.fruits[j].id in merged_ids:
                    continue
                a, b = self.fruits[i], self.fruits[j]
                if a.tier_idx != b.tier_idx:
                    continue
                dist = math.hypot(b.x - a.x, b.y - a.y)
                if dist <= (a.radius + b.radius) * 1.08:
                    mx = (a.x + b.x) / 2.0
                    my = (a.y + b.y) / 2.0
                    new_tier = a.tier_idx + 1 if a.tier_idx < 11 else None
                    merges.append((mx, my, new_tier))
                    merged_ids.add(a.id)
                    merged_ids.add(b.id)
                    if new_tier:
                        to_add.append(FruitState(self._next_id, new_tier, mx, my, 0.0, 0.0))
                        self._next_id += 1
                    break
        if merged_ids:
            self.fruits = [f for f in self.fruits if f.id not in merged_ids]
            self.fruits.extend(to_add)
            return True
        return False

    def _is_settled(self) -> bool:
        return all(abs(f.vx) < 0.15 and abs(f.vy) < 0.15 for f in self.fruits)

    def _check_game_over(self) -> None:
        for f in self.fruits:
            if f.y + f.radius > DEADLINE_Y and abs(f.vy) < 0.15:
                self.gameOver = True
                break


# ---------------------------------------------------------------------------
# SuikaEnv — Unified Gymnasium Environment
# ---------------------------------------------------------------------------

class SuikaEnv(gym.Env if HAS_GYM else object):
    """
    Unified Gymnasium environment wrapping both standalone & java backends natively.
    """
    metadata = {"render_modes": ["human", "rgb_array"]}

    OBS_DIM      = OBS_DIM
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
        self._client = None

        if backend == "java":
            from suika.bridge import BridgeClient
            self._client = BridgeClient(port=50052)

        if HAS_GYM:
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

    def reset(self, seed: int | None = None, options: dict | None = None):
        if seed is not None:
            self._seed = seed

        if self.backend == "java":
            if not self._client.is_connected():
                self._client.connect()
            flat = self._client.reset(self._seed)
        else:
            self._sim = StandaloneSimulator(seed=self._seed)
            flat = self._sim.encode_observation(self.action_bins)

        obs = self._get_obs(flat)
        return obs, {}

    def step(self, action: Any):
        if self.backend == "java":
            if not self._client.is_connected():
                self._client.connect()
            # Pass discrete action index directly as float, or raw continuous value
            a_val = float(action) if not hasattr(action, "__len__") else float(action[0])
            flat, reward, terminated, truncated, info = self._client.step(a_val)
            obs = self._get_obs(flat)
            return obs, float(reward), terminated, truncated, info
        else:
            if self._sim is None or self._sim.game_over:
                raise RuntimeError("Call reset() before stepping a terminated environment.")
            x = self._action_to_x(action)
            reward, merges, done = self._sim.drop(x)
            obs = self._get_obs(None)
            info = {
                "score":        self._sim.score,
                "step":         self._sim.step_count,
                "merges":       merges,
                "reward_terms": {"score_delta": reward},
            }
            return obs, float(reward), done, False, info

    def render(self):
        if self.render_mode == "rgb_array" and HAS_GYM:
            return self._render_rgb()

    def close(self):
        if self._client is not None:
            self._client.close()

    # ------------------------------------------------------------------

    def _action_to_x(self, action: Any) -> float:
        if self.action_space_type == "discrete":
            a = int(action)
            a = max(0, min(self.action_bins - 1, a))
            return DROP_X_MIN + (a / (self.action_bins - 1)) * (DROP_X_MAX - DROP_X_MIN)
        else:
            t = (float(action) + 1.0) / 2.0
            return DROP_X_MIN + max(0.0, min(1.0, t)) * (DROP_X_MAX - DROP_X_MIN)

    def _get_obs(self, flat: list[float] | None = None) -> Any:
        if flat is None:
            if self._sim is None:
                flat = [0.0] * self.OBS_DIM
            else:
                flat = self._sim.encode_observation(self.action_bins)

        if HAS_GYM:
            arr = np.array(flat, dtype=np.float32)
            if self.observation_type == "pixels":
                return self._state_to_pixels(flat)
            return arr
        return flat

    def _state_to_pixels(self, flat_obs: list[float]) -> "np.ndarray":
        # Draw board pixels directly from the symbolic state vector
        # (Supports both 'standalone' and 'java' backends cleanly)
        frame = np.zeros((self.PIXEL_H, self.PIXEL_W), dtype=np.float32)
        flat = list(flat_obs)

        # Globals
        fruit_count = int(round(flat[4] * MAX_FRUITS))
        deadline_y = flat[6] * CONTAINER_HEIGHT

        # Draw deadline
        dr = int(round((1.0 - deadline_y / CONTAINER_HEIGHT) * (self.PIXEL_H - 1)))
        if 0 <= dr < self.PIXEL_H:
            frame[dr, :] = 0.4

        for i in range(min(fruit_count, MAX_FRUITS)):
            base = 8 + i * 9
            if base + 8 >= len(flat):
                break
            fx = flat[base] * CONTAINER_WIDTH
            fy = flat[base + 1] * CONTAINER_HEIGHT
            ftier = int(round(flat[base + 6] * 11.0 + 1.0))
            fradius = flat[base + 7] * 4.0

            if ftier <= 0:
                continue

            intensity = ftier / 11.0
            r_px_x = max(1, int(round((fradius / CONTAINER_WIDTH) * self.PIXEL_W)))
            r_px_y = max(1, int(round((fradius / CONTAINER_HEIGHT) * self.PIXEL_H)))
            cx = int(round((fx / CONTAINER_WIDTH) * (self.PIXEL_W - 1)))
            cy = int(round((1.0 - fy / CONTAINER_HEIGHT) * (self.PIXEL_H - 1)))

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
        pixels = self._get_obs()
        grey = (pixels[0] * 255).astype(np.uint8)
        return np.stack([grey, grey, grey], axis=-1)