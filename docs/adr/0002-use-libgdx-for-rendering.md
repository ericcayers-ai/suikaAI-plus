# ADR-0002: Use LibGDX for Rendering and Window Management

**Status:** Accepted  
**Date:** 2026-06-28

## Context

We need a cross-platform 2D rendering layer for the game UI (menus, HUD, sprites,
fullscreen/resizable window, viewport scaling). Requirements:

- Fixed virtual resolution scaled to any window size (resolution independence)
- Texture atlas / sprite batching
- Input handling (mouse drop position)
- Cross-platform (Linux, macOS, Windows)
- Compatible with OpenGL context for ImGui (dashboard)

## Decision

Use **LibGDX** with the **LWJGL3** backend.

Key reasons:
- `FitViewport` provides resolution independence out of the box, keeping physics in
  a stable coordinate space regardless of window size.
- Asset manager + texture atlas tooling matches our sprite pipeline.
- Shares the GL context with **imgui-java** for the dashboard without a second window.
- Widely used in Java game development; maintained and well-documented.

## Consequences

- `suika-game` and `suika-dash` depend on LibGDX; `suika-core` does not.
- The headless/headful boundary is enforced at the Gradle module level.
- Tests for `suika-core` run without a display (headless JVM).
