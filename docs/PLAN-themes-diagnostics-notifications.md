# Implementation plan — themes, diagnostics & notifications batch

**Status:** planned, not started. Logged 2026-07-12. Build order is small → big; later items build on earlier
ones. Each item lists backend changes, frontend changes, dependencies, and a test guide. Nothing here is
implemented until explicitly picked.

Scope (what the user asked for):
1. Themes — AMOLED black + named schemes (Monokai Pro, Dracula, Nord, …)
2. Download-queue reorder
3. Broken-download detection
4. Error log + latency/speed test (in-app diagnostics)
5. Source health dashboard
6. Health sweep (background)
7. Webhooks → Discord notifications (new chapters, downloads, etc.)

Dependency chain: **4 (error log)** underpins **5 (dashboard)**; **6 (sweep)** feeds **5**; **7 (webhooks)**
consumes events from the library update, the download queue, and **6**. So build 1–3 as independent quick
wins, then 4 → 5 → 6, then 7 last.

---

## 1. Themes (AMOLED + named schemes) — SMALL, frontend-only

**Goal:** pick a colour scheme in Settings; applies instantly and persists. Ship AMOLED-black plus a few
loved schemes.

**How it works:** `theme.css` already drives everything off CSS variables in `:root` (`--bg`, `--card`,
`--card-2`, `--border`, `--text`, `--muted`, `--accent`, `--accent-press`, …). A theme = one block that
overrides those vars, selected by a `data-theme` attribute on `<html>`.

**Frontend changes**
- `server/webui/src/theme.css`: keep current `:root` as the default ("Midnight violet"). Add
  `:root[data-theme="amoled"] { --bg:#000; --card:#0a0a0c; --card-2:#111114; --border:#1c1c22; … }` and
  blocks for `dracula`, `monokai`, `nord`, `rosepine` (etc.). Only variables change — no component CSS.
- `server/webui/src/main.tsx` (or a tiny `useTheme` hook): on boot read `localStorage['app.theme']` and set
  `document.documentElement.dataset.theme`. Do this **before first paint** (inline in `index.html` head or at
  the very top of `main.tsx`) to avoid a flash of the default theme.
- `server/webui/src/screens/Settings.tsx`: new "Appearance" card — a row of swatches (each shows the
  theme's bg + accent), tap to apply. Persist to `localStorage` and set the attribute.
- Update the PWA `theme-color` meta to match on switch (optional polish).

**Palette starters** (fill in exact hexes when building):
- AMOLED: pure `#000` bg, near-black cards, keep violet accent (or let each theme pick its own accent).
- Monokai Pro: warm charcoal bg `#2d2a2e`, accent `#ffd866`/`#ff6188`.
- Dracula: `#282a36` bg, accent `#bd93f9`.
- Nord: `#2e3440` bg, accent `#88c0d0`.

**No backend.** Themes are a client preference (not in the settings backup unless we later add it to
clientPrefs — note: the reader/clientPrefs backup already exists, so we *could* fold theme into it).

**Test guide**
- Settings → Appearance → pick AMOLED → whole app goes true-black instantly, accent unchanged.
- Reload → theme sticks, **no flash** of the old theme on load.
- Switch through each scheme → cards, borders, text, chips, badges all recolour; check the reader (black bg
  already), download badges, and the No-Poster placeholder still read fine.
- Watch for: any hard-coded colour in a component (not a var) that doesn't follow the theme — fix by
  swapping it to the relevant `--var`.

---

## 2. Download-queue reorder — SMALL

**Goal:** change the order of **queued** (not-yet-running) download tasks.

**Backend** (`server/.../DownloadQueue.kt`, `Main.kt`)
- `DownloadQueue` currently orders by `id` (`tasks.values.sortedBy { it.id }`) in `pump()` and `tasks()`.
  Add an explicit `order: Int` (or a `LinkedHashMap`/priority field) to `Task`; sort queued tasks by it.
- `fun move(id, dir)` or `fun reorder(ids: List<String>)` that reassigns order among **queued** tasks only
  (running/done are fixed). Guard: never reorder an active task.
- `POST /api/downloads/reorder` (body: ordered id list) or `/api/downloads/move?id=&dir=up|down` →
  respond `downloadsSnapshot()`.

**Frontend** (`Downloads.tsx`, `api.ts`)
- Up/down arrows on each **queued** row (simplest; drag-and-drop is nicer but more work — start with arrows).
- `api.reorderDownload(...)` → refresh snapshot.

**Test guide**
- Queue 3+ chapters from different manga; while the first runs, reorder the queued ones → order changes and
  the next-to-run reflects it.
- Reorder must be a no-op on the running/finished tasks (arrows hidden/disabled there).
- Watch for: a race where `pump()` starts a task mid-reorder — reorder only touches `state == "queued"`.

---

## 3. Broken-download detection — SMALL/MEDIUM

**Goal:** proactively surface chapters that downloaded partially/corrupt, library-wide, with one-tap repair.

**What exists:** `DownloadStore.listChapters(title)` already returns a `complete` flag per chapter;
`/api/downloads/manage/delete-incomplete` and `/repair` already act on incomplete ones. This item adds
**detection at a glance** (not per-series drill-in) + a slightly stronger integrity check.

**Backend** (`DownloadStore.kt`, `DownloadManager.kt`, `Main.kt`)
- Strengthen the completeness check: a chapter is "broken" if `complete == false` OR page count on disk <
  expected (from ComicInfo/`pages`) OR a zero-byte page file exists. Add `fun brokenChapters(title): List<..>`.
- `GET /api/downloads/broken` → across all series: `[{title, broken: [names], total}]` + a grand count.
- Reuse existing `/repair` per series to fix.

**Frontend** (`DownloadsManager.tsx` or a small banner)
- A "Broken downloads (N)" card at the top of Manage Downloads when N>0 → expands to the list → "Repair all"
  (loops the existing repair endpoint) or per-series repair.
- Optional: a badge on the Downloads tab.

**Test guide**
- Force a broken chapter: stop a download mid-way (leaves a partial) → it appears under "Broken".
- Repair → it re-downloads and drops off the list; the delete-total refresh (already fixed) updates.
- Watch for: false positives — a legitimately short chapter shouldn't read as "broken". Base the check on
  the stored expected page count, not a fixed threshold.

---

## 4. Error log + latency/speed test — MEDIUM (diagnostics foundation)

**Goal:** read recent server warnings/errors in-app, and ping/speed-test a source on demand. Foundation for
the health dashboard (#5).

**What exists:** a network speed test already lives behind `/api/net*` (`DiagResult`: ping, speed, ok) and
`devStats`. This item adds a **rolling error log**.

**Backend** (new `server/.../LogBuffer.kt`, wire into logging, `Main.kt`)
- `LogBuffer`: a thread-safe ring buffer (e.g. last 300 entries) of `{ts, level, logger, msg}`. Feed it via a
  logback appender (a small custom `AppenderBase` added to `logback.xml`) so every WARN/ERROR is captured
  without touching call sites. (Alt: a lightweight `log.warnAndBuffer` helper if we don't want a logback
  appender.)
- `GET /api/logs?level=warn&limit=100` → recent entries, newest first.
- Latency test: reuse/extend the existing `/api/net` diagnostic; add a per-source variant
  `GET /api/sources/{id}/ping` → `{pingMs, ok, imagesOk}` (HEAD the source base + a sample cover).

**Frontend** (new `screens/Logs.tsx`, route, Settings link)
- A "Diagnostics" section in Settings → "View logs" opens `Logs.tsx`: level filter, auto-refresh toggle,
  copy button. Colour-code WARN/ERROR.
- A "Test" button per source (in the health dashboard #5) calls the ping endpoint.

**Test guide**
- Trigger a known error (open a down source) → it appears in the log viewer within a refresh.
- Filter WARN vs ERROR works; the buffer caps at its limit (old entries drop).
- Ping a healthy source → low ms, ok=true; ping a down source → ok=false with an error.
- Watch for: the appender must not deadlock/recurse (don't log inside the appender); cap memory (ring buffer,
  fixed size); don't leak sensitive URLs/tokens into the client.

---

## 5. Source health dashboard — MEDIUM

**Goal:** one screen showing every source's state: up / down / Cloudflare-challenge / managed-CF /
circuit-open, with last-success time and a test button.

**What exists:** `SourceCircuits.api`/`.images` (open/closed + failure counts), `SourceHealth.isDown(id)` /
`areImagesDown(id)`, and `cfState(id)` — already surfaced per source in `/api/sources`. This item aggregates
them into a dedicated, richer view and adds "last success".

**Backend** (`SourceCircuits.kt`, `Main.kt`)
- Track `lastSuccessMs` / `lastFailureMs` per source in the circuit (cheap addition to `Circuit`).
- `GET /api/health/sources` → per source: `{id, name, cfState, apiDown, imagesDown, circuitApiOpen,
  circuitImagesOpen, lastSuccessMs, lastFailureMs, consecutiveFails}` + a rollup (`healthy/degraded/down`).

**Frontend** (new `screens/Health.tsx`, route, Settings/Drawer link)
- A list/grid of sources with a status **chip** (green up / amber CF / red down / grey circuit-open) — encode
  state in colour + label, summary counts at top (X healthy · Y degraded · Z down).
- Per row: last-success relative time + a "Test" button (uses #4's ping) + a link to filtered logs (#4).

**Dependencies:** reads #4's ping; nicer with #6's sweep feeding fresh `lastSuccess`.

**Test guide**
- Open the dashboard cold → mostly healthy; open a known managed-CF source (kagane) elsewhere, then refresh →
  it shows CF/managed state.
- Hammer a down source until its breaker trips → dashboard shows circuit-open + red, with last-success time.
- "Test" a row → updates its state live.
- Watch for: stale state — the page should re-poll (reuse the 18s source poll cadence, not a hot loop).

---

## 6. Health sweep — MEDIUM (background)

**Goal:** periodically verify library series' covers/first-page still resolve, so dead series/sources are
flagged *before* you hit them (Atsumaru's imageProber idea, adapted).

**Backend** (new `server/.../HealthSweep.kt`, `Main.kt`, `Settings.kt`)
- A scheduled job (like `UpdateScheduler`): every N hours, for each library series, HEAD the cover +
  optionally the latest chapter's first page. Record results into the per-source health state (#5) and a
  per-series `lastCheckedOk`.
- Rate-limited + circuit-aware (skip sources whose breaker is open; don't hammer). Runs on a daemon thread.
- Settings: `healthSweepEnabled`, `healthSweepHour` (reuse the time-of-day pattern from auto-update).
- `GET /api/health/sweep` → last run time + list of series that failed their last check.

**Frontend**
- Surface failed series on the Health dashboard (#5) ("3 series' pages didn't resolve") and/or a badge in the
  library. Toggle + schedule in Settings.

**Dependencies:** writes into #5's health state; its results are a good webhook trigger (#7).

**Test guide**
- Enable, set the schedule near now → it runs; a series on a healthy source passes.
- Point a series at a dead URL (or use a known-broken source) → it's flagged after the sweep.
- Watch for: the sweep must be gentle (rate-limited, breaker-aware) so it never causes the very outages it's
  detecting; it must not block startup (daemon thread, initial delay).

---

## 7. Webhooks → Discord notifications — MEDIUM/LARGE (event system, build last)

**Goal:** push notifications to Discord (or any webhook) on events: new chapters found, download completed,
download failed, (optionally) health-sweep failures. E.g. "📥 Downloaded *One Piece* — 3 chapters", "🆕 12 new
chapters across 5 series".

**Backend** (new `server/.../Notifier.kt`, `Settings.kt`, hook points, `Main.kt`)
- **Config** (Settings + patch DTO + Settings UI):
  - `webhooks: List<{url, kind}>` where `kind = "discord" | "generic"` (start with Discord + raw JSON).
  - Event toggles: `notifyNewChapters`, `notifyDownloadComplete`, `notifyDownloadFailed`,
    `notifyHealthIssues`.
- **Notifier**: `fun notify(event: Event)` → formats a payload (Discord embed JSON for `discord`, plain JSON
  otherwise) and POSTs via OkHttp on a background executor. **Best-effort**: never block or fail the
  triggering action; swallow/log errors; short timeout; simple retry (1–2x).
- **Hook points** (fire-and-forget):
  - Library update (`UpdateScheduler`/`LibraryService.update`): after a scan, if new chapters found →
    `NewChapters(summary, perSeries)`.
  - `DownloadQueue.run(task)`: on `done` → `DownloadComplete(title, n)`; on `failed` → `DownloadFailed(title,
    reason, failedCount)`.
  - (Optional) Health sweep (#6): on newly-failing series → `HealthIssue(...)`.
- **Test:** `POST /api/webhooks/test` → sends a sample message to the configured webhook(s).

**Frontend** (`Settings.tsx`, `api.ts`)
- "Notifications" card: webhook URL field(s), a "Send test" button (toast on success/failure), and the event
  toggles. Mask the URL after save (it contains a secret token).

**Dependencies:** consumes events from library update, download queue, and #6. Build after those exist so
there's something to notify about.

**Test guide**
- Paste a Discord webhook URL → "Send test" → a message lands in the channel; a bad URL → clear error, no
  crash.
- Enable "download complete" → download a chapter → Discord message with title + count.
- Enable "new chapters" → run a library update that finds new → one summarised message (not one per chapter —
  batch it).
- Trigger a failure (down source download) → "download failed" message (if that toggle is on).
- Watch for: **never block** the download/update on a slow webhook (background + timeout); don't spam (batch
  new-chapter notices; debounce); never log the full webhook URL (secret); handle Discord's rate limits
  gracefully (best-effort, drop on repeated failure).

---

## Suggested build order & checkpoints

| # | Feature | Size | Test-at-end checkpoint |
|---|---------|------|------------------------|
| 1 | Themes | S | Restart web build → pick AMOLED, verify persist + no flash |
| 2 | Queue reorder | S | Reorder queued tasks mid-download |
| 3 | Broken-download detection | S–M | Force a partial, see it flagged + repair |
| 4 | Error log + latency test | M | Trigger an error, read it in-app; ping a source |
| 5 | Source health dashboard | M | Trip a breaker, see red + last-success |
| 6 | Health sweep | M | Schedule near now; healthy passes, dead flagged |
| 7 | Webhooks → Discord | M–L | Send test; download → Discord msg; new chapters → batched msg |

Build 1–3 in any order (independent). Do 4 before 5. Do 6 after 5. Do 7 last. Commit + let the user test
after each, per the standing "restart-and-verify" cadence. Ship to Proxmox whenever a batch is worth it.
