# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) (pre-1.0: minor versions may
include breaking changes).

## [Unreleased]

## [0.17.2] - 2026-07-14

### Fixed
- MCTS / auto-drop / ensembles now share the full `BoardEval.placement` scale (not raw
  score + health for search, or raw Δscore for auto-drop refinement).
- Headless `dev.suika.game.Bench` restored; `BenchmarkSuite` averages true per-seed
  re-runs (`-Deps`) instead of silently shifting the seed each episode.
- Fruit radii re-synced across `FruitTier`, `FruitLadder`, `fruits.json`, and
  `python/suika/env.py`.
- PPO ONNX export now includes SB3's `action_net` head (logits shape = action bins).
- CI capture job passes `-Dsuika.capture.*` via `JAVA_OPTS`; PR CI also runs
  `:suika-game:test` / `:suika-assets:test`; release workflow verifies tag ↔ Gradle version.
- Think-time budget and root-parallelism reach MCTS *ensembles*; GPU inference bridges
  are closed on soft startup failure and technique teardown.
- Control Center: no human drops while paused; ESC closes the SAVES modal first.
- Greedy continuous actions return `[-1,1]` as `ActionSpec.toDropX` expects.
- Ensemble donor picker no longer offers PPO/MuZero (ONNX path was never wired).

### Changed
- Heuristic danger band matches `BoardEval.DANGER_BAND`; AiTechnique docs match the
  shipping 13+5 matrix; menu / README keyboard hints updated.

## [0.17.1] — 2026-07-12

### Fixed
- **MCTS was barely better than random.** UCB1's exploitation term (realized-merge + board
  health, on a compressed scale) was dwarfed by the √2 exploration term, so visit counts —
  and therefore the chosen move — were near-uniform. Added MuZero-style min-max value
  normalization plus one-ply seeding of every root column, so search now concentrates on
  genuinely good columns. MCTS mean score jumped from the ~400–650 range to ~1000+.
- **TensorBoard runs were indistinguishable.** Every save/train wrote into one shared event
  folder, silently collapsing separate runs and techniques into one curve. Each run now
  writes a uniquely-named `run-<timestamp>` subfolder under `tb_logs/<technique>/`, and the
  OPEN button serves a single dashboard rooted at all techniques so runs *and*
  techniques/ensembles are separately selectable.
- **Benchmark classpath / release-jar version drift** that could evaluate against a stale
  build.

### Added
- **`BoardEval`** — one shared, survival-aware board evaluator (realized merges + peak/avg
  height + dead-line risk + merge-readiness) used by Greedy, the MCTS rollout value, and the
  ensembles, so every simulate-and-pick agent optimizes for *surviving to keep merging*, not
  just the biggest immediate merge. Weights are tuned so the majority of planning techniques
  clear 1000 on the standard seeds instead of stalemating in the mid-hundreds.
- **Keyboard controls in the 2D game** — ←/→ to aim and Space to drop for the human player
  (and Down-arrow drop on Imitation's "YOU" board), a full alternative to mouse + click.
- **Distinct RT Lab control schemes** — WASD-aim + Space-drop and mouse-aim + click-drop are
  both always live, never a mode toggle.
- **Richer TensorBoard logging** — per-run metadata text (hyperparameters, timestamp) and
  final/best summary scalars, from both the JVM export path and the Python scripts.
- **GPU inference for the MCTS + Policy Net ensemble** — its donor net (queried once per
  move) now routes through the persistent CUDA bridge, with an exact JVM fallback.
- **Community & project health** — Apache-2.0 `LICENSE`, `CONTRIBUTING`, `CODE_OF_CONDUCT`,
  `SECURITY` policy, issue/PR templates, Dependabot, and this changelog. CI now also runs on
  pull requests.

### Changed
- Parallelism labels are honest about GPU use: only techniques whose *live* loop genuinely
  runs a net on CUDA (PPO training, the MCTS+Net ensemble) show "GPU"; reactive nets whose
  per-frame queries would be slowed by IPC stay labelled as CPU threads.

## [0.17.0] — 2026
Compute-mode selector, TensorBoard/saving revamp, universal pondering think-loop, custom
inputs + watchdog, CMA-ES fix, WASD in RT Lab, physics feel.

## [0.16.0] — 2026
Live JVM DQN technique, automatic drop-adjustment pass, pondering think loop, CMA-ES
population knob, friction retune.

## [0.15.0] — 2026
TensorBoard advanced logging, real GPU-capable training loops, `env.py` fixes.

## [0.14.0] — 2026
Overflow-regression fix, GPU inference + CPU-only option, 5-stage hardware-calibrated
presets, PBT and Behavioral Cloning, ensemble donor selection.

## [0.13.0] — 2026
High-speed overflow-cheat fix, folder-based human-readable saves, GPU/autosave settings,
infocard tuning guide.

## [0.12.0] — 2026
Curated capability matrix (10 techniques + 5 ensembles), hardware presets, ensemble
customization, RT graphics settings.

## [0.11.0] — 2026
Full RT shell: cinematic ray-tracing pipeline, merge FX, ensemble parallelism, pixels
perception panel, global display settings.

## [0.10.0] — 2026
Physics/threading fixes, MCTS search-tree visualization, ensembles, parallel evolution
ghosts.

## [0.8.0] — 2026
RT Lab autoplay, camera orbit, visual/physics polish.

## [0.7.0]–[0.7.2] — 2026
RT Lab becomes a real ray-traced game: glass jar, PBR wood table, bokeh, 3D physics, movable
metal chute, HDRI lighting.

## [0.6.0]–[0.6.1] — 2026
Evolution OOM fix, configurable parallelism, 1024× speed, imitation dual-board, per-technique
config overhaul and live diagnostics.

## [0.5.0]–[0.5.3] — 2026
AI Playground, multi-view tiling, venv auto-install, infocards, physics tuning.

## [0.4.0]–[0.4.2] — 2026
AI Playground & specialised Control Center, MCTS strengthening, honest charts, score pops.

## [0.3.0] — 2026
Frontend rebuild: crisp fonts, live physics, settings, AI selection.

## [0.2.0] — 2026
Initial public foundation.

[Unreleased]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.17.2...HEAD
[0.17.2]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.17.1...v0.17.2
[0.17.1]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.17.0...v0.17.1
[0.17.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.16.0...v0.17.0
[0.16.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.8.0...v0.10.0
[0.8.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.7.2...v0.8.0
[0.7.2]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.7.0...v0.7.2
[0.7.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.5.3...v0.6.0
[0.5.3]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.4.2...v0.5.3
[0.4.2]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.4.0...v0.4.2
[0.4.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/ericcayers-ai/suikaAI-plus/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ericcayers-ai/suikaAI-plus/releases/tag/v0.2.0
