# manga-utils

A self-hostable manga engine that **reuses the Tachiyomi/Mihon extension ecosystem**
on the desktop JVM — no Android device, no emulator. Runs headless as a server, serves
a phone-first web UI over your own Tailscale network, downloads for offline reading, and
gets through the source-side defences (Cloudflare, WAF captchas) that normally block a
plain HTTP client.

Design rationale and the full feature menu live in [`DESIGN.md`](DESIGN.md).

## What it does

- **Runs Tachiyomi/Mihon extensions on plain JVM** — installs them from upstream
  prebuilt jars; you add the extension repositories yourself.
- **Phone-first web UI** — a React/Vite app served by the Ktor server. Browse, search,
  read, manage your library and downloads from your phone; meant to live behind
  **Tailscale** (private, no ports exposed to the internet).
- **Gets past source defences** — routes Cloudflare/WAF-gated requests through a real
  embedded Chromium (**JetBrains JCEF**) so the TLS/JA3/H2 fingerprint is genuinely
  Chrome's, with **FlareSolverr** as a lighter fallback. MangaFire (a hard case) works.
- **Solves captchas unattended** — an in-JVM ONNX shape-detector auto-solves MangaFire's
  "click the shapes in order" WAF captcha inside JCEF, with a manual WebView fallback and
  a Discord ping on success/failure. Downloads pause on a human-check and resume the
  moment it clears.
- **Downloads & library** — multi-source fallback, CBZ-friendly on-disk layout, reading
  history/positions, per-scanlator chapter versions, mass-download planning, scheduled
  library updates (each run reports how many series were checked vs. failed, on-screen and
  to Discord) with auto-download of new chapters, a **corrupt-image scan + one-click
  repair** (flags block-pages/junk saved as pages and re-fetches them), and Discord
  notifications.
- **Self-hosts cleanly** — a Docker/Proxmox deploy with the web UI reachable only over
  Tailscale, and source egress routed through Mullvad — either per-container
  (**gluetun**) or at the network layer (an OpenWRT gateway with a toggleable Mullvad exit
  node). See [`docs/!DOCKER-SERVER-PLAN-MAIN.md`](docs/!DOCKER-SERVER-PLAN-MAIN.md).
- **Moves between machines in two clicks** — a *Mass data migration* package carries the
  whole instance (library, history, extensions, settings, covers) except the downloads,
  which move as their own drive — plus a **downloads integrity check** (fast name+size, or
  deep SHA-256 of every file) that confirms the library survived the move intact.

## Architecture

Multi-module Gradle project (Kotlin / JVM 21):

| Module          | Responsibility |
|-----------------|----------------|
| `android-compat`| JVM stubs for the Android APIs extensions expect (vendored from Suwayomi) + the **JCEF** WebView/fetch bridge for Cloudflare. |
| `source-api`    | The `extensions-lib` interfaces extensions compile against + network interceptors (FlareSolverr, JCEF fetch, human-check state). |
| `core`          | The engine: extension loader, source manager, download manager (multi-source fallback), library, converter, status/logging. No UI. |
| `data`          | Persistence — Exposed ORM over SQLite. |
| `cli`           | The `mu` command-line front-end. |
| `server`        | Ktor HTTP server + the React/Vite web UI (`server/webui`) + the captcha auto-solver and deploy surface. |
| `gui` / `desktop` | Optional local desktop front-ends (Compose/Swing) for testing. |

All runtime state lives under one data dir (`MU_DATA_DIR`, else a platform default):
`library.db`, `downloads/`, `extensions/`, `bin/jcef` (the downloaded Chromium native),
`cache/jcef` (cookies incl. `cf_clearance`), `covers/`, `downloads-manifest.json` (the
integrity fingerprint), `logs/`.

### Concurrency

So a big download can't slow browsing/reading, blocking work runs on isolated
`limitedParallelism` slices of `Dispatchers.IO` (`core/.../async/Pools.kt`) — a stall
in one lane can't drain the others:

| Lane       | Limit | Handles |
|------------|------:|---------|
| `source`   | 16 | browse / search / details |
| `image`    | 16 | reader page images |
| `cover`    | 8  | cover thumbnails (grids) |
| `download` | 12 | download page-fetches (`parallelDownloads` 3 × `downloadConcurrency` 4) |

Separately: OkHttp stays at its default `maxRequestsPerHost = 5`; each extension sets its
own throttle via `.rateLimit(N)` (e.g. MangaFire 2/s); and the download queue rests a
whole source for 3 min after a transient failure.

## Building

Requires a JDK (21+). Uses the Gradle wrapper:

```sh
./gradlew build              # compile + test everything
./gradlew :cli:run --args="version"
./gradlew :cli:installDist   # produces cli/build/install/mu/bin/mu
./gradlew :server:run        # runs the server + builds and serves the web UI
```

On Windows, `start.bat web` runs the server with the web UI for local dev.

The build stamps the current git commit into `/api/version` (shown in the web UI footer),
so any running instance reports exactly which commit it was built from.

## Deploying

The intended production shape is a Debian VM on Proxmox running Docker: the whole server
(including JCEF) in one container, with the web UI published only onto your Tailscale
network. Source egress goes through Mullvad — either per-container via **gluetun**, or at
the network layer via an **OpenWRT gateway** with a toggleable Mullvad exit node (so the
whole LAN shares one tunnel and the container stays simpler). The full architecture,
`Dockerfile`, compose file, and a step-by-step setup runbook are in
[`docs/!DOCKER-SERVER-PLAN-MAIN.md`](docs/!DOCKER-SERVER-PLAN-MAIN.md).

## Licensing & ethics

Our own code is **MPL-2.0** (see `LICENSE`); vendored components keep their
Apache-2.0 / MPL-2.0 notices (see `NOTICE`). manga-utils ships **no** bundled
sources or content — you add extension repositories yourself.
