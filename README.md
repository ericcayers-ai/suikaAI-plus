# Suika AI Sandbox

A research-grade platform for training, benchmarking, and shipping AI agents that play Suika (Watermelon) Game. The engine is headless, deterministic, and fully JVM-native; the Python layer provides Gymnasium-compatible training integrations.

[![CI](https://github.com/ericcayers-ai/suikaAI-plus/actions/workflows/ci.yml/badge.svg)](https://github.com/ericcayers-ai/suikaAI-plus/actions/workflows/ci.yml)

## Features

### Game Engine (suika-core)
- Deterministic rigid-body physics via **dyn4j** — same seed + same actions always produces the same game
- 11-tier fruit ladder (Cherry → Watermelon) with merge chaining and score tracking
- `GameCore.snapshot()` for zero-overhead MCTS/planning forks
- Moddable fruit ladders and physics constants via JSON config (`ModConfig`)
- Replay logging with full deterministic reconstruction

### AI Algorithms (suika-ai) — 100% JVM-native, no Python required
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
- `BridgeTransport` interface with `InProcessTransport` (tests/inference) and stubs for JEP / gRPC / shared-memory sidecar
- `ObservationCodec` — length-prefixed little-endian float32 wire format (same as Arrow)
- `GymBridge` — Gymnasium `(obs, reward, terminated, truncated, info)` contract on the JVM
- `PettingZooBridge` — two-player racing adapter for competitive training
- `OnnxPolicyRunner` + `StubOnnxPolicyRunner` — no-Python inference deploy path

### Dashboard & Telemetry (suika-dash)
- `DashboardRegistry` with pluggable metric publishers
- `EvolutionMetricsLogger` — per-generation best/mean fitness
- `RewardDecompositionTracker` — per-reward-term breakdown
- `ActionHeatmap` — drop-position heatmap across episodes
- `ConsoleExporter` — human-readable terminal output

### Extensibility (suika-app + plugin SPI)
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

### Build & Run Tests (JVM)

```bash
# Clone
git clone https://github.com/ericcayers-ai/suikaAI-plus.git
cd suikaAI-plus

# Build all modules
./gradlew build

# Run the full test suite
./gradlew test

# Start the headless demo (Explorer mode, 3 GA generations)
./gradlew :suika-app:run
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
./gradlew :suika-app:run
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
suika-game      — LibGDX rendering + game loop (display required)
suika-app       — entry point, wires everything + Explorer/Researcher UI
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
         (LibGDX)        (MCTS, GA,         (metrics,
                          BC, DAgger…)       heatmaps)
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

## Latest Release

See the [Releases page](https://github.com/ericcayers-ai/suikaAI-plus/releases/latest) for the pre-built `suika-app-<version>.zip` distribution.

Extract and run:
```bash
unzip suika-app-0.1.0.zip
cd suika-app-0.1.0/bin
./suika-app        # Linux/macOS
suika-app.bat      # Windows
```

The distribution bundles all JVM dependencies. Java 21+ must be on your PATH.

---

## Contributing

1. Fork and create a feature branch
2. `./gradlew build` must pass
3. `ruff check python/` must pass
4. Open a PR against `main`

---

## License

Apache 2.0
