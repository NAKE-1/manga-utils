# Queue / backlog

Pending work, roughly prioritized. Reflects decisions on 2026-07-05.

## Next up (source bugs — hurt daily use)
- [x] **aquamanga** (`626267…`) — was NOT broken search: site 404s on `/page/2` past the last results page; our infinite-scroll looped on it. Fixed by stopping pagination on a next-page error (5eacd6e).
- [~] **mangadot** (`5900…`) — KNOWN LIMITATION. Cf-Mitigated: challenge on every endpoint; FlareSolverr can't get cf_clearance (managed/Turnstile). Cookie-replay can't clear it; would need to route the source through a real browser (WebView). Now fails with a clear message (9512846). Use an alternate source.
- [x] **mangafire** (`6084…`) — search timed out at 20s. Root cause: it computes an anti-bot `vrf` token via a WebView `evaluateJavascript`, but our provider DROPPED null/undefined results (and JS errors went down a cancel path), so the callback never fired and the extension's latch hit its own 20s timeout. Fixed: always invoke the callback ("null" on null, JSON-encoded to match Android) + JS errors now deliver null through the normal query path. Pending on-device test.

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
