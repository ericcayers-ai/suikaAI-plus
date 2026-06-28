# ADR-0003: JVM ↔ Python Boundary Strategy

**Status:** Accepted  
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

2. **Sidecar (gRPC + Apache Arrow / shared-memory)** — for heavy parallel training.
   Provides isolation, crash safety, and multi-process throughput. Zero-copy pixel
   batches via shared memory avoid serialisation bottlenecks.

3. **DJL / ONNX Runtime (JVM)** — for deployment. Training happens in Python; the
   final policy is exported to ONNX and loaded on the JVM. Players download one app
   with no `pip install`.

## Consequences

- Phase 0/1/2: only the gRPC sidecar stub is wired; JEP integration is Phase 3+.
- The Python `suika` package exposes a Gymnasium env that connects to the sidecar.
- ONNX export is the contract between Python training and JVM inference — any
  Python framework (PyTorch, JAX via XLA) can train as long as it exports valid ONNX.
