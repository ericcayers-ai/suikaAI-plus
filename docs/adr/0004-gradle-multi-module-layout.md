# ADR-0004: Gradle Multi-Module Layout

**Status:** Accepted  
**Date:** 2026-06-28

## Context

We need to enforce the "headless ≥ headful" architectural boundary described in
ROADMAP §II.3: `suika-core` must be incapable of depending on `suika-game`.

## Decision

Use a **Gradle multi-module project** (Kotlin DSL) with the following dependency graph:

```
suika-core       ← no upstream JVM deps (only dyn4j)
suika-assets     ← suika-core
suika-game       ← suika-core, suika-assets, LibGDX
suika-env        ← suika-core
suika-ai         ← suika-core, suika-env
suika-bridge     ← suika-core, suika-env
suika-dash       ← suika-core, suika-env, LibGDX, imgui-java
suika-app        ← all of the above
```

The boundary is enforced at **build time**: if a developer accidentally adds a LibGDX
import to `suika-core`, the compile step fails.

## Consequences

- Each module can be tested independently; `suika-core` tests need no display.
- New algorithms are added to `suika-ai` without touching `suika-core`.
- CI can cache per-module build artifacts.
