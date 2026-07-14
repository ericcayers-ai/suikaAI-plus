# 🍉 Suika AI Sandbox — Next Roadmap (post-0.10.0)

> **Status (v0.17.x):** Most of §1–§12 below shipped across **v0.11.0 → v0.17.2**
> (RT shell, cinematic pipeline, merge FX, global display, playground ensembles,
> landscape control center, pixels perception, debug-matrix CI job). Keep this file
> as the historical work plan; treat unchecked boxes as *likely done unless you
> verify otherwise against the live code / CHANGELOG*, and prefer
> [`ROADMAP.md`](ROADMAP.md) + [`CHANGELOG.md`](CHANGELOG.md) for what remains.
>
> A focused, execution-oriented roadmap for the arc from **v0.10.0 → v1.0**. Unlike
> the north-star [`ROADMAP.md`](ROADMAP.md) (which frames the whole vision), this
> document is a **work plan**: every item is something concrete to build, grouped so
> the result is one *consistent, cohesive* system rather than a pile of features.
>
> **Convention.** Each section states a **Goal**, a **Scope** checklist, **Acceptance
> criteria** (how we know it's done), and **Technical notes** (where in the code it
> lives / how to approach it). Sections are ordered roughly by dependency, then by
> value. Milestones at the end bundle sections into shippable releases.

**Baseline:** v0.10.0 — physics/threading fixes, MCTS search-tree viz, 10 ensemble
agents, scrollable diagnostics panel, RT Lab HUD overlay + caustics, parallel
evolution ghosts, and the selectable config-matrix debug harness.

---

## Table of Contents

- [Guiding principles for this arc](#guiding-principles-for-this-arc)
- [§1 — RT mode access & gating](#1--rt-mode-access--gating)
- [§2 — RT mode geometry & fruit](#2--rt-mode-geometry--fruit)
- [§3 — RT mode rendering & cinematic pipeline](#3--rt-mode-rendering--cinematic-pipeline)
- [§4 — RT mode merge FX](#4--rt-mode-merge-fx)
- [§5 — RT mode shell: loading, pause, settings, hotkey tray](#5--rt-mode-shell-loading-pause-settings-hotkey-tray)
- [§6 — Global display: resolution, fullscreen, UI scale](#6--global-display-resolution-fullscreen-ui-scale)
- [§7 — Settings & pause-menu bug fixes](#7--settings--pause-menu-bug-fixes)
- [§8 — AI Playground: ensembles, sorting, attributes](#8--ai-playground-ensembles-sorting-attributes)
- [§9 — Control Center: landscape-first revamp & live tree](#9--control-center-landscape-first-revamp--live-tree)
- [§10 — Ensemble & training performance](#10--ensemble--training-performance)
- [§11 — Pixels-mode live perception view](#11--pixels-mode-live-perception-view)
- [§12 — Debug matrix follow-through](#12--debug-matrix-follow-through)
- [Milestones](#milestones)
- [Cross-cutting acceptance gates](#cross-cutting-acceptance-gates)

---

## Guiding principles for this arc

1. **One design language, two renderers.** The RT (Vulkan) window and the LibGDX 2D
   UI should feel like the *same app*: shared palette, the bundled DroidSans font,
   the "dark card + colored top-strip" motif, the same iconography. The HUD revamp in
   0.10.0 started this; every new RT surface (loading, pause, settings) continues it.
2. **Landscape is the professional default; portrait is the phone fallback.** The
   control center's information density wants width. Default to landscape when an AI
   launches, but never let a layout break in portrait.
3. **Every knob is honest.** A setting that's shown must do something real and update
   live. No fake sliders. (This already governs the AI hyper-params; extend it to the
   new graphics settings.)
4. **Text is sacred.** At every resolution, UI scale, and window size, every label
   must remain fully on-screen and legible. Truncation is a bug, not a layout.
5. **Only add an effect if it can genuinely work** at interactive frame rates on the
   target GPU tier. Prefer a convincing approximation that ships over a physically
   exact effect that stutters.

---

## §1 — RT mode access & gating

**Goal.** RT mode is a first-class way to play, not a hidden experiment.

**Scope**
- [ ] Remove the requirement to enable *Experimental mode* before the RT LAB / AI-PLAYS
      buttons on the main menu become active. The buttons are always live.
- [ ] Keep a graceful capability check: if the GPU/driver lacks hardware ray tracing,
      the button still clicks but surfaces a friendly "not supported on this GPU"
      message instead of silently failing.
- [ ] Fold the two *genuinely experimental* physics toggles (instant-death, bouncy
      fruit) into their own clearly-labelled group — see [§7](#7--settings--pause-menu-bug-fixes)
      — rather than gating the whole RT feature behind them.
- [ ] The 2D-vs-3D physics choice for RT stays a normal setting, not an experimental gate.

**Acceptance criteria**
- Fresh install → main menu → RT LAB launches without touching Settings first.
- On a non-RT GPU, clicking RT LAB shows a clear message; the 2D game is never
  destabilised (the launcher already isolates its Vulkan context — keep that).

**Technical notes**
- Gate today lives in [`MainMenuScreen`](suika-game/src/main/java/dev/suika/game/MainMenuScreen.java)
  (`game.settings.experimentalMode` checks around the RT LAB / AI-plays buttons) and
  in [`GameSettings`](suika-game/src/main/java/dev/suika/game/GameSettings.java)
  (`experimentalMode`, `rt3dPhysics`). Replace the `experimentalMode` gate with a
  cheap one-time RT-capability probe cached on `GameSettings`.
- `RtLabLauncher.run(...)` already catches `Throwable` and reports to console — route
  that into a UI-visible status the menu can read.

---

## §2 — RT mode geometry & fruit

**Goal.** The reference-photo look: a tall steel chute running off the top of the
frame that *grips* each fruit before releasing it, with slightly chunkier fruit.

**Scope**
- [ ] **Taller chute** — extend `CHUTE_HEIGHT` so the tube's top exits the frame at
      the default camera. It should read as "coming from above," not a floating stub.
- [ ] **Narrower chute** — reduce `CHUTE_RADIUS` a touch so the tube visually hugs the
      pending fruit. The held fruit should sit with its shoulders inside the opening.
- [ ] **Grip-and-release motion** — the pending fruit currently hangs at
      `CHUTE_BOTTOM_Y - radius*0.75`. Tune so at rest it's clearly held; on drop, add a
      short release animation (the chute "lets go" — a small settle/recoil) rather than
      an instant teleport to falling.
- [ ] **Slightly bigger fruit** — bump every `FruitTier.radius` by a small factor
      (~5–8% on top of the current values). Re-verify containment in all three modes
      (2D, RT-2D, RT-3D) and keep the duplicated radius tables in sync (`FruitTier`,
      `FruitLadder`, `suika-assets/fruits.json`, `python/suika/env.py`,
      `StateObservationEncoder`'s normaliser).

**Acceptance criteria**
- At the default (un-orbited, un-zoomed) camera the chute's top is off-frame.
- A held fruit visibly sits inside the chute mouth; releasing it looks like a release,
  not a pop-in.
- No fruit escapes the jar in any mode after the size bump (the debug matrix's
  out-of-bounds check passes across the speed sweep).

**Technical notes**
- Constants in [`RtScene`](suika-game/src/main/java/dev/suika/game/rtlab/RtScene.java):
  `CHUTE_RADIUS`, `CHUTE_HEIGHT`, `CHUTE_BOTTOM_Y`. The held-fruit instance is added in
  [`RtLabLauncher`](suika-game/src/main/java/dev/suika/game/rtlab/RtLabLauncher.java)'s
  render loop (`CHUTE_BOTTOM_Y - cur.radius * 0.75f`).
- Radius bump touches `FruitTier`, `FruitLadder`, `fruits.json`, `env.py`,
  `StateObservationEncoder`; keep `RtTraceTest`'s pile reading radii from `FruitTier`.
- Release animation: track a per-session "just dropped" timestamp and offset the chute
  Y / add a brief scale pulse in the launcher loop.

---

## §3 — RT mode rendering & cinematic pipeline

**Goal.** A grand, filmic, *default-on* look — no "denoiser optional" caveats,
believable depth-of-field, and a proper post chain.

**Scope**
- [ ] **Bokeh you can read** — increase the background blur so HDRI detail behind the
      jar is no longer legible; it should be a soft wash of studio color, not a
      recognisable environment map. (Raise aperture and/or push the wall further; the
      wall already moved to `WALL_Z = -58` in 0.10.0 — tune aperture/focus with it.)
- [ ] **Lower exposure** a notch so highlights stop clipping (`ENV_EXPOSURE` and the
      key-light intensity in `raygen.rgen`).
- [ ] **Fix the fruit texture speckle** — the "random spots" are procedural-noise/rind
      artifacts on some tiers. Retune or replace the fallback rind bump so untextured
      tiers read as clean fruit, not noisy blobs. (See `closesthit.rchit` fruit
      procedural path + the GRAPE-is-black fix already in place.)
- [ ] **More noticeable deadline line** — make the etched game-over line on the glass
      clearer (stronger emissive band / subtle animated shimmer) without it looking
      like a UI element.
- [ ] **Higher sample counts** for shadows and DoF — raise the jittered-sample counts
      so the pre-denoise image is cleaner, budget permitting.
- [ ] **Denoiser as default** — improve the bilateral compute denoiser (edge-aware
      weights, maybe a second pass or temporal reuse) until it's good enough to be
      always-on. Remove the "D toggles denoise" as a *necessity* (keep it only as a
      hidden debug hotkey, or drop it).
- [ ] **Post-processing chain** (add only what runs well): ACES tonemap (already in) →
      **color grade** with a gentle contrast/saturation lift → **bloom** (bright-pass
      + separable blur + add) → **very slight motion blur** (velocity-based or a cheap
      accumulation nudge) → **anti-aliasing** (the temporal accumulation already
      provides TAA-like smoothing; add a light FXAA/edge pass if aliasing remains).
      Each stage individually toggleable/quality-tunable via the RT settings ([§5](#5--rt-mode-shell-loading-pause-settings-hotkey-tray)).

**Acceptance criteria**
- Headless `RtTraceTest` renders (2D and 3D-scatter) show: unreadable-but-pretty
  background, non-clipping highlights, clean fruit skin, a clearly visible deadline
  line, and no objectionable noise with the denoiser on.
- Frame time stays interactive on the RTX-4060 reference GPU with the default preset.

**Technical notes**
- Shaders: [`raygen.rgen`](suika-game/src/main/resources/shaders/rtlab/raygen.rgen)
  (lighting, DoF, tonemap, deadline band, caustics),
  [`closesthit.rchit`](suika-game/src/main/resources/shaders/rtlab/closesthit.rchit)
  (materials, procedural rind), `denoise.comp` (bilateral filter). Compiled at runtime
  via `RtShaderCompiler` (shaderc), so shader edits need no build step for the SPIR-V.
- Bloom / grade / motion-blur are new compute passes on the `present` image (mirror
  `RtDenoiser` / `RtHudCompositor` — descriptor set + compute pipeline + dispatch in
  the `renderFrame` command buffer). Order: RT → denoise → grade → bloom → motion-blur
  → HUD composite → blit.
- Validate every render change through `RtTraceTest <outDir> [3d]` (renders real scene
  to PNG on GPU, no window) before wiring into the live loop.

---

## §4 — RT mode merge FX

**Goal.** Merges feel *good* — a satisfying, cinematic pop with real particles.

**Scope**
- [ ] **Merge event → FX hook.** Surface merge events out of the RT sessions
      (`Rt2DSession` wraps `GameCore` which already emits `MergeEvent`s; `Jar3DPhysics`
      applies merges internally — add an event list it can hand back per tick).
- [ ] **Particle system** in world space: on merge, spawn a burst at the merge point,
      tinted to the resulting tier. Support **accumulation** (lingering sparks/embers)
      and a sharp **burst**, with **culling** (cap live particles; distance/￼age cull;
      skip off-frame). Render as additive billboards composited after the RT pass, or
      as emissive point-instances in the scene.
- [ ] **Merge animation** — the two parents visibly coalesce into the child (quick
      scale-down of parents + scale-up/overshoot of the child) instead of an instant
      swap. A flash/shockwave ring is a nice touch.
- [ ] Respect quality settings: particle budget and effect richness scale with the
      graphics preset ([§5](#5--rt-mode-shell-loading-pause-settings-hotkey-tray)).

**Acceptance criteria**
- A merge produces a visible, tier-colored burst + brief coalesce animation; sustained
  merging never unbounded-grows particle count (culling holds the cap).
- Disabling particles in RT settings cleanly removes them with no cost.

**Technical notes**
- 2D merge events already exist (`GameCore.tick()` returns `List<MergeEvent>` with
  spawn position + tier); `Rt2DSession` can forward them. For 3D, add a returned
  event list to `Jar3DPhysics.applyMerges()`.
- The 2D game already has a CPU particle system (`game.particles`, `ScorePops`) — the
  RT window is a separate Vulkan context so it needs its own lightweight GPU/CPU
  particle path. Simplest first cut: CPU-simulated particles uploaded as extra emissive
  sphere/quad instances into `RtScene` each frame, with a hard cap + age cull.

---

## §5 — RT mode shell: loading, pause, settings, hotkey tray

**Goal.** RT mode is a self-contained, polished app: it opens with a loading screen,
pauses to a live settings menu, and keeps hotkeys tucked away until asked for.

**Scope**
- [ ] **Loading screen** — while the Vulkan device, pipeline, textures, HDRI, meshes,
      and BLASes build (this takes a beat), show a branded loading screen in-window
      (progress or a tasteful animated splash) instead of a blank/black window.
- [ ] **Pause menu** — a key (Esc, or a dedicated one) opens an in-RT pause overlay:
      Resume, Restart, Graphics Settings, Back to (2D) app. Fully functional; pauses
      the sim while open.
- [ ] **Live RT graphics settings** (reachable from the pause menu, all update live):
  - FPS cap / vsync
  - Sample counts (shadow, DoF, accumulation blend)
  - Resolution / internal render scale
  - Fullscreen ↔ windowed
  - Per-feature toggles + quality: denoiser, bloom, motion blur, AA, particles,
    caustics, DoF
  - **Graphics presets** (Low / Medium / High / Ultra) that set all of the above, plus
    Custom once the user tweaks anything.
- [ ] **Hotkey tray** — the bottom of the RT HUD shows only a small upward arrow; click
      it to reveal the control/hotkey strip, click again to hide. Default hidden so the
      scene is unobstructed.
- [ ] **Font & polish pass** — continue the HUD's design-language upgrade across the
      new loading/pause/settings surfaces (bundled DroidSans, tracked captions, gradient
      cards, colored top-strips).

**Acceptance criteria**
- Cold RT launch shows a loading screen, never a blank window.
- Pause → change any graphics setting → see it apply immediately on resume (or live).
- Hotkey strip is hidden by default; the arrow toggles it; nothing else obstructs the
  scene.

**Technical notes**
- All new surfaces render through the existing `RtHud` Java2D→Vulkan overlay path (draw
  into the `BufferedImage`, upload, composite). Extend `RtHud` with pause/settings/tray
  states, or add sibling overlay layers composited in order.
- The RT loop in [`RtLabLauncher`](suika-game/src/main/java/dev/suika/game/rtlab/RtLabLauncher.java)
  needs a `paused` flag (skip `session.step` while paused) and mouse hit-testing against
  HUD rectangles (the window is raw GLFW — add click handling that maps cursor → HUD
  buttons when a menu is open).
- Live resolution/fullscreen change means swapchain + output-image recreation
  (`RtSwapchain`, `RtOutputImage`, HUD image) — the launcher already owns all of these
  in one `try`-with-resources scope; factor the recreatable set into a rebuildable unit.
- Loading screen: draw via `RtHud` after the window exists but before/while the heavy
  resources build. Since the build is currently synchronous on the rtlab thread, either
  present a couple of loading frames between construction steps, or move construction
  onto a worker and pump loading frames until it's ready.

---

## §6 — Global display: resolution, fullscreen, UI scale

**Goal.** The 2D app (menu, human play, AI playground, control center) supports real
resolution, fullscreen, and UI-scale settings — with text that never breaks.

**Scope**
- [ ] **Resolution** — selectable window resolutions; the virtual canvas adapts without
      breaking layouts (the app already uses `FitViewport` on a fixed virtual size —
      keep the virtual coordinate space, change the backing window/back-buffer).
- [ ] **Fullscreen on/off** — real borderless/exclusive fullscreen toggle.
- [ ] **UI scale** — a user UI-size setting. Critically: **text must stay fully visible
      and legible at every scale**. This means fonts re-rasterised at the scaled size
      (FreeType regen, not just sprite-stretching) and layouts that reflow/clamp so
      nothing clips.
- [ ] Settings persist across launches (the app currently keeps settings in-memory only
      per `GameSettings` — add lightweight persistence, e.g. libGDX `Preferences`).

**Acceptance criteria**
- Change resolution / fullscreen / UI scale → apply live, no restart, no clipped text,
  no broken layout, in every 2D screen and in both orientations.
- Reopen the app → settings preserved.

**Technical notes**
- Display application via `Gdx.graphics.setWindowedMode / setFullscreenMode`; wire into
  `GameSettings.applyDisplay()` (already applies FPS/vsync).
- Fonts: [`SuikaGame`](suika-game/src/main/java/dev/suika/game/SuikaGame.java) builds
  fonts via FreeType from the bundled TTFs — regenerate at the chosen UI scale on change
  and hand the new `BitmapFont`s to every screen. Watch for disposal of old fonts.
- Layout: the virtual-canvas approach means most positions are resolution-independent
  already; UI scale is the harder axis — audit each screen's fixed pixel positions
  against the largest supported scale and add clamping/reflow where text would overrun.
- Persistence: `Preferences` (`Gdx.app.getPreferences`) keyed per setting; load in
  `GameSettings` ctor, save on change.

---

## §7 — Settings & pause-menu bug fixes

**Goal.** Kill the known formatting/legibility bugs.

**Scope**
- [ ] **Max GPU utilization slider** (normal Settings, *not* RT settings) — fix the
      value/label formatting bug (overlap/misalignment near the "100%" readout in the
      AI-ENVIRONMENT section).
- [ ] **"AI plays" formatting bug** — fix the mis-formatted text on the main-menu
      AI-plays button / picker.
- [ ] **Human-play pause menu** — the buttons' glossy white highlight is so bright the
      label text is unreadable. Tone down the sheen (or darken/raise-contrast the label)
      so text is always legible.
- [ ] **Experimental physics group** — surface the *instant-death* and *bouncy-fruit*
      toggles as a clear, labelled group in Settings (they're the genuinely
      experimental gameplay knobs now that RT itself isn't gated). Keep the RT 2D/3D
      physics selector nearby.

**Acceptance criteria**
- The GPU-util readout, AI-plays label, and pause-menu button labels are all fully
  legible and correctly aligned in the showcase capture pass.

**Technical notes**
- GPU-util slider + AI-ENVIRONMENT layout in
  [`SettingsScreen`](suika-game/src/main/java/dev/suika/game/SettingsScreen.java).
- AI-plays button text in [`MainMenuScreen`](suika-game/src/main/java/dev/suika/game/MainMenuScreen.java).
- The over-bright button sheen is the shared `Ui.button(...)` glossy highlight —
  fix in [`Ui`](suika-game/src/main/java/dev/suika/game/Ui.java) (reduce highlight
  alpha or ensure label draws after/over it with enough contrast), which also improves
  every other button.
- Instant-death (`immediateDeadline`) and bounce (`bounceEnabled`) already exist on
  `GameSettings`; this is a Settings-screen presentation task.

---

## §8 — AI Playground: ensembles, sorting, attributes

**Goal.** The technique list is organised, honest, and easy to navigate; ensembles are
a distinct, ranked group.

**Scope**
- [ ] **Ensemble dropdown at the top** — the 10 ensemble techniques get their own
      collapsible group revealed at the top of the list (expand/collapse), separate from
      the base techniques.
- [ ] **Sort ensembles best→worst** — order them by expected strength (a curated
      ranking, and/or driven by measured average score once telemetry exists).
- [ ] **Fix list scrolling direction** — the playground/diagnostics scroll currently
      goes the wrong way relative to wheel direction; invert to match platform
      convention.
- [ ] **Fix remaining text formatting** — audit the technique cards and the
      landscape diagnostics text for any residual misalignment/overrun (the panel
      scrolls now, but verify wrapping/indent/label-column alignment across every
      technique, especially the verbose planners and ensembles).
- [ ] **Richer, more accurate attributes** — expand each technique's/ensemble's metadata
      so the card and infocard represent it precisely (e.g. add fields like *learns?*,
      *needs Python?*, *uses search?*, *stochastic?*, *parallelisable?*, expected
      strength tier, and a one-line "how it decides"). Consider adding a couple of new
      base techniques if a gap is obvious.

**Acceptance criteria**
- Ensembles are a labelled, collapsible, ranked group at the top; base techniques below.
- Wheel-scroll direction matches the OS everywhere it's used.
- No clipped/overrun/misaligned text on any card or in any technique's diagnostics.

**Technical notes**
- List rendering in
  [`AiPlaygroundScreen`](suika-game/src/main/java/dev/suika/game/AiPlaygroundScreen.java)
  (flat `AiTechnique.values()` loop today). Add grouping/collapse state and a sort
  comparator; ensembles are identified by `category.equals("Ensemble")`.
- Scroll direction: check the `scrolled(...)` handlers in `AiPlaygroundScreen` and
  [`ControlCenterScreen`](suika-game/src/main/java/dev/suika/game/ControlCenterScreen.java)
  — the control-center panel scroll (`statsScroll += amountY*40`) and the playground
  list scroll should both follow the same, correct sign.
- Attributes live on the [`AiTechnique`](suika-game/src/main/java/dev/suika/game/AiTechnique.java)
  enum (`explainerLines()`, `liveHint()`, `category`, `kind`, flags). Add fields there;
  it's the single source of truth. Adding a new base technique also means an
  `Agents.build(...)` case and (if it needs a runner family) a switch update.

---

## §9 — Control Center: landscape-first revamp & live tree

**Goal.** Each family's control panel is a genuinely well-designed landscape dashboard,
and the search-tree viz updates live.

**Scope**
- [ ] **Landscape by default** — when an AI is started, open the control center in
      landscape orientation (portrait remains a supported fallback for narrow windows).
- [ ] **Per-family panel revamp** — redesign the diagnostics layout for each family
      (Planning, Evolution, Imitation, Python, Ensemble) so each reads as a purpose-built
      dashboard: clear hierarchy, grouped stats, charts + the tree/perception viz placed
      intentionally, not a single scrolling text column.
- [ ] **Live search tree** — the MCTS/AlphaZero/ensemble search-tree diagram should
      update every move (or every few frames), not only on panel construction. It
      already snapshots per search in `MctsAgent.lastTree()`; drive the panel to
      re-read and redraw it live.
- [ ] Keep the drop-tendency stat and per-family charts; integrate them into the new
      layouts.

**Acceptance criteria**
- Launching any technique opens landscape; the panel is visually organised per family;
  the search tree animates as the agent thinks.

**Technical notes**
- Orientation is chosen in `ControlCenterScreen.applyOrientation(...)` from window
  aspect — add a "prefer landscape on launch" path (and/or open the RT/AI window at a
  landscape size).
- The tree already has a live source (`mctsTreeSource()` → `MctsAgent.lastTree()`), and
  `drawMctsTree(...)` renders it each frame — verify it's re-reading the latest snapshot
  (it should already; confirm no stale caching) and that the snapshot updates every
  move. The `TreeNodeView` snapshot is taken in `MctsAgent.selectAction(GameCore,...)`.
- Panel layout constants and the `stats()/extendedStats()` model live in the runners
  (`PlanningRunner`, `EvolutionRunner`, `ImitationRunner`, `PythonRunner`) and
  `ControlCenterScreen.drawPanel/drawPanelText/chartSlots`.

---

## §10 — Ensemble & training performance

**Goal.** Ensembles and trainers use the machine fully — parallelism and, where it
genuinely applies, the GPU.

**Scope**
- [ ] **Parallelise ensemble decisions** — the composite agents (voting committee,
      tiebreak, bandit, etc.) run several inner agents per move; run those inner
      `selectAction` calls concurrently where they're independent, and reuse the
      root-parallel MCTS fan-out that `AgentRunner` already has for the MCTS-based ones.
- [ ] **Cap & pool threads sanely** — a bounded pool per control center, not unbounded
      thread creation per move (the current per-move `new Thread` in a few spots should
      become a small reusable pool).
- [ ] **GPU where real** — be honest: JVM physics/search has no CUDA path, so "GPU" only
      applies to the Python-trained techniques. Where a technique *can* use the GPU
      (PPO training via the venv), make sure the wiring and the reported status are
      correct; don't claim GPU for JVM-native work.
- [ ] Profile a few worst cases (16-elite evolution at high speed, high-rollout MCTS
      ensembles) and remove any remaining render-thread stalls.

**Acceptance criteria**
- Ensemble move latency drops measurably vs. serial on a multi-core machine; no
  unbounded thread growth; high-elite evolution stays smooth (already parallelised in
  0.10.0 — keep it, extend the pattern).

**Technical notes**
- Inner-agent fan-out mirrors `AgentRunner.parallelMctsSelect(...)` (root-parallel
  trees). `EnsembleAgents` composites can submit their independent sub-decisions to a
  shared pool and join.
- Evolution ghost stepping is already pooled (`EvolutionRunner.ghostPool`); the eval
  pools in `GeneticTrainer`/`CmaEsTrainer` are already bounded — audit for the ideal
  worker count and any false serialization.

---

## §11 — Pixels-mode live perception view

**Goal.** Techniques whose data mode is *pixels* (MuZero, Dreamer) show what they
"see," making the pixels-vs-state distinction tangible.

**Scope**
- [ ] For pixels-mode techniques in the normal (2D) control center, add a small live
      **perception panel**: a downsampled/false-color render of the board frame the
      agent would consume (the pixel observation), updating live.
- [ ] Make it clearly *the model's-eye view* (low-res, maybe grayscale/edge/heatmap
      styling) so it visibly differs from the crisp gameplay board.
- [ ] Slot it into the per-family landscape layout from [§9](#9--control-center-landscape-first-revamp--live-tree)
      (it's the pixels-family analogue of the search-tree viz).

**Acceptance criteria**
- Launching MuZero/Dreamer shows a live, low-res "what the model sees" panel distinct
  from the gameplay board.

**Technical notes**
- `AiTechnique.dataMode == "pixels"` marks these. The observation encoder today is
  state-based (`StateObservationEncoder`); a pixels view can be a cheap CPU render of
  the board to a small pixmap (render the fruits into an N×N buffer), shown as a texture
  in the diagnostics panel. It doesn't need to be the real training tensor — it needs to
  *communicate* the pixels modality.

---

## §12 — Debug matrix follow-through

**Goal.** Use and grow the config-matrix harness shipped in this release.

**Scope**
- [ ] Add a **fullscreen/resolution/UI-scale axis** to the matrix once [§6](#6--global-display-resolution-fullscreen-ui-scale)
      lands, so layout/text-legibility is regression-tested across display settings.
- [ ] Add an **RT-mode capture path** (headless `RtTraceTest` already renders scenes;
      extend the harness or a sibling to sweep RT graphics presets and merge-FX states).
- [ ] Wire the matrix into CI as an opt-in job (bounded axes) that fails on any reported
      anomaly.

**Acceptance criteria**
- `-Dsuika.capture.mode=matrix` with display axes produces per-setting screenshots + a
  `report.txt`; any out-of-bounds/NaN/overflow flags the run.

**Technical notes**
- Harness is
  [`CaptureHarness`](suika-game/src/main/java/dev/suika/game/CaptureHarness.java);
  axes parsed from `-Dsuika.capture.*`. Add axes by extending `buildMatrix()` and the
  `Job` record. RT sweeps go through `RtTraceTest` (GPU, windowless).

---

## Milestones

- **v0.11 — "RT mode grows up."** §1 (access), §2 (geometry/fruit), §5 (loading + pause
  + RT settings + hotkey tray). RT becomes a self-contained, ungated, polished mode.
- **v0.12 — "Cinematic RT."** §3 (render pipeline + default denoiser + post chain), §4
  (merge FX). The showcase look.
- **v0.13 — "Playground & panels."** §8 (ensemble dropdown/sort/scroll/attributes), §9
  (landscape-first revamp + live tree), §10 (ensemble/training perf), §11 (pixels view).
- **v0.14 — "Display & polish."** §6 (resolution/fullscreen/UI-scale + persistence), §7
  (settings/pause bug fixes), §12 (matrix follow-through in CI).
- **v1.0 — "Cohesive."** Everything above integrated, one design language across both
  renderers, the debug matrix green across the full axis sweep.

## Cross-cutting acceptance gates

Applied to *every* section before it's called done:

1. **Build & tests green** — `./gradlew build` (JDK 21) with all module tests passing.
2. **No clipped/illegible text** — verified in the showcase capture at the relevant
   resolutions/UI scales/orientations.
3. **No physics escapes** — the debug matrix's out-of-bounds/NaN check passes across the
   speed and column sweep for every affected technique.
4. **Honest knobs** — any new setting does something real and (in RT/graphics contexts)
   updates live.
5. **One design language** — new surfaces use the bundled font, shared palette, and the
   dark-card/colored-top-strip motif; RT and 2D read as the same app.
