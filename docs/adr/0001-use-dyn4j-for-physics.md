# ADR-0001: Use dyn4j as the Physics Engine

**Status:** Accepted  
**Date:** 2026-06-28

## Context

The game requires a 2D rigid-body physics engine to simulate fruit falling and stacking.
The original Suika Game (Aladdin X) uses a Matter.js-style solver on the web/Switch.
No JVM engine is bit-compatible with Matter.js, so we must calibrate empirically.

Candidates evaluated:

| Engine | License | Pure JVM? | Determinism | Headless | Activity |
|--------|---------|-----------|-------------|----------|----------|
| **dyn4j** | BSD | ✅ | Configurable | ✅ | Active |
| JBox2D | zlib | ✅ | Good | ✅ | Inactive |
| Bullet (JBullet) | zlib | ✅ | Poor (3D port) | ✅ | Dead |
| GraalJS + Matter.js | various | ❌ (JNI) | Reference | ✅ | Active |

## Decision

Use **dyn4j** (pure-Java, BSD-licensed) as the primary physics engine in `suika-core`.

Run **GraalJS + Matter.js** as a calibration oracle only — not shipped — to generate
reference trajectories for CMA-ES calibration (see ROADMAP §I.3).

## Consequences

- `suika-core` has zero native-code dependencies → fully headless, easily testable.
- Physics fidelity depends on calibration; we accept this and lock golden trajectories.
- dyn4j's determinism is same-machine reliable; cross-machine is best-effort with `strictfp`.
