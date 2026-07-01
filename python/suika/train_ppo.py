"""
PPO training script using Stable-Baselines3 (ROADMAP §IV.3).

Train::

    python -m suika.train_ppo --timesteps 500000 --seed 42 --out models/ppo_suika

Requires: pip install stable-baselines3 torch

The resulting policy is saved as both an SB3 checkpoint (.zip) and an ONNX
model (policy.onnx) that OnnxPolicyRunner.java loads for in-game inference.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def make_env(seed: int = 0, action_bins: int = 32):
    from suika.env import SuikaEnv

    def _init():
        env = SuikaEnv(action_space_type="discrete", action_bins=action_bins, seed=seed)
        return env

    return _init


def train(
    timesteps:        int   = 500_000,
    action_bins:      int   = 32,
    n_envs:           int   = 8,
    lr:               float = 3e-4,
    seed:             int   = 0,
    out_dir:          str   = "models/ppo_suika",
    device:           str   = "auto",
    gpu_mem_fraction: float = 1.0,
) -> None:
    try:
        from stable_baselines3 import PPO
        from stable_baselines3.common.env_util import make_vec_env
    except ImportError as e:
        raise SystemExit(
            "stable-baselines3 is required: pip install stable-baselines3 torch"
        ) from e

    if 0.0 < gpu_mem_fraction < 1.0:
        import torch
        if torch.cuda.is_available():
            # This caps the fraction of GPU memory this process is allowed to allocate —
            # the closest honest, real lever torch exposes to a "max GPU utilization"
            # setting; there's no first-class hard compute-throughput limiter in
            # stock PyTorch/CUDA, only memory-fraction (which in practice constrains
            # batch/model size and so indirectly limits how much of the card gets used).
            torch.cuda.set_per_process_memory_fraction(gpu_mem_fraction)
            print(f"GPU memory fraction capped at {gpu_mem_fraction:.0%}")

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    vec_env = make_vec_env(make_env(seed=seed, action_bins=action_bins),
                           n_envs=n_envs, seed=seed)

    model = PPO(
        policy="MlpPolicy",
        env=vec_env,
        learning_rate=lr,
        n_steps=2048,
        batch_size=256,
        n_epochs=10,
        gamma=0.99,
        gae_lambda=0.95,
        clip_range=0.2,
        ent_coef=0.01,
        verbose=1,
        tensorboard_log=str(out / "tb_logs"),
        seed=seed,
        device=device,
    )

    model.learn(total_timesteps=timesteps,
                progress_bar=True,
                reset_num_timesteps=True)

    checkpoint = str(out / "ppo_suika_final")
    model.save(checkpoint)
    print(f"\nSaved SB3 checkpoint: {checkpoint}.zip")

    onnx_path = out / "policy.onnx"
    _export_onnx(model, onnx_path, obs_dim=model.observation_space.shape[0])
    print(f"Saved ONNX model:      {onnx_path}")
    vec_env.close()


def _export_onnx(model, path: Path, obs_dim: int) -> None:
    import torch
    import torch.onnx

    class PolicyWrapper(torch.nn.Module):
        def __init__(self, policy):
            super().__init__()
            self.policy = policy

        def forward(self, obs):
            return self.policy.mlp_extractor.policy_net(
                self.policy.features_extractor(obs)
            )

    wrapper = PolicyWrapper(model.policy).eval()
    dummy = torch.zeros(1, obs_dim)
    torch.onnx.export(
        wrapper, dummy, str(path),
        input_names=["observation"],
        output_names=["policy_logits"],
        opset_version=17,
        dynamic_axes={"observation": {0: "batch"}, "policy_logits": {0: "batch"}},
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a PPO agent on Suika")
    parser.add_argument("--timesteps",   type=int,   default=500_000)
    parser.add_argument("--action-bins", type=int,   default=32)
    parser.add_argument("--n-envs",      type=int,   default=8)
    parser.add_argument("--lr",          type=float, default=3e-4)
    parser.add_argument("--seed",        type=int,   default=0)
    parser.add_argument("--out",         type=str,   default="models/ppo_suika")
    parser.add_argument("--device",      type=str,   default="auto")
    parser.add_argument("--gpu-mem-fraction", type=float, default=1.0,
                         help="Cap this process's CUDA memory fraction (0-1); see train()'s docstring.")
    args = parser.parse_args()

    train(
        timesteps=args.timesteps,
        action_bins=args.action_bins,
        n_envs=args.n_envs,
        lr=args.lr,
        seed=args.seed,
        out_dir=args.out,
        device=args.device,
        gpu_mem_fraction=args.gpu_mem_fraction,
    )


if __name__ == "__main__":
    main()
