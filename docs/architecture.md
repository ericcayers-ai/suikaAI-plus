# Suika AI Sandbox — Architecture Overview

## Module Graph

```
suika-core        headless dyn4j physics engine, GameCore, FruitTier, modding types
    ↑
suika-assets      data-driven FruitDefinition / AssetRegistry (fruits.json)
suika-env         Gymnasium-shaped environment (SuikaEnv, VectorEnv, encoders, reward)
suika-bridge      Java↔Python boundary (BridgeServer, GymBridge, ONNX Runtime deploy)
suika-ai          plugin SPI, neuroevolution, MCTS, imitation, benchmark suite
suika-game        LibGDX UI + Playground / Control Center + RT Lab + OnnxAgent
suika-dash        RunMetrics, DashboardRegistry, ConsoleExporter, ActionHeatmap (headless)
    ↑
suika-app         application entry point, AgentPreset, HyperparamSchema, OnnxExportConfig
```

Dependencies flow downward only — `suika-core` has no runtime dependencies beyond dyn4j.

Frozen compatibility surfaces (ModelSlots, encoders, BenchmarkSuite seeds, prefs keys)
are documented in [`contracts.md`](contracts.md).

## The Java ↔ Python Boundary (`suika-bridge`)

The roadmap's hybrid boundary (§II.4 / ADR-0003) is expressed as a JVM-side **contract**:

- `BridgeTransport` — the channel carrying observations out / actions back.
  **Shipping today:** `InProcessTransport` (dependency-free tests / JVM-native paths).
  JEP, GraalPy, gRPC sidecar, and Arrow shared-memory remain enum / javadoc placeholders
  for later phases; training/play bridging uses the TCP `BridgeServer` below.
- `BridgeServer` — TCP Gym sidecar wrapping `GymBridge`. Start with
  `./gradlew :suika-app:run --args="--bridge-port 50052"`. Python connects via
  `suika.make(backend="java", port=50052)` (standalone remains the default/fast path).
- `BridgeConfig` — selects the mechanism (`JEP`, `GRAALPY`, `GRPC_SIDECAR`,
  `SHARED_MEMORY`, `DJL_ONNX`).
- `ObservationCodec` — length-prefixed little-endian tensor wire format (stand-in for
  Arrow zero-copy). Bridge frames use opcodes: reset=`[0,seed]`, step=`[1,action]`, close=`[2]`.
- `GymBridge` / `PettingZooBridge` — Gymnasium-shaped adapters over `SuikaEnv`.
- `OnnxPolicyRunner` — no-Python deployment seam. **Shipping:** `OrtOnnxPolicyRunner`
  (Microsoft ONNX Runtime: lazy natives, action-head shape checks, CUDA→CPU fallback).
  `StubOnnxPolicyRunner` remains for dependency-free tests when natives are absent.
  Play path: drop `model.onnx` in a ModelSlots folder → `OnnxAgent` / `AiSlotPlayer` /
  ensembler donor blend without Python.

## Two Front Doors (shipping UX)

| Mode       | Audience        | Entry point                              | Status |
|------------|-----------------|------------------------------------------|--------|
| Explorer   | casual / player | Curated Playground matrix + hardware presets | **Shipping** in LibGDX Playground / Control Center |
| Researcher | ML practitioner | Full hyperparam panels + PluginRegistry rows | **Shipping**; plugins listed as informational rows / Lab Hub |

Headless/CLI helpers (`AgentPreset`, `HyperparamSchema`) remain available. Shipping Playground
matrix: **13 techniques + 5 ensembles**.

## Key Invariants

- **Determinism**: `new GameCore(seed)` + same action sequence → identical trajectory
  (guarded by golden physics tests).
- **Headless ≥ Headful**: every feature works without a display (`ObservationMode.STATE`).
- **Plugin SPI**: new algorithm = new `AgentPlugin` class + `META-INF/services` registration.
- **Environment Contract**: `SuikaEnv.reset()` / `SuikaEnv.step(action)` mirrors Gymnasium's API.
- **Fruit sync**: `FruitTier` ≡ `FruitLadder.standard` ≡ `fruits.json` ≡ Python `FRUIT_TIERS`.

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
Explorer uses the curated `AiTechnique` set; Researcher mode and the Lab Hub **Plugins**
tab surface discovered plugins (informational — they do not expand the curated matrix).

## Snapshot / Planning

`GameCore.snapshot()` deep-clones the entire physics world. MCTS and one-ply lookahead
agents use snapshots as a perfect world model — branch, simulate N steps, discard.

## Dashboard / Reward Studio

`suika-dash` ships headless metric publishers and exporters. The Research **Lab Hub** also
ships in-app panels for Reward Studio, DashboardRegistry runs, bounded bench, replay scrub,
physics golden tooling, and plugin discovery. These are research surfaces — lean, not a full
ImGui chart chrome replacement (`ConsoleExporter` remains for rich export).
