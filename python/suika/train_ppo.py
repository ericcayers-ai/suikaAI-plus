"""
PPO training script using Stable-Baselines3 (ROADMAP §IV.3).

Train::

    python -m suika.train_ppo --timesteps 500000 --seed 42 --out models/ppo_suika

Requires: pip install stable-baselines3 torch tensorboard

The resulting policy is saved as both an SB3 checkpoint (.zip) and an ONNX
model (policy.onnx) that OnnxPolicyRunner.java loads for in-game inference.

TensorBoard: basic metrics (rollout/episode reward+length, train/loss, etc.) are
always written to --tb-logdir. Pass --tb-detailed for additional per-episode score
scalars and periodic policy-weight histograms — see DetailedLoggingCallback below.
The suika-game app's SETUP -> TensorBoard button starts a local TensorBoard server
pointed at this same directory and opens it in the browser.
"""

from __future__ import annotations

import argparse
import datetime
from pathlib import Path


def default_tb_logdir() -> str:
    """Matches TensorboardLauncher.logDir("ppo") on the Java side exactly, so the
    app's OPEN button can always find these logs regardless of --out."""
    return str(Path.home() / ".suikai" / "tb_logs" / "ppo")


def make_env(seed: int = 0, action_bins: int = 32):
    from suika.env import SuikaEnv

    def _init():
        env = SuikaEnv(action_space_type="discrete", action_bins=action_bins, seed=seed)
        return env

    return _init


def _make_run_info_callback(hparams: dict):
    """Writes a ``run_info`` text summary once training starts — the run's hyperparameters
    and start time, so a run picked out of TensorBoard's list (``ppo/run_3``, thanks to
    SB3's own auto-incrementing ``tb_log_name``) is identifiable without cross-referencing
    the app or shell history. Always attached, independent of ``--tb-detailed`` (which only
    gates the EXTRA per-batch/histogram logging, not baseline identifiability)."""
    from stable_baselines3.common.callbacks import BaseCallback
    from stable_baselines3.common.logger import TensorBoardOutputFormat

    class RunInfoCallback(BaseCallback):
        def _on_training_start(self) -> None:
            for fmt in self.logger.output_formats:
                if isinstance(fmt, TensorBoardOutputFormat):
                    lines = "  \n".join(f"{k}: {v}" for k, v in hparams.items())
                    fmt.writer.add_text("run_info", f"technique: ppo  \nstarted: {datetime.datetime.now()}  \n{lines}  \n", 0)
                    break

        def _on_step(self) -> bool:
            return True

    return RunInfoCallback()


def _make_detailed_callback():
    """A BaseCallback that logs per-episode score (a custom scalar the base SB3
    metrics don't include) and, every 20 rollouts, a histogram of each policy-net
    layer's weights — genuinely more detailed than the metrics SB3 writes by default,
    not just the same numbers logged more often.

    SB3's own logger.record() only supports scalars/strings/images/video/figures in
    this version (no histogram type), so weight histograms are written straight to
    the underlying torch SummaryWriter that SB3's TensorBoardOutputFormat already owns
    — no separate writer/log dir, so everything lands in the same TensorBoard run."""
    from stable_baselines3.common.callbacks import BaseCallback
    from stable_baselines3.common.logger import TensorBoardOutputFormat

    class DetailedLoggingCallback(BaseCallback):
        def __init__(self):
            super().__init__()
            self._rollouts_seen = 0

        def _tb_writer(self):
            for fmt in self.logger.output_formats:
                if isinstance(fmt, TensorBoardOutputFormat):
                    return fmt.writer
            return None

        def _on_step(self) -> bool:
            for info in self.locals.get("infos", ()):
                score = info.get("score")
                if score is not None and info.get("terminal_observation") is not None:
                    self.logger.record("custom/episode_score", float(score))
            return True

        def _on_rollout_end(self) -> None:
            self._rollouts_seen += 1
            if self._rollouts_seen % 20 != 0:
                return
            writer = self._tb_writer()
            if writer is None:
                return
            for name, param in self.model.policy.named_parameters():
                if param.dim() < 2:
                    continue  # skip biases — weights are the interesting distribution
                try:
                    writer.add_histogram(f"weights/{name}",
                                          param.detach().cpu().numpy().flatten(),
                                          self.num_timesteps)
                except Exception:
                    pass  # never let optional histogram logging break training

    return DetailedLoggingCallback()


def train(
    timesteps:        int   = 500_000,
    action_bins:      int   = 32,
    n_envs:           int   = 8,
    lr:               float = 3e-4,
    seed:             int   = 0,
    out_dir:          str   = "models/ppo_suika",
    device:           str   = "auto",
    gpu_mem_fraction: float = 1.0,
    tb_logdir:        str | None = None,
    tb_detailed:      bool  = False,
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

    tb_dir = tb_logdir or default_tb_logdir()
    Path(tb_dir).mkdir(parents=True, exist_ok=True)

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
        tensorboard_log=tb_dir,
        seed=seed,
        device=device,
    )
    # Plain ASCII only — Windows consoles default to cp1252/cp437, which mangles
    # Unicode punctuation like a middle dot into mojibake (see RtHud.java's identical
    # note for the same reason).
    print(f"Device: {model.device}  |  TensorBoard: {tb_dir}"
          + ("  |  detailed logging on" if tb_detailed else ""))

    callbacks = [_make_run_info_callback({
        "timesteps": timesteps, "action_bins": action_bins, "n_envs": n_envs,
        "lr": lr, "seed": seed, "device": str(model.device),
    })]
    if tb_detailed:
        callbacks.append(_make_detailed_callback())
    from stable_baselines3.common.callbacks import CallbackList
    model.learn(total_timesteps=timesteps,
                progress_bar=True,
                reset_num_timesteps=True,
                callback=CallbackList(callbacks))

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

    # ONNX export always happens on CPU — the tiny policy net doesn't benefit from
    # GPU for a single dummy forward pass, and it keeps the exported graph
    # device-independent for OnnxPolicyRunner.java (which loads it on whatever
    # device DJL/ONNX Runtime picks at inference time).
    wrapper = PolicyWrapper(model.policy).eval().to("cpu")
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
    parser.add_argument("--device",      type=str,   default="auto",
                         help="'auto' picks CUDA when available, else CPU; 'cpu' forces CPU-only.")
    parser.add_argument("--gpu-mem-fraction", type=float, default=1.0,
                         help="Cap this process's CUDA memory fraction (0-1); see train()'s docstring.")
    parser.add_argument("--tb-logdir", type=str, default=None,
                         help="TensorBoard log directory (default: ~/.suikai/tb_logs/ppo, "
                              "matching the app's OPEN TensorBoard button).")
    parser.add_argument("--tb-detailed", action="store_true",
                         help="Also log per-episode score and periodic weight histograms.")
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
        tb_logdir=args.tb_logdir,
        tb_detailed=args.tb_detailed,
    )


if __name__ == "__main__":
    main()
