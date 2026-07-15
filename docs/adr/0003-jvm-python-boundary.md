# ADR-0003: JVM ↔ Python Boundary Strategy

**Status:** Accepted (bridge-deploy update 2026-07-16)  
**Date:** 2026-06-28

## Context

The project must support "Python directly inside the game" (ROADMAP §II.4) while also
scaling to heavy parallel training workloads. Python owns the deep-learning ecosystem;
the JVM owns the game and real-time UI.

## Decision

Use a **hybrid strategy** with three layers:

1. **Embedded (JEP or GraalPy)** — for the in-app Python Lab console and low-latency
   inference. Satisfies the "Python directly inside the game" requirement literally.
   A user opens a panel, writes `policy(obs)`, and watches the fruit drop.
   *Status: still planned; not required for training or ONNX deploy.*

2. **Sidecar** — for heavy parallel training and exact-physics env access.
   - **Shipping today:** TCP `BridgeServer` wrapping `GymBridge`, started via
     `--bridge-port N` on `suika-app`. Python `suika.make(backend="java")` uses
     `BridgeClient` / `JavaBackedSuikaEnv`. Wire format is length-prefixed float32
     with command opcodes (reset / step / close).
   - **Later:** gRPC + Apache Arrow / shared-memory for zero-copy pixel batches.

3. **ONNX Runtime (JVM)** — for deployment. Training happens in Python; the final
   policy is exported to ONNX and loaded on the JVM via `OrtOnnxPolicyRunner`
   (measured choice over DJL: smaller policy-only surface, bundled CPU natives,
   CUDA→CPU session fallback). Players download one app with no `pip install`.
   `StubOnnxPolicyRunner` remains for tests when natives are unavailable.

## Consequences

- Standalone Python sim stays the **default / fast** training path; Java backend is opt-in.
- ONNX export (`policy_logits`, obs dim 584, action bins 32) is the contract between
  Python training and JVM inference — PPO/BC drop `model.onnx` into ModelSlots and
  play through `OnnxAgent` / ensemble donors without Python at play time.
- Observation dims, ModelSlots layout, and STANDARD_SEEDS remain frozen
  ([`contracts.md`](../contracts.md)); change only with a versioned migration.
