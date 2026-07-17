# Security Policy

## Supported versions

This is an actively developed project; security fixes land on `main` and ship in the next
tagged release. Only the latest release line is supported.

| Version | Supported |
|---|---|
| Latest release (`0.19.x`) | :white_check_mark: |
| Older releases | :x: |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, use one of these private channels:

1. **GitHub Security Advisories** — the preferred route. Open a draft advisory via
   **Security → Advisories → Report a vulnerability** on the repository. This keeps the
   report private until a fix is ready.
2. **Email** — `eric.c.ayers@gmail.com` with a subject prefixed `[SECURITY]`.

Please include:

- A description of the vulnerability and its impact.
- Steps to reproduce (a minimal proof of concept is ideal).
- The version / commit affected.

You can expect an acknowledgement within **72 hours** and a status update within **7 days**.
Once a fix is available we will credit you (unless you prefer to remain anonymous) in the
release notes.

## Scope & threat model

This is a desktop application, not a network service. The most relevant surfaces are:

- **The optional Python bridge** (`GpuInferenceBridge`, `PythonRunner`) launches a local
  Python subprocess from the managed virtual environment. It executes only code shipped in
  `python/suika/` and never downloads or `exec`s untrusted input.
- **Model save files** (`~/.suikai/saves/...`) are plain-text weights/config loaded on
  startup. Loading a maliciously crafted save from an untrusted source is treated as a
  trust decision by the user, but parsing is defensive (bounds-checked, never `eval`-based).
- **Third-party dependencies** (dyn4j, LibGDX, LWJGL, PyTorch, Stable-Baselines3) are pinned
  in `gradle.properties` / the Python setup and kept current via Dependabot.

Reports about any of the above — or anything else — are welcome.
