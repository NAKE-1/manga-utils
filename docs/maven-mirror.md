# Build Maven mirror (worker nodes behind a VPN)

## The problem

Worker nodes egress through a **Mullvad exit** (via Tailscale) so sources like MangaFire see a non-home IP.
But a worker rebuild (`git pull` + `docker build`) fetches Gradle dependencies, and two of them —
`com.android.tools.build:apksig` and `androidx.annotation` — live **only** on Google's Maven CDN
(`dl.google.com`), not on Maven Central. Google refuses the Mullvad datacenter IP (the same block MangaFire
applies), so the build dies with:

```
Could not find com.android.tools.build:apksig:9.2.1
Could not find androidx.annotation:annotation:1.10.0
```

Main never hits this because main builds on its clean home IP.

## The fix: main is the build's Maven mirror

Main has clean egress, so it fetches the deps once and caches them; workers fetch from main **over the
tailnet**. Tailnet (peer-to-peer WireGuard) traffic does **not** go through the exit node, so a worker
reaches main regardless of which Mullvad exit it's on. Java jars are architecture-independent, so one cache
serves both the ARM worker (Orange Pi) and the x86 workers (M720q) — unlike shipping a prebuilt image,
which the ARM/x86 split rules out.

```
worker build ──tailnet (bypasses exit)──> main:8099 nginx cache ──clean egress──> dl.google.com / Central / …
```

## Pieces

- `deploy/maven-mirror/` — an nginx caching reverse proxy, run **on main**. One path per Gradle upstream
  (`/central`, `/google`, `/gradle-plugins`, `/jitpack`, `/jetbrains-ij`, `/compose-dev`, `/jogamp`),
  cached 120 days (versioned artifacts are immutable).
- `settings.gradle.kts` — when `MU_MAVEN_MIRROR` is set, every plugin + dependency repo resolves through
  that base URL instead of the public one. Unset (main, dev machines) = normal direct resolution, unchanged.
- `deploy/Dockerfile` — takes `--build-arg MU_MAVEN_MIRROR=` and exposes it to the Gradle build.
- `deploy/worker/docker-compose.yml` — passes `MU_MAVEN_MIRROR` from the worker's env into the build.

## Setup

### 1. Start the mirror on main (once)

```bash
docker compose -f deploy/maven-mirror/docker-compose.yml up -d
curl -s http://localhost:8099/healthz        # -> ok
```

It listens on `:8099`. Find main's tailnet IP (`tailscale ip -4`) — e.g. `100.67.109.13`.

### 2. Point each worker's build at it

On every worker box, set the env before building. Easiest: a `.env` next to the worker compose, or export
it in the shell / the fleet script:

```bash
export MU_MAVEN_MIRROR=http://100.67.109.13:8099
```

- **node-1 (Orange Pi):** `cd ~/manga-src/deploy/worker && MU_MAVEN_MIRROR=http://100.67.109.13:8099 docker compose up -d --build manga-worker flaresolverr solver autoheal`
- **node-3 (Proxmox, per CT):** set `MU_MAVEN_MIRROR` in each CT's environment (or in `/root/manga-src/deploy/worker/.env`) before `/root/mu-lxc-fleet.sh update`.

Leave `MU_MAVEN_MIRROR` **unset** on main and dev machines — they build direct, as before.

## Rollout order (important)

The mirror support ships *in* the repo, so the first worker build that USES it must be built while the
mirror is already running:

1. Deploy the mirror on main (step 1) **first**.
2. Rebuild main normally (no `MU_MAVEN_MIRROR`) so it carries this change.
3. Then rebuild workers with `MU_MAVEN_MIRROR` set.

Until then, the off-VPN build path still works: flip a worker's exit off (`/api/workers/exit-node?name=<w>&node=`),
build, flip it back.

## Verifying

- `curl -I http://<main>:8099/google/com/android/tools/build/apksig/9.2.1/apksig-9.2.1.pom` → `200`, and the
  response carries `X-Cache: MISS` the first time, `HIT` after.
- A worker build logs `Downloading http://<main>:8099/...` instead of `https://dl.google.com/...`.
- `docker exec mu-maven-mirror du -sh /cache` grows as builds populate it.

## Notes / limits

- Covers every repo the build uses, so it's future-proof against another Google-Maven-only dep appearing.
- `jitpack` builds on demand; the first request for a new jitpack artifact may be slow (that's jitpack, not
  the mirror). Cached afterward.
- The cache is disposable — delete the `mu-maven-cache` volume to reclaim space; it refills on the next build.
- This is a *build-time* concern only. The running app never needs these repos.
