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

**Java 21+ must be on your `PATH`.** The ZIP bundles all JVM dependencies — no Python or GPU required for the game. GPU-accelerated Python training/inference is optional and installed on demand from **Settings → AI**.

> **Headless / training mode**
> ```bash
> ./suika-app --headless    # runs the built-in GA demo in the terminal
> ```

---

## What's new in v0.19.0

**v0.19.0** is a navigation / QOL UI pass on top of the v0.18 systems overhaul:

- **Clearer menu** — PLAY and AI PLAYGROUND lead; Settings + Lab sit as a utility pair; Quit is a quiet link.
- **Shared chrome** — `UiChrome` bottom bars, secondary/ghost buttons, Settings section jump chips (keys 1–7).
- **Readable tools** — Playground Export/Import + wider search; Lab and Game Over action hierarchy cleaned up.

See the full [CHANGELOG](CHANGELOG.md) for every release.


---

## Screens

| Screen | What you see |
|---|---|
| **Main Menu** | Title + **PLAY** / **AI PLAYGROUND** / Settings+Lab / RT row / quiet Quit; first-run help |
| **Settings** | Section jump chips + Display / Graphics / Sim / AI / Input / RT Lab / Data — drag sliders, reset |
| **Game (Human)** | Real-time physics — aim with the mouse or ←/→, click or Space to drop. HUD with score, best, next-fruit preview |
| **AI Playground** | Searchable capability matrix (13+5); Explorer/Researcher; Export/Import; status rail; **LAUNCH** |
| **Control Center** | Live boards, diagnostics, run controls, ONNX-aware slots, hotswap; experiment status rail |
| **Research Lab** | Reward Studio, dashboard runs, bounded bench, replay scrub, physics golden tools, plugins |
| **RT Lab** | Experimental Vulkan ray-traced game (own window); failures offer Retry / Settings |
| **Game Over** | Score / fruit / seed; **PLAY AGAIN** / **EXPORT SUMMARY** / **MAIN MENU** |

Crisp anti-aliased text (bundled Apache-2.0 DroidSans via FreeType), glossy depth-shaded
fruit, rounded glass container, merge particles — all procedural, **no external art files**.

### AI Playground & Control Center

Pressing **WATCH AI** opens the **AI Playground** — a self-contained browser of the
entire [capability matrix](#ai-algorithms-suika-ai)
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
  populated. Train in Python → export ONNX → play with `OrtOnnxPolicyRunner` / `OnnxAgent`
  (no Python at play time; stub kept for tests). Live GPU inference can still use the
  PyTorch worker bridge when enabled.
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

### AI Algorithms (suika-ai)

> The AI Playground surfaces a **curated** set (**13 techniques + 5 ensembles**).
> The table below is the broader JVM toolbox those are built from — including
> **library-only / retired-from-matrix** helpers that are not Playground entries.
> Optional GPU acceleration (Settings → AI ENVIRONMENT) routes Python-backed
> training and saved-net inference to CUDA when available.

| Algorithm | Class | Status |
|---|---|---|
| Greedy one-ply | `GreedyOnePlyAgent` | Playground |
| MCTS | `MctsAgent` | Playground |
| AlphaZero-style MCTS | `MctsAgent` + `PolicyValueNetwork` | Playground |
| Heuristic | `HeuristicAgent` | Playground |
| Neuroevolution (GA) | `GeneticTrainer` | Playground |
| CMA-ES | `CmaEsTrainer` | Playground |
| Population-based training | `PopulationBasedTraining` | Playground |
| Behavioral Cloning | `BehavioralCloningTrainer` | Playground |
| DAgger | `DAggerTrainer` | Playground |
| DQN | (JVM Q-learning loop) | Playground |
| Return-conditioned | `ReturnConditionedAgent` | Building block / ensemble |
| Random baseline | `RandomAgent` | Library only (retired from matrix) |
| Generative model | `GenerativeModelBridge` | Library / stub sampling |
| Adversarial curriculum | `AdversarialSequenceSetter` | Library helper |
| Self-play / league | `RacingSelfPlay` | Library only (retired from matrix) |

Python scripts `diffusion_policy.py` / `flow_matching.py` exist for research but are
**not** Playground matrix techniques.

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
- `BridgeServer` — TCP Gym sidecar (`--bridge-port N`); Python `suika.make(backend="java")`
- `BridgeTransport` — **shipping:** `InProcessTransport` + TCP `BridgeServer`.
  JEP / GraalPy / gRPC / shared-memory remain enum/contract placeholders.
- `ObservationCodec` — length-prefixed little-endian float32 wire format (opcodes reset/step/close)
- `GymBridge` / `PettingZooBridge` — Gymnasium-shaped JVM adapters
- `OnnxPolicyRunner` — **`OrtOnnxPolicyRunner`** (ONNX Runtime, lazy natives, shape checks,
  CUDA→CPU fallback) + **`StubOnnxPolicyRunner`** for dependency-free tests

### Dashboard & Telemetry (suika-dash)
- Headless telemetry helpers: `DashboardRegistry`, `EvolutionMetricsLogger`,
  `RewardDecompositionTracker`, `ActionHeatmap`, `ConsoleExporter`
- In-app dashboard / ImGui viewer and Reward Studio UI are **not** shipping yet
  (contracts only; see `docs/architecture.md`)

### Extensibility
- `AgentPlugin` / `TrainerPlugin` SPI — discovered via `java.util.ServiceLoader`
- `PluginRegistry` — runtime registration and lookup
- Schema-driven hyperparameter helpers (`HyperparamSchema`) and `AgentPreset` for headless/CLI;
  full Explorer / Researcher LibGDX UX is planned, not the current Settings/Playground redesign surface
- ONNX export config (`OnnxExportConfig`) for Python→file shipping; JVM load via `OrtOnnxPolicyRunner` / `OnnxAgent`

### Compatibility contracts
Frozen save / encoder / benchmark / prefs surfaces are documented in
[`docs/contracts.md`](docs/contracts.md) and gated in CI.

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
trainer.save_onnx("policy.onnx")     # copy to ~/.suikai/saves/.../model.onnx for OnnxAgent
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
Train in Python → export ONNX → place as `~/.suikai/saves/<id>/slotN/model.onnx` →
`OnnxAgent` / `AiSlotPlayer` / ensemble donor via `OrtOnnxPolicyRunner`.
Standalone Python sim remains the default fast training path; Java backend is opt-in.

Compatibility freezes: [`docs/contracts.md`](docs/contracts.md).

---

## Contributing

1. Fork and create a feature branch
2. `./gradlew build` must pass
3. `ruff check python/` and `cd python && pytest -q` must pass
4. Open a PR against `main`

---

## License

Apache 2.0
