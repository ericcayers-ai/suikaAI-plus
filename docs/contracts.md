# Compatibility contracts (frozen)

These surfaces are **load/play backward-compat freezes**. Change them only with an
explicit migration path and a bump note in `CHANGELOG.md`. Phase 1 of the systems
overhaul gates them with unit tests and CI checks.

## Product version

| Surface | Location | Rule |
|---|---|---|
| Gradle `version` | root `build.gradle.kts` | **Canonical** — release tags must be `v$version` |
| Displayed UI / window / headless banner | `Theme.VERSION` ← generated `suika-version.properties` | Derived from Gradle at build time |
| Python package | `python/suika/__init__.py` `__version__` | Must equal Gradle version |

Release CI verifies all three surfaces match the tag.

## Model slots (`ModelSlots`)

| Constant | Value | Meaning |
|---|---|---|
| `SLOT_COUNT` | `3` | Slots per technique under `~/.suikai/saves/<id>/` |
| `HIDDEN_SIZE` | `64` | Default MLP hidden width |
| `OUTPUT_BINS` | `32` | Default discrete action bins |
| Input dim | `StateObservationEncoder.TOTAL` (584) | Must match encoder contract |
| Folder kind | `weights` / `config` | Written in `info.txt` |
| Live format | `slotN/` with `info.txt`, `progress.txt`, `model.txt`, optional `model.onnx`, `.sav` | v0.13+ |
| Legacy format | `slotN.dat` binary | **Read-only**; never rewrite. Weights: `int paramCount`, `int len`, `double[len]`, `long savedAt`, `double score`. Config: magic `-2`, then `int 0`, `long`, `double`, `int n`, `UTF+double` pairs |

Saves under `~/.suikai` must keep loading after upgrades. Reject architecture mismatches; do not silently reshape weights.

## Observation encoders (`suika-env`)

| Mode | Shape | Notes |
|---|---|---|
| `STATE` | `[584]` = `8 + 64×9` | Canonical deploy / ModelSlots input |
| `HYBRID` | `[CHANNELS × GRID_H × GRID_W]` | Raster heatmap |
| `PIXELS` | `[4 × 84 × 84]` | Software raster; frame stack of 4 |

Do not change `StateObservationEncoder.TOTAL`, `MAX_FRUITS`, or `PER_FRUIT` without a versioned encoder migration and ModelSlots reader.

## Action space defaults

- Discrete default bins: **32** (also `BenchmarkSuite` / `ModelSlots.OUTPUT_BINS`).
- Continuous: action in `[-1, 1]` mapped to `[DROP_X_MIN, DROP_X_MAX]`.

## Benchmark suite (`BenchmarkSuite`)

| Contract | Value |
|---|---|
| `STANDARD_SEEDS` | `[1, 42, 137, 999, 31415]` — **never reorder or replace** (historic leaderboard comparability) |
| Episodes per seed (default) | `3` |
| Max steps (default) | `500` |
| Action bins | `32` |

CI uses a **bounded** subset (fewer seeds / steps) with published score floors. Full suite remains the community submission standard.

## LibGDX preference keys (`SettingsPersistence`)

Store name: `suika-display-settings`.

| Key | Type | Purpose |
|---|---|---|
| `resHeightIndex` | int | Resolution preset index |
| `fullscreen` | boolean | Fullscreen flag |
| `uiScaleIndex` | int | UI scale preset |
| `preferGpu` | boolean | Legacy GPU preference (still written; derived from `gpuMode`) |
| `autosaveIndex` | int | Autosave interval preset |
| `jvmCpuOnly` | boolean | Legacy CPU-only flag |
| `gpuMode` | boolean | Compute mode; on first run migrates from `preferGpu && !jvmCpuOnly` |
| `customValueEntry` | boolean | Custom hyperparam entry |
| `watchdogEnabled` | boolean | Training watchdog |
| `reducedMotion` | boolean | Prefer reduced / zero-duration UI motion (added v0.18; default false) |
| `presetsCalibrated` | boolean | Hardware preset calibration done |
| `presetsSimsPerSec` | float | Calibration throughput |
| `fpsIndex` | int | Frame-rate preset index (workflow-redesign) |
| `vsync` | boolean | V-Sync |
| `smoothShading` | boolean | Glossy fruit shading |
| `particles` | boolean | Merge particles |
| `showGuide` | boolean | Drop guide line |
| `tierLabels` | boolean | Tier number labels |
| `screenShake` | boolean | Screen shake on merges |
| `binIndex` | int | Drop-column preset index |
| `randomSeed` | boolean | Random vs fixed seed |
| `fixedSeed` | long | Fixed seed value |
| `immediateDeadline` | boolean | Instant-fail deadline |
| `bounceEnabled` | boolean | Bouncy fruit restitution |
| `rt3dPhysics` | boolean | RT Lab 3D physics |
| `gpuUtilPercent` | int | Python training GPU memory fraction percent |
| `agentIndex` | int | Watch-AI agent index |
| `aiMoveDelay` | float | Watch-AI move delay seconds |
| `showThinking` | boolean | MCTS visit overlay |
| `firstRunHelpSeen` | boolean | Main-menu first-run help dismissed |

**Never rename or remove keys** without a load-time migration that still accepts the old name.
Custom numeric overrides (`customFps` / `customBins` / `customAutosaveMinutes`) stay session-only.

## Fruit ladder sync

Radii / merge scores / droppable flags must stay identical across:

1. `FruitTier.java`
2. `FruitLadder.standard()`
3. `suika-assets/.../fruits.json`
4. `python/suika/env.py` `FRUIT_TIERS`

Guarded by sync tests in Java and Python.

## Playground matrix

Shipping curated matrix: **13 techniques + 5 ensembles** (`AiTechnique`). Library-only / retired agents (`RandomAgent`, `RacingSelfPlay`, generative / diffusion / flow scripts, etc.) are not Playground matrix entries.
