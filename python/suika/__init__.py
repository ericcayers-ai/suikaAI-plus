"""
Suika AI Sandbox — Python package.

Provides a Gymnasium-compatible environment backed by the Java suika-core
engine via gRPC sidecar or JEP (see ROADMAP §II.4).

Usage::

    import suika
    env = suika.make(observation="state", action_space="discrete", action_bins=32, seed=42)
    obs, info = env.reset()
    obs, reward, terminated, truncated, info = env.step(env.action_space.sample())
"""

from suika.env import SuikaEnv

def make(
    observation: str = "state",
    action_space: str = "discrete",
    action_bins: int = 32,
    seed: int = 0,
    **kwargs,
) -> "SuikaEnv":
    """Factory matching the Gymnasium ``gym.make`` contract."""
    return SuikaEnv(
        observation=observation,
        action_space_type=action_space,
        action_bins=action_bins,
        seed=seed,
        **kwargs,
    )


def make_vec(num_envs: int = 8, **kwargs) -> "list[SuikaEnv]":
    """Create ``num_envs`` independent SuikaEnv instances (vectorised env stub)."""
    return [make(**kwargs) for _ in range(num_envs)]
