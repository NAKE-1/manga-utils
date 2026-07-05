# Queue / backlog

Pending work, roughly prioritized. Reflects decisions on 2026-07-05.

## Next up (source bugs — hurt daily use)
- [x] **aquamanga** (`626267…`) — was NOT broken search: site 404s on `/page/2` past the last results page; our infinite-scroll looped on it. Fixed by stopping pagination on a next-page error (5eacd6e).
- [ ] **mangadot** (`5900…`) — per user, the installed **extension is outdated**; a newer version likely fixes the CF handling. Action: update/replace the mangadot extension (repo), no app-code change. (Our interceptor already fails clearly if it still can't clear — 9512846.)
- [x] **mangafire** (`6084…`) — search timed out at 20s. Root cause: it computes an anti-bot `vrf` token via a WebView `evaluateJavascript`, but our provider DROPPED null/undefined results (and JS errors went down a cancel path), so the callback never fired and the extension's latch hit its own 20s timeout. Fixed: always invoke the callback ("null" on null, JSON-encoded to match Android) + JS errors now deliver null through the normal query path. Pending on-device test.

## Nice-to-have (UI polish)
- [x] First-run WebView UX: friendly "Starting in-app browser…" panel + auto-retry (createClient also waits up to 30s for CEF). (f7d7f32)
- [x] Cosmetic rename `Kcef*` → `Jcef*` now on JetBrains JCEF (8471593).

## Won't do (per user)
- FlareSolverr concurrency throttle + session-TTL tuning — user says FlareSolverr is fine. Skip.
- Trackers + library management — user doesn't care. Dropped from scope for now.

## Proxmox deploy (not yet — but the destination)
- [ ] `docker-compose.yml`: `:server` + `flaresolverr` siblings, container-to-container networking, per-container `mem_limit`, `shm_size: 1g` for both Chromiums.
- [ ] `:server` Dockerfile with headless-CEF deps (fontconfig, libX11/libnss, etc.) so JCEF's Chromium subprocesses run in-container.
- [ ] Note: on native Docker/Proxmox the `vmmemWSL` reservation quirk disappears — memory tracks real working set. `.wslconfig` is Windows-dev only (`autoMemoryReclaim=gradual` if you want it tamed locally).

## Next
- [ ] **URLs / deep-linking** — shareable/bookmarkable routes (manga, chapter, reader position) + browser back/forward. Constraint: must NOT break existing navigation. Up next.

## Done recently
- [x] **Global search** — per-source rows + toggleable "Has results" filter; a failed source counts as no-results (6d19bca).
- [~] Reader polish: preload slider for hybrid, "Keep screen on" (Wake Lock), resume re-pin on resize/rotate (f7d7f32). Remaining ideas: per-source sizing memory, tap-zone nav, brightness.

## Explicitly NOT now
- Desktop (Compose) UI backlog — paused; focus is the web UI.
