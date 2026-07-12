# Suika AI Sandbox

A research-grade platform for training, benchmarking, and shipping AI agents that play Suika (Watermelon) Game. Includes a **fully windowed LibGDX game** with Human and AI Watch modes, a curated capability matrix of AI techniques and ensembles, hardware-calibrated quality presets, human-readable model saves, and an experimental **hardware ray-traced RT Lab** — no assets required, all graphics are procedurally generated.

[![CI](https://github.com/ericcayers-ai/suikaAI-plus/actions/workflows/ci.yml/badge.svg)](https://github.com/ericcayers-ai/suikaAI-plus/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ericcayers-ai/suikaAI-plus?sort=semver)](https://github.com/ericcayers-ai/suikaAI-plus/releases/latest)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

---

## Quick Start — Play or Watch AI

**Download the latest release** from the [Releases page](https://github.com/ericcayers-ai/suikaAI-plus/releases/latest) and extract `suika-app-<version>.zip`:

```bash
# Linux / macOS
cd suika-app-<version>/bin && ./suika-app

# Windows
cd suika-app-<version>\bin && suika-app.bat
```

**Java 21+ must be on your `PATH`.** The ZIP bundles all JVM dependencies — no Python or GPU required for the game. GPU-accelerated Python training/inference is optional and installed on demand from **Settings → AI ENVIRONMENT**.

> **Headless / training mode**
> ```bash
> ./suika-app --headless    # runs the built-in GA demo in the terminal
> ```

---

## What's new in v0.17.1

- **MCTS actually plans now.** A latent bug left UCB1's exploration term dwarfing its value
  term, so the search was close to random. Min-max value normalization + one-ply seeding of
  every root column fixed it — MCTS jumped from the ~400–650 range to ~1000+.
- **Survival-aware evaluation (`BoardEval`).** One shared scorer folds realized merges *and*
  board health (peak/average height, dead-line risk, merge readiness) into the value every
  simulate-and-pick agent uses, so the planners stop stalemating in the mid-hundreds and
  clear 1000 on the standard seeds. See [`docs/benchmarking.md`](docs/benchmarking.md).
- **Keyboard play everywhere.** ←/→ to aim + Space to drop in the 2D game (Down-arrow on the
  Imitation board), and a full WASD-aim + Space-drop scheme in the RT Lab, both alongside the
  mouse — never a mode toggle.
- **TensorBoard runs are distinguishable.** Each save/train writes a uniquely-named
  `run-<timestamp>` folder, with richer per-run metadata; the OPEN button now serves one
  dashboard rooted at all techniques so runs *and* techniques are separately selectable.
- **GPU inference for the MCTS + Policy Net ensemble**, plus honest "GPU" vs "CPU" labelling
  everywhere else.
- **Project health** — Apache-2.0 `LICENSE`, `CONTRIBUTING`, `CODE_OF_CONDUCT`, `SECURITY`,
  issue/PR templates, Dependabot, PR-triggered CI, an automated release workflow, and a
  [`CHANGELOG`](CHANGELOG.md).

See the full [CHANGELOG](CHANGELOG.md) for every release.

---

## Screens

| Screen | What you see |
|---|---|
| **Main Menu** | Title + **PLAY** / **WATCH AI** / **SETTINGS** / **QUIT**, ambient backdrop |
| **Settings** | Live graphics (FPS 30–240/unlimited, V-Sync, shading, particles, guide, labels, shake), simulation (drop columns, seed), and AI-watch knobs |
| **Game (Human)** | Real-time physics — aim with the mouse, click to drop, watch fruit fall and settle. HUD with score, best, next-fruit preview |
| **AI Playground** | The curated capability-matrix browser (12 techniques + 5 ensembles); pick one, configure it via the drawer (preset, speed, parallelism, per-technique + per-ensemble knobs), read its infocard, **LAUNCH** |
| **Control Center** | Per-technique diagnostics — live board(s), score/fitness/loss/diversity charts, MCTS search-tree + perception panels, SAVES/SETUP modals, runtime controls (pause, speed, restart) |
| **RT Lab** | Experimental raw-Vulkan hardware ray-traced game in its own window, with an in-window pause menu of live graphics settings (bloom / denoise / depth-of-field / accumulation) |
| **Game Over** | Final score, highest fruit, fruit-on-board, seed; **PLAY AGAIN** / **MAIN MENU** |

Crisp anti-aliased text (bundled Apache-2.0 DroidSans via FreeType), glossy depth-shaded
fruit, rounded glass container, merge particles — all procedural, **no external art files**.

### AI Playground & Control Center

Pressing **WATCH AI** opens the **AI Playground** — a self-contained browser of the
entire [capability matrix](#ai-algorithms-suika-ai--100-jvm-native-no-python-required)
(every technique, JVM and JVM+Python). Select a technique, tune its knobs
(**speed**, **parallelism**, and a family-specific hyper-parameter), and **LAUNCH** its
specialised control center:

- **Planning** (Heuristic, Greedy One-Ply, MCTS, AlphaZero) — plays live with move-latency
  and rollout diagnostics plus an MCTS search-tree diagram and visit-count overlay.
- **Evolution** (Neuroevolution GA, CMA-ES, Population-Based Training) — trains
  generation-by-generation on a background thread, hot-swaps each champion into the live
  board, shows the live **leader** across the champion + elite grid, and streams
  best / mean-fitness and diversity-σ convergence curves. Selectable selection mathematics
  (tournament / rank roulette / Boltzmann softmax), crossover, and σ-annealing.
- **Imitation** (DAgger, Behavioral Cloning) — a **"Train the AI"** card appears first; the
  AI watches while you play one full game capturing every drop, then trains live as you
  keep playing, charting loss and action-match accuracy and showing its predicted drop.
- **Model-based / Deep-RL** (MuZero, PPO, Decision Transformer) — probes for the managed
  Python venv, shows the exact training command and (when GPU deps are installed) the CUDA
  device, and runs a JVM-native surrogate live so the board and charts are always
  populated. Train in Python → export ONNX → deploy with `OnnxPolicyRunner`.
- **Ensembles** (Adaptive Voting Committee, Bandit Meta-Controller, MCTS + Policy Net,
  MCTS + Greedy Tiebreak, Return-Conditioned + MCTS Verify) — each composes real
  building-block agents, declares its members explicitly, and exposes its own knobs
  (donor net + slot, blend weight, tie threshold, UCB exploration, adapt rate). The
  learning ensembles persist their trust statistics through the SAVES slots.

---

## Features

### Windowed Game (suika-game + suika-app)
- LibGDX 1.12.1 + LWJGL3 backend — 720 × 1060 virtual-pixel viewport with `FitViewport` scaling
- Procedural fruit graphics: 11 distinct colors mapped to Cherry → Watermelon
- Human mode: click-to-drop with hover guide line and ghost preview
- AI Watch mode: live MCTS agent (`MctsAgent(50, √2, 5, 32)`)
- Screen transitions: Main Menu → Gameplay → Game Over → Main Menu
- `--headless` CLI flag bypasses the window for CI / training runs

### Game Engine (suika-core)
- Deterministic rigid-body physics via **dyn4j** — same seed + same actions always produces the same game
- 11-tier fruit ladder (Cherry → Watermelon) with merge chaining and score tracking
- `GameCore.snapshot()` for zero-overhead MCTS/planning forks
- Moddable fruit ladders and physics constants via JSON config (`ModConfig`)
- Replay logging with full deterministic reconstruction

### AI Algorithms (suika-ai) — 100% JVM-native

> The AI Playground surfaces a **curated** set (12 techniques + 5 ensembles). The
> `suika-ai` library below is the full JVM-native toolbox those are built from; every
> algorithm runs without Python. Optional GPU acceleration (Settings → AI ENVIRONMENT)
> routes Python-backed training and saved-net inference to CUDA when available.

| Algorithm | Class | Notes |
|---|---|---|
| Random baseline | `RandomAgent` | Uniform drop |
| Greedy one-ply | `GreedyOnePlyAgent` | Maximise immediate merge score |
| MCTS | `MctsAgent` | UCB1, configurable rollouts |
| AlphaZero-style MCTS | `MctsAgent` + `PolicyValueNetwork` | Policy-value net guides search |
| Neuroevolution (GA) | `GeneticTrainer` | Tournament + Gaussian mutation |
| CMA-ES | `CmaEsTrainer` | Separable CMA for faster evolution |
| Behavioral Cloning | `BehavioralCloningTrainer` | Supervised imitation from demos |
| DAgger | `DAggerTrainer` | MCTS-as-expert iterative imitation |
| Return-conditioned | `ReturnConditionedAgent` | DT-style RTG conditioning (JVM) |
| Generative model | `GenerativeModelBridge` | Board-aware softmax sampling (JVM) |
| Adversarial curriculum | `AdversarialSequenceSetter` | Random / greedy-worst / look-ahead |
| Population-based training | `PopulationBasedTraining` | Hyperparameter evolution |
| Self-play / league | `RacingSelfPlay` | Two-player seeded racing |

### Python Training (python/suika)
| Module | What it provides |
|---|---|
| `env.py` | Gymnasium-compatible `SuikaEnv` with standalone Python sim |
| `bc.py` | Behavioral Cloning (numpy + torch paths) + ONNX export |
| `train_ppo.py` | PPO training script via Stable-Baselines3 |
| `diffusion_policy.py` | DDPM diffusion policy with DDIM inference |
| `flow_matching.py` | Conditional Flow Matching (CFM) policy |
| `decision_transformer.py` | Full causal-transformer DT + `TrajectoryDataset` |
| `bridge.py` | TCP bridge client for Java-backed environment |

### Environment Contract (suika-env)
- Three observation modes: `STATE` (flat 584-dim vector), `PIXELS` (4×84×84), `HYBRID`
- Composable reward with configurable weights (score delta, merge bonus, deadline penalty)
- Vectorised env (`VectorEnv`) for parallel rollout collection
- Gymnasium-compatible `ActionSpace.Discrete` / `ActionSpace.Continuous`

### Java↔Python Bridge (suika-bridge)
- `BridgeTransport` interface with `InProcessTransport` and stubs for JEP / gRPC / shared-memory sidecar
- `ObservationCodec` — length-prefixed little-endian float32 wire format
- `GymBridge` — Gymnasium `(obs, reward, terminated, truncated, info)` contract on the JVM
- `PettingZooBridge` — two-player racing adapter for competitive training
- `OnnxPolicyRunner` + `StubOnnxPolicyRunner` — no-Python inference deploy path

### Dashboard & Telemetry (suika-dash)
- `DashboardRegistry` with pluggable metric publishers
- `EvolutionMetricsLogger` — per-generation best/mean fitness
- `RewardDecompositionTracker` — per-reward-term breakdown
- `ActionHeatmap` — drop-position heatmap across episodes
- `ConsoleExporter` — human-readable terminal output

### Extensibility
- `AgentPlugin` / `TrainerPlugin` SPI — discovered via `java.util.ServiceLoader`
- `PluginRegistry` — runtime registration and lookup
- Schema-driven hyperparameter UI (`HyperparamSchema`) for Explorer / Researcher modes
- `AgentPreset` (Quick Learner, Competitive, Researcher) for one-click configuration
- ONNX export config (`OnnxExportConfig`) for model shipping

---

## Getting Started (Development)

### Prerequisites
- **Java 21** (JDK — tested with Temurin 21 LTS)
- **Python 3.10+** (optional, for training scripts)
- Git

### Build & Run

```bash
# Clone
git clone https://github.com/ericcayers-ai/suikaAI-plus.git
cd suikaAI-plus

# Build all modules + run all tests
./gradlew build

# Launch the GUI game
./gradlew :suika-app:run

# Launch headless training demo
./gradlew :suika-app:run --args="--headless"

# Build distribution ZIP
./gradlew :suika-app:distZip
# → suika-app/build/distributions/suika-app-<version>.zip
```

### Python Environment

```bash
cd python

# Minimal (standalone sim, no GPU)
pip install -e .

# With Gymnasium
pip install -e ".[gym]"

# Full training stack (torch + SB3)
pip install -e ".[training]"
```

Quick sanity check:

```python
import suika
env = suika.make(action_bins=32, seed=42)
obs, info = env.reset()
print(obs.shape)          # (584,)
for _ in range(10):
    obs, reward, done, _, info = env.step(env.action_space.sample())
print("score:", info["score"])
```

### Train a Policy

**Neuroevolution (no Python, no GPU)**
```bash
./gradlew :suika-app:run --args="--headless"
# Runs the built-in GA loop; outputs scores per generation
```

**PPO via SB3 (Python + GPU)**
```bash
python -m suika.train_ppo --timesteps 1000000 --n-envs 8 --out models/ppo
# Saves models/ppo/ppo_suika_final.zip and models/ppo/policy.onnx
```

**Behavioral Cloning from recordings**
```python
from suika.bc import BCTrainer, DemoDataset
ds = DemoDataset.from_recordings("demos/")
trainer = BCTrainer(obs_dim=584, num_actions=32)
trainer.train(ds, epochs=20)
trainer.save_onnx("policy.onnx")     # load with OnnxPolicyRunner.java
```

**Diffusion Policy**
```python
from suika.diffusion_policy import DiffusionPolicy, train_diffusion
from suika.bc import DemoDataset
ds     = DemoDataset.from_recordings("demos/")
policy = DiffusionPolicy(obs_dim=584, action_dim=1, T=50)
train_diffusion(policy, ds, epochs=50)
policy.save("models/diffusion_policy.pt")
action = policy.predict_action(obs, n_steps=10)   # continuous ∈ [-1, 1]
```

**Decision Transformer**
```python
from suika.decision_transformer import DecisionTransformer, TrajectoryDataset, train_dt
ds = TrajectoryDataset.from_recordings("demos/")
dt = DecisionTransformer(obs_dim=584, action_dim=32, ctx_len=20)
train_dt(dt, ds, epochs=50)
dt.save("models/dt.pt")
action = dt.predict(obs_history, action_history, rtg_history, target_return=2000)
```

---

## Module Map

```
suika-core      — deterministic physics engine (dyn4j, no rendering)
suika-assets    — fruit definitions, atlas packing
suika-env       — obs encoders, action/reward, vectorised env
suika-bridge    — Java↔Python boundary (transports, codec, Gym/PettingZoo adapters)
suika-ai        — all JVM-native AI algorithms + plugin SPI
suika-dash      — metrics, heatmaps, replay viewer, exporters
suika-game      — LibGDX rendering + input + screens (display required)
suika-app       — entry point, DesktopLauncher, Explorer/Researcher UI
python/suika/   — Gymnasium env + PyTorch training toolkit
```

---

## Architecture Overview

```
                      ┌─────────────┐
    Human/Agent ─────►│  suika-app  │◄── AgentPreset / HyperparamSchema
                      └──────┬──────┘
                             │
              ┌──────────────┼─────────────────┐
              ▼              ▼                  ▼
         suika-game      suika-ai           suika-dash
         (LibGDX UI)     (MCTS, GA,         (metrics,
         MainMenuScreen   BC, DAgger…)       heatmaps)
         SuikaScreen
         GameOverScreen
              │              │
              └──────────────┤
                             ▼
                        suika-env
                   (obs, reward, action)
                             │
                             ▼
                        suika-core
                   (dyn4j physics engine)
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
               suika-bridge      python/suika
               (JVM side)       (training side)
           BridgeTransport   ◄──►  BridgeClient
           GymBridge              (PPO/BC/DT/
           OnnxPolicyRunner        Diffusion…)
```

**Deploy path (no Python)**:
Train in Python → export ONNX → load with `OnnxPolicyRunner` → shipped in game JAR.

---

## Contributing

1. Fork and create a feature branch
2. `./gradlew build` must pass
3. `ruff check python/` must pass
4. Open a PR against `main`

---

## License

Apache 2.0
