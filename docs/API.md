# manga-utils HTTP API

The `:server` module (Ktor/Netty) serves a JSON API plus the built React SPA.

- **Base URL:** `http://<host>:8080` (default; override with env `MANGA_WEB_PORT`). Live box: `http://100.67.109.13:8080` over Tailscale.
- **Auth:** none. The server binds `0.0.0.0` and relies on being reachable only over the tailnet.
- **Content type:** request + response bodies are JSON unless noted. Image/log endpoints return bytes/text.
- **Source IDs** are `Long`. Most source routes take the id in the path (`/api/sources/{id}/...`); image/page routes take it as the `?source=` query param.
- Anything not matching an `/api/*` or `/img/*` route falls through to the SPA (`index.html`).

Routes below are grouped by area. Defined in `server/.../Main.kt`.

---

## Sources & browsing

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/sources` | List installed sources. |
| GET | `/api/languages` | Languages available across installed sources. |
| GET | `/api/sources/{id}/popular?page=` | Browse a source's popular list. |
| GET | `/api/sources/{id}/latest?page=` | Browse a source's latest list. |
| GET | `/api/sources/{id}/search?q=&page=` | Search within a source. |
| GET | `/api/sources/{id}/manga?url=` | Manga detail + chapter list (cached for library entries, else fetched). |
| GET | `/api/sources/{id}/preferences` | Source's extension preferences. |
| POST | `/api/sources/{id}/preferences` | Update a source preference. |
| GET | `/api/resolve?...` | Resolve a manga across sources (migration/global helper). |

## Library

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/library` | Full library list (per-entry: title, source, chapter counts, new count, last chapter/date). |
| POST | `/api/library` | Add a manga to the library. |
| DELETE | `/api/library` | Remove a manga from the library. |
| POST | `/api/library/update` | Start a background "check for updates" scan. |
| GET | `/api/library/update/progress` | Progress of the running update scan. |
| POST | `/api/library/clear-new` | Clear the "new chapters" flags. |

## Manga state

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/manga/state?...` | Per-manga state (bookmark, skip-parts, etc.). |
| POST | `/api/manga/bookmark` | Toggle/set a manga bookmark. |
| POST | `/api/manga/skip-parts` | Set the skip-redundant-parts flag for a manga. |
| POST | `/api/position` | Save reading position. |
| POST | `/api/read` | Mark chapter(s) read/unread. |
| POST | `/api/bookmarks` | Set a page/chapter bookmark. |
| GET | `/api/history` | Read history. |
| POST | `/api/history` | Record a history entry. |
| DELETE | `/api/history` | Delete a history entry. |
| POST | `/api/history/clear` | Clear all history. |

## Downloads

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/downloads` | Current queue snapshot. |
| POST | `/api/downloads` | Enqueue a download (manga + chapters). |
| GET | `/api/downloads/count` | Active/queued count. |
| POST | `/api/downloads/stop` | Stop one task. |
| POST | `/api/downloads/stop-all` | Stop everything. |
| POST | `/api/downloads/clear` | Clear finished tasks. |
| DELETE | `/api/downloads` | Delete downloaded content. |
| POST | `/api/downloads/remove` | Remove a task from the queue. |
| POST | `/api/downloads/move` | Reorder a queued task. |
| POST | `/api/downloads/resume` | Resume one task. |
| POST | `/api/downloads/resume-all` | Resume all paused/failed. |
| POST | `/api/downloads/force-retry` | Force-retry a failed task (bypasses known-broken skip). |
| GET | `/api/downloads/broken` | Chapters that failed/are known broken. |
| GET | `/api/downloads/unavailable` | Known-unavailable chapters (`UnavailableChapters`). |
| DELETE | `/api/downloads/unavailable` | Clear unavailable record(s). |

### Downloads: manage, repair, integrity

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/downloads/manage` | On-disk downloaded series overview. |
| GET | `/api/downloads/manage/chapters?title=` | Downloaded chapters for a series. |
| POST | `/api/downloads/manage/delete-incomplete` | Delete incomplete chapter folders. |
| DELETE | `/api/downloads/chapter` | Delete one downloaded chapter. |
| POST | `/api/downloads/manage/mark-unread` | Mark downloaded chapters unread. |
| POST | `/api/downloads/manage/repair` | Repair a series' downloaded structure. |
| POST | `/api/downloads/scan/corrupt/start` | Start a corrupt-file scan. |
| GET | `/api/downloads/scan/corrupt/progress` | Corrupt-scan progress. |
| POST | `/api/downloads/scan/repair` | Repair files found corrupt. |
| GET | `/api/downloads/manifest` | Manifest info (per-file hashes). |
| POST | `/api/downloads/manifest/generate` | Generate the manifest. |
| POST | `/api/downloads/manifest/verify` | Verify files against the manifest. |
| GET | `/api/downloads/manifest/progress` | Manifest generate/verify progress. |
| GET | `/api/downloads/mass/plan` | Plan a mass (re)download. |
| POST | `/api/downloads/mass/start` | Start the mass download. |

## Scanlator versions

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/scanver/plan` | Plan a scanlator-version operation. |
| POST | `/api/scanver/start` | Run it. |

## Stats & diagnostics

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/stats` | Library/download stats. |
| GET | `/api/diag` | General diagnostics snapshot. |

## Settings

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/settings` | Current settings. |
| POST | `/api/settings` | Update settings. |

## WebView / captcha

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/webview/open` | Open an in-app WebView session (JCEF / Chrome sidecar). |
| GET | `/api/webview/frame` | Current rendered frame (image bytes). |
| POST | `/api/webview/input` | Send keyboard/text input. |
| POST | `/api/webview/scroll` | Scroll the WebView. |
| POST | `/api/webview/autosolve` | Trigger the YOLO/ONNX captcha auto-solver. |
| GET | `/api/webview/autosolve/events` | Auto-solve event stream (SSE). |
| GET | `/api/webview/status` | WebView session status. |
| POST | `/api/webview/close` | Close the WebView. |
| GET | `/api/webview/pending` | Pending human-check prompts. |
| POST | `/api/webview/pending/clear` | Clear pending prompts. |

## Network & egress

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/net/ping` | Returns `pong` (latency probe). |
| GET | `/api/net/down` | Download throughput test payload. |
| POST | `/api/net/up` | Upload throughput test sink. |
| GET | `/api/net/status` | Online state + last-checked (`NetMonitor`). |
| POST | `/api/net/check` | Force a connectivity check. |
| GET | `/api/mullvad` | Mullvad/VPN egress status. |
| POST | `/api/egress/reset` | Reset the source-egress state. |
| GET | `/api/flaresolverr/test` | Test the FlareSolverr sidecar. |
| GET | `/api/flaresolverr/events` | FlareSolverr event stream (SSE). |
| GET | `/api/solver/events` | Captcha-solver event stream (SSE). |

## Health

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health/sources` | Per-source up/down/blocked health. |
| POST | `/api/health/sweep` | Start a health sweep across sources. |
| GET | `/api/health/sweep/progress` | Sweep progress. |
| GET | `/api/health/services` | Sidecar/service health (flaresolverr, solver, etc.). |
| GET | `/api/notify/status` | Notifier rate-limit status. |
| POST | `/api/webhooks/test/ping` | Send a test webhook ping. |
| POST | `/api/webhooks/test/sample` | Send a sample notification. |

## Backup, migration & export

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/backup/import` | Import a backup. |
| GET | `/api/backup/import/progress` | Import progress. |
| POST | `/api/backup/preview` | Preview a backup's contents. |
| GET | `/api/backup/local` | List local auto/manual backups. |
| POST | `/api/backup/local/create` | Create a local `.mudata` backup. |
| POST | `/api/backup/local/preview` | Preview a local backup (merge preview). |
| POST | `/api/backup/local/restore` | Restore a local backup (merges). |
| DELETE | `/api/backup/local` | Delete a local backup. |
| GET | `/api/backup/export` | Export manifest (what an export contains). |
| POST | `/api/backup/export` | Create an export package. |
| POST | `/api/dyno/backup-now` | Trigger the portable-drive backup job. |
| GET | `/api/dyno/backup/progress` | Portable-drive backup progress. |
| POST | `/api/migrate` | Run a data migration. |
| GET | `/api/migrate/preview` | Preview a migration. |
| GET | `/api/migrate/progress` | Migration progress. |
| POST | `/api/relocate/plan` | Plan a downloads-folder relocation. |
| POST | `/api/relocate/start` | Start the relocation. |
| GET | `/api/relocate/progress` | Relocation progress. |

## Extensions & repos

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/extensions` | Installed extensions. |
| GET | `/api/extensions/available` | Installable extensions from repos. |
| POST | `/api/extensions/check-updates` | Check for extension updates. |
| GET | `/api/extensions/changelog` | Extension changelog. |
| POST | `/api/extensions/install` | Install an extension (prebuilt jar). |
| POST | `/api/extensions/install-local` | Sideload a local jar. |
| DELETE | `/api/extensions` | Uninstall an extension. |
| GET | `/api/extensions/mangafire-vrf` | MangaFire VRF config. |
| POST | `/api/extensions/mangafire-vrf` | Set MangaFire VRF config. |
| DELETE | `/api/extensions/mangafire-vrf` | Clear it. |
| POST | `/api/extensions/mangafire-vrf/test` | Test MangaFire VRF. |
| GET | `/api/repos` | Extension repos. |
| POST | `/api/repos` | Add a repo. |
| DELETE | `/api/repos` | Remove a repo. |
| GET | `/api/repos/beta` | Beta repo list. |
| POST | `/api/repos/beta` | Set beta repos. |
| GET | `/api/repos/stats` | Repo stats. |

## Chapter pages & images

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/chapter/pages?source=&...` | Page URL list for a chapter. |
| GET | `/img/source-icon?source=` | Source icon bytes. |
| GET | `/img/ext-icon?...` | Extension icon bytes. |
| GET | `/img/cover?source=&url=` | Manga cover (proxied/cached). |
| GET | `/img/page?source=&chapter=&index=&title=&name=` | A single page image (serves local download if present, else fetches from source). Supports `X-Preload` / `?pre=1` preload hints. |

## Version

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/version` | Server version info. |

## Dev tools (hidden dev mode)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/dev/stats` | Dev stats. |
| GET | `/api/dev/storage` | Storage usage breakdown. |
| GET | `/api/dev/state` | Persisted state files list. |
| GET | `/api/dev/state/content?...` | One state file's content. |
| GET | `/api/dev/requests` | Recent source HTTP request log. |
| POST | `/api/dev/requests/clear` | Clear the request log. |
| GET | `/api/dev/diagnostics` | Full diagnostics dump. |
| GET | `/api/dev/identity` | Chapter-identity parse diagnostics. |
| GET | `/api/dev/tls` | TLS/JA3 fingerprint info. |
| GET | `/api/dev/captcha/stats` | Captcha solver stats. |
| GET | `/api/dev/captcha/generate` | Generate a test captcha. |
| POST | `/api/dev/captcha/solve` | Solve a test captcha. |
| GET | `/api/dev/solver/test?id=` | Run the solver against a source. |
| GET | `/api/dev/solver/raw?...` | Raw solver output. |
| GET | `/api/dev/source/{id}/diag` | Per-source diagnostics. |
| POST | `/api/dev/source/{id}/raw` | Raw source call. |
| GET | `/api/dev/jcef/pool` | JCEF browser-pool status. |
| POST | `/api/dev/jcef/reset` | Reset the JCEF pool. |
| POST | `/api/dev/webview/clear-cookies` | Clear WebView cookies. |
| GET | `/api/dev/webview/cookie-hosts` | Hosts with stored cookies. |
| GET | `/api/dev/series-backfill/preview` | Preview series-metadata backfill. |
| POST | `/api/dev/series-backfill` | Run series backfill. |
| GET | `/api/dev/migrate/manifest` | Dev export manifest. |
| GET | `/api/dev/migrate/export` | Dev data export. |
| POST | `/api/dev/migrate/preview` | Preview restoring a package. |
| POST | `/api/dev/migrate/import` | Import a package. |
| POST | `/api/dev/simulate-update` | Simulate an extension update. |
| POST | `/api/dev/restart` | Restart the server. |
| POST | `/api/dev/shutdown` | Shut the server down. |
| GET | `/api/logs` | Server log tail (`LogBuffer`). |

---

_Generated from `server/src/main/kotlin/mangautils/server/Main.kt`. Routes are the source of truth; regenerate this table when routes change._
