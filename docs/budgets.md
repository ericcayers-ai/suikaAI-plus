# Performance budgets (v0.18)

Measurable targets for the systems overhaul. Profile before relaxing concurrency
or rendering. Numbers are **soft CI floors** unless marked hard.

| Surface | Budget | Notes |
|---|---|---|
| Classic / Control Center frame | ≤ 16.7 ms @ 60 FPS when idle | Soft; V-Sync / reduced motion respected |
| AI decision (MCTS 80 rollouts, 1 board) | ≤ 450 ms think budget | `PlaygroundConfig.maxThinkMs` |
| Bridge JVM↔Python round-trip | ≤ 5 ms local | Soft; IPC dominated |
| ONNX Runtime forward (584→32) | ≤ 5 ms CPU after warm | Soft; first call may load natives |
| Headless `BenchmarkFloorTest` | Completes in CI bound | Seeds truncated; score floors published in test |
| Capture showcase | Completes without crash | Matrix stays opt-in / bounded |
| Memory (Control Center, idle) | No unbounded thread pools after BACK | Runners dispose on `hide()` |

Golden physics fixtures must not drift without an intentional physics PR + rebless via
**Lab → Physics → REBLESS** (copies a snippet; never auto-edits tests).
