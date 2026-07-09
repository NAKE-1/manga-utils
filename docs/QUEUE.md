# Queue / backlog

Pending work, roughly prioritized. Reflects decisions on 2026-07-05.

## PRIORITIZED BACKLOG (updated 2026-07-09)

Ranked by impact × effort. A big session's worth of fixes are committed but **only on local `w`** —
the phone reads from Proxmox, which is ~20 commits behind.

### Tier 1 — highest impact / ship-blockers
1. **SHIP: Docker rebuild → push → Proxmox `compose pull && up -d`.** Everything (open-by-URL,
   download-badge fix, No Poster, backup reader-prefs + restore progress bar, auto-update time-of-day
   + scan toast, working drawer, FlareSolverr auto-detect, scanlator read fix, circuit-breaker,
   stop-download, PLUS the 2026-07-09 batch below) is only on `w`. The phone has none of it. Per user:
   **ship once a few more features are done.** ← still the only open Tier-1 item.
2. ✅ **DONE (0ddbe59) — Filename sanitize: strip trailing dots/spaces.** `trimEnd(' ', '.')` after
   truncation in `DownloadManager.sanitize`. Chapters like Ch.138 "…Are..." now save on Windows.
3. ✅ **DONE (0ddbe59) — Circuit-breaker counts timeouts.** `browse()` onFailure now records a
   `TimeoutCancellationException` as a source failure → flaky sources trip the breaker; no 20s stalls.

### Tier 2 — high impact, more effort
4. ✅ **DONE (0ddbe59) — FlareSolverr rendered-response fallback (text).** Suwayomi PR #990: on a
   still-blocked GET (non-image), re-solve with `returnOnlyCookies=false` and return FlareSolverr's
   rendered body. Should fix comix/kagane **search + browse**. NOT for images (browser → HTML). Needs
   on-device verification. Caveat: JSON-API sources may get browser-wrapped HTML — watch for that.
5. ✅ **DONE (849ff1f) — Download-path job-local circuit breaker.** A source that fails 4 consecutive
   chapters in a job is skipped for the rest of that job (remaining chapters → "skipped" fast) instead
   of grinding doomed FlareSolverr solves. Reset on any success. Separate from the browse breaker.
6. ✅ **DONE (e2ad631) — Mass-download library.** Settings → "Download missing chapters" → MassDownload
   screen: grand-total missing + per-series `downloaded/total` + `+missing` badge, checkboxes,
   select-all/clear, "Show complete" toggle, "Scan for new" (runs a library update first), sticky
   "Download N chapters" → Downloads. Endpoints `GET /api/downloads/mass/plan` + `POST .../start`.
   (Managed-CF sources aren't pre-excluded — the #5 download breaker skips them mid-job.)

- ✅ **DONE (e2ad631) — Retry bug.** Retrying one failed task called `clearDownloads()` (wiped ALL
  finished rows) so other failed tasks vanished. Added `DownloadQueue.remove(id)` + `POST
  /api/downloads/remove`; retry now removes only its own row.

### Tier 3 — medium
7. **App modes in the ☰ drawer — Normal / Incognito / Dev** (segmented control or dropdown).
   **Incognito is a FULLY SEPARATE PROFILE** (not just ephemeral history) — this makes it a bigger
   feature (data-namespacing across the app).
   - **Separate everything:** Incognito has its **own installed extensions/sources, own library, own
     history, own search + recents, own downloads** — isolated from Normal. You install NSFW
     extensions and add manga *while in Incognito* and they live only there; switching to Normal shows
     none of it. Implemented via a **profile-scoped data namespace** — e.g. Normal = `data/`,
     Incognito = `data/incognito/` (+ its own downloads dir) — with every store (`LibraryStore`,
     `HistoryStore`, `InstalledStore`/extensions, `ReadStore`, `BookmarkStore`, browse/search state)
     resolving its path from the **active profile**, which the client sends as a mode flag/header
     (single-user server, so the mode is a UI toggle). Extension loading/`SourceManager` must load the
     active profile's extensions dir.
   - **"Wipe Incognito" button:** nukes the entire Incognito namespace — its extensions, library,
     history, search, and downloads — one click, back to empty. (Confirm dialog.)
   - **Persistence:** Incognito **persists on disk until wiped** (so you can build up an NSFW
     collection), NOT per-session-ephemeral. (This is a deliberate change from the earlier ephemeral idea.)
   - **Dev mode toggle:** gate the Developer settings section + dev-only UI/diagnostics behind it
     (hidden by default, shown when on).
   - UI: `Drawer.tsx` — a Normal|Incognito segment + a Dev switch. Mode persists in localStorage so a
     reload keeps you where you were; a subtle app-wide indicator when Incognito is active.
   - **Effort:** substantial — the whole server data layer becomes profile-aware. **Decisions to
     confirm:** does downloads get namespaced too (yes, so NSFW files are isolated + wiped); is there a
     lock/PIN on entering Incognito (probably not for v1); does Wipe also clear the mode's browse cache.
8. **Migration** — move a followed series to another source, keeping progress (match chapters by
   number, carry read/bookmarks/resume, replace-with-undo, leave existing downloads). Scoped.
8. **JCEF-fetch for images (managed-CF downloads/reading).** Our unique edge vs Suwayomi (we bundle
   JCEF, they don't): route flagged hosts' page/image requests through the real browser to beat
   managed challenges. Only path to images from kagane/comix. Slow + low-concurrency + real work +
   may fail on interactive Turnstile → **prove one kagane page via JCEF first.**
9. **Interactive search timeout (P1 tiered timeouts)** — 20s per source stalls global search; a
   shorter interactive timeout (~8–10s) helps even before the breaker trips.

### Tier 4 — small polish / low
10. **Stale delete-total refresh** — after Delete-all a series still shows "57 ch · 329 MB" until refresh.
11. **Throttle the `/api/sources` poll** (log/perf cleanliness).
12. **Reading stats page** — chapters read, per-series (user lukewarm; optional).

### Big / later (needs its own design pass)
13. **DYNO** — portable USB library. Spec in `dyno md/`, test fixture ready. Paused for a design
    discussion before any Phase 1 build.

### Parked (per user)
- Reader polish (tap-zone, brightness, zoom, paged) — "none of the reader stuff matters."
- HTTP/2 / two-port — not worth it on working sources.
- Desktop Compose UI — focus is the web UI.
- mangadot — managed-CF / outdated extension; extension-side, not our code.
- ~~mangafire~~ — **working now** (per user 2026-07-09); the `vrf`/WebView provider fix landed.


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

## Done recently
- [x] **URLs / deep-linking** — Search now URL-driven (6f8d887); Detail/Reader/SPA-fallback already were. User: URL length is fine as-is, just keep links robust.
- [x] **Source-not-installed card** on Detail for links to a missing extension (3cae183).
- [x] **FlareSolverr cold-start retry** (smooths comix's flaky first solve) + **friendly port-in-use message** (3cae183).

## Feature backlog (fuller list — pick by impact)
### Reader experience
- [ ] Tap-zone navigation (tap edges to page / jump), and an end-of-chapter "next chapter" overlay.
- [ ] Reader background/theme (black / white / sepia) + brightness dim overlay.
- [ ] Per-source sizing memory (some sources are webtoon, some paged); double-page spread option.
- [ ] Pinch-zoom / double-tap zoom on a page.
### Library
- [ ] Categories / collections + sort & filter (unread, recently updated, title, source).
- [ ] Unread-count badges; bulk actions (mark read, download, remove).
- [ ] Library search box.
### Discovery
- [ ] Per-source genre/filter UI in browse (sort, tags).
- [ ] "Updates" feed — new chapters across your whole library in one list.
### Downloads / offline
- [ ] Download a range / whole manga; storage-usage view; per-manga "keep downloaded".
- [ ] Verify offline reading of downloaded chapters end-to-end in the web reader.
### Links / sharing
- [ ] External paste-a-source-URL → resolve to source + open (inbound, the "(b)" feature).
- [ ] Prettier slug-in-path URLs (deferred — user OK with current length).
### Deploy / infra
- [ ] **Proxmox**: docker-compose (:server + flaresolverr, mem_limit, shm_size) + :server Dockerfile with headless-CEF deps.
- [ ] Throttle the every-2s /api/sources poll.
- [ ] **DYNO — portable-library USB system** (spec: `dyno md/dyno init.md`). Big multi-phase project (drive format + detection + manifest, export profiles, incremental UUID/checksum sync + import/merge, Settings→Portable Drives page, verification, safe eject, and a standalone Explorer GUI on the drive). The Docker auto-mount/unmount is one small slice. **Needs phasing + user sign-off before building.**

### Known bugs (minor)
- [ ] After **Delete all** for a manga in Settings, the total doesn't refresh: the main area shows "no chapters" but the size line still says e.g. "57 chapters · 329.1 MB" until a manual refresh. Stale downloaded-total after delete. (Low priority.)

## Done recently
- [x] **Global search** — per-source rows + toggleable "Has results" filter; a failed source counts as no-results (6d19bca).
- [~] Reader polish: preload slider for hybrid, "Keep screen on" (Wake Lock), resume re-pin on resize/rotate (f7d7f32). Remaining ideas: per-source sizing memory, tap-zone nav, brightness.

## Explicitly NOT now
- Desktop (Compose) UI backlog — paused; focus is the web UI.
