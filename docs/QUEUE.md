# Queue / backlog

Pending work, roughly prioritized. Reflects decisions on 2026-07-05.

## Next up (source bugs — hurt daily use)
- [ ] **aquamanga** (`626267…`) — browse works, **search 404s**. Likely the extension's search URL/params on our path.
- [ ] **mangadot** (`5900…`) — FlareSolverr solves but origin still **403s** (no WebView badge → header/auth quirk, not a WebView case).
- [ ] **mangafire** (`6084…`) — repeated **20 s timeouts** (often maintenance; confirm it isn't our timeout/interceptor).

## Nice-to-have (UI polish)
- [ ] First-run WebView UX: replace the raw 502 on the first hit with a friendly "starting in-app browser…" toast/spinner.
- [ ] Cosmetic rename `KcefWebViewProvider`/`KcefWebSettings`/`bin/jcef` internals → `Jcef…` (we're on JetBrains JCEF now). Touches the WebView factory registration; purely cognitive cleanup.

## Low priority (deferred by decision)
- [ ] **FlareSolverr concurrency throttle** (~2 simultaneous solves). "idc" for now; still good hygiene and carries into Proxmox (keeps under the container `mem_limit`). Cheap to add later.
- [ ] FlareSolverr session-TTL tuning (shorter TTL = Chrome sheds sooner between bursts). Windows-dev memory only.

## Proxmox deploy (not yet — but the destination)
- [ ] `docker-compose.yml`: `:server` + `flaresolverr` siblings, container-to-container networking, per-container `mem_limit`, `shm_size: 1g` for both Chromiums.
- [ ] `:server` Dockerfile with headless-CEF deps (fontconfig, libX11/libnss, etc.) so JCEF's Chromium subprocesses run in-container.
- [ ] Note: on native Docker/Proxmox the `vmmemWSL` reservation quirk disappears — memory tracks real working set. `.wslconfig` is Windows-dev only (`autoMemoryReclaim=gradual` if you want it tamed locally).

## Larger / later
- [ ] **Global search** screen (deferred spec): cross-source search, per-source rows, toggleable "HAS RESULTS" filter.
- [ ] **Trackers + richer library** (roadmap's top item, previously skipped).
- [ ] Reader polish: settings-sheet completeness (strip sizing/gap/preload/UI toggles), resume-mid-chapter edge cases.

## Explicitly NOT now
- Desktop (Compose) UI backlog — paused; focus is the web UI.
