# Queue / backlog

Pending work, roughly prioritized. Reflects decisions on 2026-07-05.

## PRIORITIZED BACKLOG (updated 2026-07-09)

Ranked by impact × effort. A big session's worth of fixes are committed but **only on local `w`** —
the phone reads from Proxmox, which is ~20 commits behind.

### Tier 1 — highest impact / ship-blockers
1. **SHIP: Docker rebuild → push → Proxmox `compose pull && up -d`.** Everything this session
   (open-by-URL, download-badge fix, No Poster, backup reader-prefs + restore progress bar,
   auto-update time-of-day + scan toast, working drawer, FlareSolverr auto-detect, scanlator read
   fix, circuit-breaker, stop-download) is only on `w`. The phone has none of it. **Do first.**
2. **Filename sanitize: strip trailing dots/spaces.** Chapters whose name ends in `...` (e.g. "The
   Guy She Was Interested In…" Ch.138 "…Are...") never save on Windows and forever show "not
   downloaded" (write fails / `isDownloaded` mismatch). One line: `.take(150).trim(' ', '.')` in
   `DownloadManager.sanitize`. Aligns with Windows' auto-trim so partial saves get recognized. Also a
   cross-platform hazard (Linux allows trailing dots). Small, high value.
3. **Circuit-breaker: count timeouts as failures.** In `Main.kt` `browse()` onFailure the guard
   `if (it !is CancellationException)` wrongly skips `TimeoutCancellationException`, so a source that
   times out (e.g. `6084…` at 20s every search) NEVER trips the breaker and stalls global search 20s
   each time. Fix = the same TimeoutCancellationException-vs-plain-cancel distinction already used in
   `SourceImage`. Small, kills the search stalls.

### Tier 2 — high impact, more effort
4. **FlareSolverr-response fallback (text).** Adopt Suwayomi PR #990: when cookie-replay is still
   blocked but FlareSolverr saw no challenge, return FlareSolverr's rendered response body instead of
   replaying cookies. Fixes **comix/kagane SEARCH + browse** (managed-challenge sites). Does NOT work
   for images (FlareSolverr returns HTML, not binary). Proven approach.
5. **Download-path circuit breaker + skip managed-challenge sources.** Downloads have no breaker, so a
   managed-CF source (kagane) grinds every chapter doing 2 FlareSolverr solves (~2s) each, all doomed.
   Prereq for a sane mass-download. Skip a source after N managed-challenge fails.
6. **Mass-download library** (user-requested). Settings button → scan for new → **plan preview**
   (per-manga, grouped by source, chapter counts, exclude unreachable/managed-CF sources) → confirm →
   enqueue "missing only". Reuse `LibraryService.update`, the badge's `downloadedChapterNames`, and
   `DownloadQueue`. Decisions to confirm: everything-missing vs new-only (default: missing); summary
   vs per-manga list (lean summary+expand); auto-skip managed-CF; no size estimate (too costly);
   dedicated preview screen.

### Tier 3 — medium
7. **Migration** — move a followed series to another source, keeping progress (match chapters by
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
- mangadot / mangafire — managed-CF / vrf; extension-side, not our code.


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
