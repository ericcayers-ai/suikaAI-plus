# Suika AI Sandbox — Architecture Overview

## Module Graph

```
suika-core        headless dyn4j physics engine, GameCore, FruitTier, modding types
    ↑
suika-assets      data-driven FruitDefinition / AssetRegistry (fruits.json)
suika-env         Gymnasium-shaped environment (SuikaEnv, VectorEnv, encoders, reward)
suika-bridge      Java↔Python boundary (BridgeTransport, GymBridge, PettingZooBridge, ONNX deploy)
suika-ai          plugin SPI, neuroevolution, MCTS, imitation, IRL, offline RL, benchmark
suika-game        fixed-timestep game loop, renderer/input interfaces (LibGDX boundary)
suika-dash        RunMetrics, DashboardRegistry, ConsoleExporter, ActionHeatmap
    ↑
suika-app         application entry point, AgentPreset, HyperparamSchema, OnnxExportConfig
```

Dependencies flow downward only — `suika-core` has no runtime dependencies beyond dyn4j.

## The Java ↔ Python Boundary (`suika-bridge`)

The roadmap's hybrid boundary (§II.4) is expressed as a JVM-side contract:

- `BridgeTransport` — the channel carrying observations out / actions back. Real
  implementations wrap JEP, GraalPy, a gRPC sidecar, or an Arrow shared-memory ring;
  `InProcessTransport` is the dependency-free path for tests and JVM-native inference.
- `BridgeConfig` — selects the mechanism (`JEP`, `GRAALPY`, `GRPC_SIDECAR`,
  `SHARED_MEMORY`, `DJL_ONNX`).
- `ObservationCodec` — length-prefixed little-endian tensor wire format (the
  dependency-free stand-in for Arrow zero-copy).
- `GymBridge` / `PettingZooBridge` — Gymnasium and PettingZoo adapters over `SuikaEnv`,
  the JVM half that Python `gym.make` / `parallel_env` drive.
- `OnnxPolicyRunner` — the no-Python deployment seam: load an exported ONNX policy and
  run inference on the JVM (pairs with `OnnxExportConfig` in suika-app).

## Two Front Doors

| Mode       | Audience        | Entry point                              |
|------------|-----------------|------------------------------------------|
| Explorer   | casual / player | `AgentPreset` enum → one-click presets   |
| Researcher | ML practitioner | `HyperparamSchema` → full config panel   |

## Key Invariants

- **Determinism**: `new GameCore(seed)` + same action sequence → identical trajectory.
- **Headless ≥ Headful**: every feature works without a display (`ObservationMode.STATE`).
- **Plugin SPI**: new algorithm = new `AgentPlugin` class + `META-INF/services` registration, zero engine edits.
- **Environment Contract**: `SuikaEnv.reset()` / `SuikaEnv.step(action)` mirrors Gymnasium's API.

## Core Loop (headless)

```
GameCore.dropAndSettle(x)
  → dyn4j World.step(FIXED_DT) × N until settled
  → detect merges (distance < sumRadii)
  → award score, spawn merged fruit
  → check dead-line timer → terminated
  → return StepResult(observation, reward, terminated, truncated)
```

## Plugin Discovery

`PluginRegistry` uses `java.util.ServiceLoader` to discover all `AgentPlugin` and
`TrainerPlugin` implementations on the classpath. Third-party plugins are registered by
adding a `META-INF/services/dev.suika.ai.AgentPlugin` file to their JAR.

## Snapshot / Planning

`GameCore.snapshot()` deep-clones the entire physics world. MCTS and one-ply lookahead
agents use snapshots as a perfect world model — branch, simulate N steps, discard.
