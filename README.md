# manga-utils

A headless, self-hostable manga downloader engine that **reuses the Tachiyomi/Mihon
extension ecosystem** on the desktop JVM. Driven by a CLI today; a web layer (read
on your phone over Tailscale) comes later.

Design rationale and the full feature menu live in [`DESIGN.md`](DESIGN.md).

## Status

Early development. **Phase 0** (build skeleton) is the current focus — see the
roadmap below.

## Architecture

Multi-module Gradle project (Kotlin / JVM 21):

| Module          | Responsibility |
|-----------------|----------------|
| `android-compat`| JVM stubs for the Android APIs that extensions expect (vendored from Suwayomi). |
| `source-api`    | The `extensions-lib` interfaces extensions are compiled against. |
| `core`          | The engine: extension loader, source manager, download manager (with multi-source fallback), converter, status/logging. No UI. |
| `data`          | Persistence — Exposed ORM over SQLite. |
| `cli`           | The `mu` command-line front-end. |

All runtime state lives under one data dir (default `./data`): `library.db`,
`downloads/`, `extensions/`, `logs/`.

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

Separately: OkHttp stays at its default `maxRequestsPerHost = 5` (a per-host dispatcher
tune was tried and reverted); each extension sets its own throttle via `.rateLimit(N)`
(e.g. MangaFire 2/s); and the download queue rests a whole source for 3 min after a
transient failure.

## Building

Requires a JDK (21+). Uses the Gradle wrapper:

```sh
./gradlew build          # compile + test everything
./gradlew :cli:run --args="version"
./gradlew :cli:installDist   # produces cli/build/install/mu/bin/mu
```

## Roadmap

- **Phase 0** — build skeleton, `mu --help`. ← *current*
- **Phase 1** — vendor `android-compat` + `source-api`.
- **Phase 2** — extension install/load pipeline.
- **Phase 3** — browse (search / manga info / chapters).
- **Phase 4** — download + CBZ export + multi-source fallback + status.
- **Phase 5** — library + status/logs polish.

## Licensing & ethics

Our own code is **MPL-2.0** (see `LICENSE`); vendored components keep their
Apache-2.0 / MPL-2.0 notices (see `NOTICE`). manga-utils ships **no** bundled
sources or content — you add extension repositories yourself.
