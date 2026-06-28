"""
Suika AI Sandbox — Python package.

Provides:
  - Gymnasium-compatible environment backed by the standalone Python sim
    or the Java suika-core engine via gRPC bridge (see ROADMAP §II.4)
  - Behavioral Cloning trainer (bc.py)
  - PPO training script via Stable-Baselines3 (train_ppo.py)
  - Diffusion Policy (diffusion_policy.py)
  - Flow Matching policy (flow_matching.py)
  - Decision Transformer (decision_transformer.py)
  - Java bridge client (bridge.py)

Quick start::

    import suika
    env = suika.make(action_bins=32, seed=42)
    obs, info = env.reset()
    obs, reward, terminated, truncated, info = env.step(env.action_space.sample())
"""

from suika.env import SuikaEnv


def make(
    observation:       str = "state",
    action_space:      str = "discrete",
    action_bins:       int = 32,
    seed:              int = 0,
    backend:           str = "standalone",
    **kwargs,
) -> SuikaEnv:
    """Factory matching the Gymnasium ``gym.make`` contract."""
    return SuikaEnv(
        observation=observation,
        action_space_type=action_space,
        action_bins=action_bins,
        seed=seed,
        backend=backend,
        **kwargs,
    )


def make_vec(num_envs: int = 8, **kwargs) -> list[SuikaEnv]:
    """Create ``num_envs`` independent SuikaEnv instances (vectorised env pool)."""
    return [make(seed=i, **kwargs) for i in range(num_envs)]


__version__ = "0.1.0"
__all__ = ["SuikaEnv", "make", "make_vec"]
