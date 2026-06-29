# Debug & Improve Roadmap Cycle

A repeatable, screenshot-driven loop for hardening and polishing the Suika AI+
GUI. Every release should pass through one full turn of this cycle. The cycle is
deliberately small and concrete so it can be run end-to-end in a single sitting.

> **Guiding rule:** never claim a screen is "done" without looking at a fresh
> screenshot of it. The GUI runs in a Gradle-spawned JVM, so visual truth comes
> from the [`CaptureHarness`](suika-game/src/main/java/dev/suika/game/CaptureHarness.java),
> not from reading code.

---

## Phase 0 — Setup

1. Confirm the JDK 21 toolchain: `JAVA_HOME = C:\Program Files\Java\jdk-21`.
2. Build the install image once so the harness can run:
   `./gradlew :suika-app:installDist`
3. Pick the cycle's target version (current `+ 0.0.1`). Record it here:
   - **This cycle targets: `v0.5.3`**

## Phase 1 — Observe (capture every screen)

Run the harness twice — portrait and landscape — into throwaway dirs and read
**every** PNG with the Read tool. Do not skip any.

```
# portrait
java -Dsuika.capture.dir=<dir-p>  -cp "<install>/lib/*" dev.suika.app.SuikaApplication
# landscape
java -Dsuika.capture.dir=<dir-l> -Dsuika.capture.land=true -cp "<install>/lib/*" dev.suika.app.SuikaApplication
```

**Screen inventory that must be captured each cycle:**

| # | Screen / state            | What to scrutinise                                   |
|---|---------------------------|------------------------------------------------------|
| 1 | Main menu                 | title, buttons, alignment, version string            |
| 2 | Settings (all sections)   | label/control overlap, glyphs, AI ENVIRONMENT rows   |
| 3 | AI Playground list+drawer | card text clipping, "i" icons, selected drawer       |
| 4 | Infocard modal            | background fully hidden, bars + text aligned         |
| 5 | Control center — single   | board placement, panel charts, column overlay        |
| 6 | Quick-settings modal      | glyphs render, CLOSE legible, no dead space          |
| 7 | Control center — 4-grid   | tiling fills space, labels, no stray pops            |
| 8 | Control center — 2-view   | agent/rival boards, panel                            |
| 9 | Ghost-overlay single view | translucent ghosts, score pops correct               |
| 10| Human play + Game Over    | HUD, next-fruit, game-over panel & buttons           |

## Phase 2 — Triage (log every defect)

For each screenshot, record findings in the **Findings log** below with a
severity:

- **B (blocker)** — crash, unreadable, or feature visibly broken
- **M (major)** — overlap/cutoff/misplacement a user would notice
- **m (minor)** — small spacing/alignment/contrast nit
- **P (polish)** — opportunity to improve, not a defect

## Phase 3 — Fix (highest severity first)

Work B → M → m. One concern per edit. Re-read the file's render math before
touching coordinates. Keep changes localized; prefer parameterized layout over
magic numbers.

## Phase 4 — Verify (re-capture, no regressions)

Re-run Phase 1 and confirm each logged defect is resolved **and** that nothing
adjacent regressed. A fix is not done until its screenshot proves it.

## Phase 5 — Improve (one or two targeted upgrades)

Only when the board is clean, add at most two small improvements that raise
quality (clarity, information density, affordance). Re-verify with screenshots.

## Phase 6 — Release

1. Bump `version` in `build.gradle.kts` and the title in `DesktopLauncher`.
2. `./gradlew :suika-app:jpackageExe`, then zip the `SuikaAI` app-image.
3. Commit (Co-Authored-By trailer), push `main`.
4. Recreate the GitHub release for the new tag with notes summarising the cycle.
5. Update the **Cycle history** section below.

---

## Findings log (this cycle → v0.5.3)

All 10 inventory screens captured in portrait **and** landscape and read.

| ID | Sev | Screen | Finding | Status |
|----|-----|--------|---------|--------|
| M1 | M | Main menu | Version label stale at `v0.5.1` | ✅ fixed — now reads `v0.5.3` |
| m1 | m | menu / launcher / app | Version hardcoded in 3 places (drift source) | ✅ fixed — centralised in `Theme.VERSION` |
| m2 | m | Main menu | Footer-right showed `WatchAgents` name ("MCTS Fast"), a remnant of the removed AI-Watch config with no way to set it | ✅ fixed — now shows `N AI techniques · <fps>` |
| P1 | P | menu / settings / playground in landscape | Portrait-locked `FitViewport` letterboxes into a centred strip | ⏷ accepted — readable; full landscape adaptation deferred to a later cycle |

Verified clean (no action): infocard modal, quick-settings modal, single/4-grid/
2-view/ghost control centers, human play, game over — all correct in both
orientations after the v0.5.2 cycle.

## Cycle history

- **v0.5.3** — first formal run of this cycle. Captured all 10 screens ×2
  orientations; fixed version drift (centralised to `Theme.VERSION`) and a stale
  menu footer; confirmed every other screen renders correctly. Deferred:
  landscape adaptation of the portrait-locked menu/settings/playground (P1).
