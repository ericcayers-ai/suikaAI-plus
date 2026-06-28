# ADR 001 — Headless-First Physics Core

**Status:** Accepted  
**Date:** 2024

## Context

Suika AI Sandbox has two primary consumers of the game engine: the live game (needs
LibGDX rendering and player input) and the AI training loop (needs thousands of
rollouts per second on a machine with no display). These goals are in direct tension
if the engine is coupled to the renderer.

## Decision

The `suika-core` module contains **only** the dyn4j physics simulation and game rules.
It has zero dependency on LibGDX, OpenGL, or any display system. Rendering is handled
by `suika-game` (which declares the `GameRenderer` / `InputHandler` functional
interfaces and wires LibGDX on top). AI training runs directly against `suika-core`.

## Consequences

**Positive**
- The training loop, MCTS rollouts, and vectorised environments all run in any JVM
  without a display server — CI-friendly, server-friendly, Docker-friendly.
- `GameCore.snapshot()` clones the world in microseconds — MCTS plans tens of thousands
  of positions per second on a single machine.
- Testing is fast and deterministic: `new GameCore(seed)` + same actions → same outcome.
- The game itself is guaranteed to share the exact same rules as the AI's environment
  — no sim-to-real gap within the project.

**Negative / Trade-offs**
- `GameRenderer` and `GameLoop` live in `suika-game`, which means the live-play experience
  requires assembling the full stack at `suika-app` level. This is one extra indirection
  compared to a monolithic design.
- dyn4j is a pure-Java 2-D engine; it is not a GPU physics solver. This is intentional
  (portability, determinism) but means per-step cost is higher than native alternatives
  for very large population sizes. The current workaround is Project Loom virtual threads
  for embarrassingly-parallel episode evaluation.

## Alternatives Considered

- **LibGDX Box2D via JNI inside `suika-core`**: rejected — JNI forbids headless
  execution in environments without a native library; CI would require x86 binaries.
- **Separate "headless clone" of the engine**: rejected — maintenance nightmare;
  guaranteed divergence over time.
- **Python environment (Gymnasium native)**: rejected as the primary path — the JVM
  ecosystem (Maven, Gradle, DJL, ONNX Runtime) and cross-platform distribution are
  first-class requirements.
