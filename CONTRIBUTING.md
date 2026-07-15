# Contributing to Suika AI Sandbox

Thanks for your interest in improving the project! This guide covers everything you need
to build, test, and land a change.

## Ways to contribute

- **Report a bug** — open a [Bug report](https://github.com/ericcayers-ai/suikaAI-plus/issues/new?template=bug_report.yml).
- **Request a feature or a new AI technique** — open a [Feature request](https://github.com/ericcayers-ai/suikaAI-plus/issues/new?template=feature_request.yml).
- **Add an AI algorithm** — see [`docs/add-an-algorithm.md`](docs/add-an-algorithm.md); the
  plugin registry makes new agents/trainers first-class without touching the UI.
- **Improve docs** — the [`docs/`](docs) directory (architecture, ADRs, the algorithm guide)
  is as important as the code.
- **Send a pull request** — see the workflow below.

## Project layout

This is a multi-module Gradle build (Java 21 toolchain). The dependency arrows point
downward — lower modules never import higher ones:

| Module | Responsibility |
|---|---|
| `suika-core` | Pure game rules + dyn4j physics (`GameCore`, `PhysicsConfig`). No UI, no AI. |
| `suika-env` | RL environment contract: observation encoding, action spec, reward. |
| `suika-ai` | Agents & trainers (MCTS, Greedy, Heuristic, GA, CMA-ES, DQN, BC, DAgger, `BoardEval`). |
| `suika-assets` | Procedural assets (fonts, colours) + data-driven `fruits.json`. |
| `suika-bridge` | Java↔Python boundary (`BridgeServer`, `GymBridge`, `BridgeTransport`, ONNX Runtime deploy). |
| `suika-dash` | Headless telemetry (`DashboardRegistry`, exporters, heatmaps). |
| `suika-game` | LibGDX game + Playground / Control Center / **Lab Hub** + RT Lab (Vulkan). |
| `suika-app` | Entry point / distribution packaging. |
| `python/suika` | Optional Python training (PPO via SB3, Decision Transformer, BC). |

Frozen compatibility: [`docs/contracts.md`](docs/contracts.md). Performance budgets:
[`docs/budgets.md`](docs/budgets.md). Overhaul wrap: [`ROADMAP-NEXT.md`](ROADMAP-NEXT.md) §Overhaul.

See [`docs/architecture.md`](docs/architecture.md) for the full picture and
[`docs/adr/`](docs/adr) for the reasoning behind key decisions.

## Building & running

**Prerequisites:** JDK 21 (the build pins the toolchain to 21; newer JDKs are not used
even if `java` on your `PATH` is newer).

```bash
# Build everything
./gradlew build

# Run the game
./gradlew :suika-app:run

# Headless GA demo (no window)
./gradlew :suika-app:run --args="--headless"
```

On Windows, if your default `java` is not 21, point Gradle at a JDK 21 install:

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-21"   # Git Bash
```

## Testing

Every change should keep the suite green:

```bash
./gradlew test                      # all modules
./gradlew :suika-core:test          # one module
./gradlew :suika-ai:test
```

- **Unit tests** live in each module's `src/test/java`.
- **Python contract tests** — `cd python && pytest` (also run in CI).
- **Headless AI benchmarks** — the fastest way to check an agent change hasn't regressed
  strength is the score bench described in [`docs/benchmarking.md`](docs/benchmarking.md);
  CI gates a bounded `BenchmarkFloorTest`.
- **Visual QA** — the `CaptureHarness` (`-Dsuika.capture.dir=...`) renders screens headlessly;
  PR CI runs a bounded smoke capture; a wider `capture-matrix` job is opt-in via workflow_dispatch.
- **Compatibility** — see [`docs/contracts.md`](docs/contracts.md) for frozen ModelSlots /
  encoder / benchmark / prefs surfaces.

Python changes must pass `ruff` + `pytest`:

```bash
pip install -e "./python[dev]"
ruff check python/
cd python && pytest -q
```

## Pull-request workflow

1. **Fork** and create a topic branch off `main` (`git checkout -b fix/overflow-at-speed`).
2. **Make focused commits.** Match the surrounding code's style — the codebase favours
   explanatory comments that say *why*, not *what*.
3. **Add or update tests** for behaviour changes. Agent-strength changes should cite bench
   numbers in the PR description.
4. **Run `./gradlew build` and the tests locally** before pushing.
5. **Open a PR** using the template. Link the issue it closes, describe the change, and note
   how you verified it. CI (Java tests, Python lint+pytest, bounded benchmark, capture smoke)
   runs automatically on the PR.
6. A maintainer reviews; address feedback by pushing follow-up commits (no force-push during
   review, please — it makes re-review harder).

## Coding conventions

- **Java 21**, `--enable-preview` where the module opts in. Prefer records, switch
  expressions, and sealed types where they clarify intent.
- **No dead code / no speculative abstractions.** Add the seam when the second caller arrives.
- **Comments explain the non-obvious** — a physics constant, a threading invariant, a
  numeric tuning — not the syntax.
- **Determinism matters** for the sim: seed your RNGs; never call wall-clock time inside a
  step. Tests rely on `GameCore(seed)` reproducing exactly.
- **Honesty in the UI** — never label something "GPU" / "trained" / "TensorBoard-backed"
  unless it genuinely is (see `GpuProbe` and `AiTechnique` for the pattern).

## Reporting security issues

Please **do not** open a public issue for a security vulnerability. See
[`SECURITY.md`](SECURITY.md) for private disclosure.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating you
agree to uphold it.
