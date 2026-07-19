# Plan — Suwayomi-style per-scanlator chapter storage (keep multiple versions)

**Status:** planned, not started. Logged 2026-07-19.

## Goal
Stop silently discarding alternate uploads of the same chapter. Today a chapter folder is named
`sanitize(chapter.name)` only, so two uploads called "Ch. 1" (different scanlator/URL) collide — the
second is **skipped** (`"already exists"`) and never written. Adopt Suwayomi's model: put a discriminator
(scanlator) in the folder name so every version coexists in its own folder as a real backup. Confirmed
behaviour we're fixing: *The Black Leopard Family's Snow Leopard Baby* queued 126 entries, wrote **78**
folders (one per number), silently dropped the 48 second-uploads; the detail page still shows all 126 as
"downloaded" because the state check matches by **name**, not by version.

Suwayomi scheme (reference): `downloads/{source}/{manga}/{scanlator}_{chapter}` (folder or `.cbz`) — the
scanlator in the path is exactly what lets versions coexist. Tachiyomi/Mihon do NOT keep all (they filter
to one), and historically had our identical collision bug (Mihon #1395).

---

## Current mechanics (what the change touches)
All in `core/.../download/DownloadManager.kt` unless noted:
- **`destFor` (l.386)** — `base.resolve(sanitize(chapter.name))` (+`.cbz`). The ONLY place the folder name
  is formed. This is the core edit.
- **Skip on collision (l.185)** — `"already exists: $dest"` fires when `destFor` already exists.
- **`isDownloaded(title, chapterName)` (l.427)** — name-only existence check. Used by the detail page
  (`Main.kt:601`) and the reader-state endpoint (`Main.kt:940`).
- **`downloadedChapterNames(title)` (l.451)** — set of folder base-names. Used by **mass-download**
  (`Main.kt:1172/1192`) to compute "missing" via `sanitize(it.name) in onDisk`.
- **`DownloadStore`** — `listChapters/listSeries/incomplete/bytes/cbz` iterate folders; each folder = one
  "chapter" to the store (Downloads-manager, broken-download detection).
- **`DownloadQueue.Chapter` = `{url, name}`** (scanlator dropped before download). `SChapter` DOES carry
  `scanlator`; the queue is where it's lost.
- **Reader:** appears to fetch pages **live** from the source — no read-from-downloads path found
  (`downloadsDir` only appears in config + isDownloaded). *Verify: offline reading is a TODO, not built.*
  → If true, the reader is NOT in the blast radius (big simplification).

---

## Design
### Folder naming
- When a chapter's number has **one** upload → keep name-only (`Ch. 1`) — no change for the common case,
  and it keeps existing libraries readable.
- When a **second** upload of the same sanitized name arrives → give it a discriminated folder.
  Discriminator priority:
  1. **scanlator** if present and distinct → `Ch. 1 [GroupB]`
  2. else a stable **URL discriminator** — the numeric id in the URL (e.g. `…/3945015-chapter-1-en` →
     `Ch. 1 [3945015]`) or a short hash. Only for sources that return a **blank** scanlator.
- **Confirmed:** MangaFire *does* give a scanlator — Black Leopard's two "Ch. 1"s are `official` and
  `unofficial` (152/152 chapters have one). So the discriminator is almost always a real name
  (`Ch. 1 [official]` / `Ch. 1 [unofficial]`); the URL fallback is the rare no-scanlator case.
- Format: `"{name} [{disc}]"` (keep the readable name first; matches the CBZ-naming feature-request
  preference over Suwayomi's `scanlator_` prefix, and sorts better). The `[disc]` goes through
  `sanitize` too, and the name is capped short enough that the discriminator is never truncated off
  (else two versions could collide again).

### Thread the scanlator/URL through
- `DownloadQueue.Chapter` gains `scanlator: String = ""` (it already has `url`). Populate it at every
  enqueue site from the source chapter: mass-download (`Main.kt:1196`), migration (`MigrationJob`),
  auto-download-new (`UpdateScheduler`), manual enqueue.
- When building the `SChapter` for download, set `scanlator` + keep `url` so `destFor` can discriminate.
- **ComicInfo already stores the source URL** (`web = chapter.url`, l.344) — so a folder already knows
  which upload it holds; we can read it back to map folder↔URL for state/repair.

### Version-aware existence
- `isDownloaded` gains an overload keyed by the **chapter's url/scanlator**, not just name: "is *this*
  upload on disk?" = does its discriminated folder (or the name-only folder whose ComicInfo url matches)
  exist. Keep the old name-only overload for "do I have *any* copy of this number".
- `downloadedChapterNames` → split into two ideas the callers need:
  - `downloadedNumbers(title)` (unique chapter numbers present) — for the "do I have this chapter" badge.
  - `downloadedUrls(title)` (from ComicInfo) — for per-version checks.

### Identify what you already have (ComicInfo fingerprint) — "have one, get the next"
Every downloaded chapter's `ComicInfo.xml` records its source URL (`web = chapter.url`, l.344). That's the
fingerprint for "which version is this folder?", so we never re-download or guess:
1. Read each existing folder's ComicInfo → its URL (old `Ch. 1/` → `…/8178166-chapter-1-en`).
2. Map URL → scanlator via the source chapter list (`8178166 → official`) → the folder **is** the official
   version.
3. **Present set** = every `(number, scanlator)` we can fingerprint from disk.
4. **Missing** = the source's `(number, scanlator)` list − present → download those into `[scanlator]`
   folders. So "I have `official` → grab `unofficial`" falls straight out.
- **Going forward**, also write the scanlator *into* ComicInfo (dedicated field), so new folders
  self-identify without the source lookup; legacy folders fall back to URL→list.
- **No renaming:** existing name-only folders stay as the "primary"; only the *new* version gets the
  `[scanlator]` suffix — nothing you already have is moved or risked.
- **Edge:** a folder whose ComicInfo has no URL (ancient) or whose URL the source later dropped → can't
  map; safe fallback = treat as "one version present, unknown which" and **don't** re-pull that number
  (never duplicate what you have). Non-issue for anything downloaded with the current code.

---

## What it AFFECTS / could BREAK  ← the fragile core
1. **Mass-download "missing" calc (`Main.kt:1172/1192`)** — matches `sanitize(it.name) in onDisk`. Once
   folders are `Ch. 1 [GroupB]`, that name match **fails** → every chapter reads as missing → it would
   **re-download the whole library**. MUST update to match by number (already groups by number) against
   `downloadedNumbers`, and decide "missing" semantics (below). **Highest-risk breakage.**
2. **Detail-page badge (`Main.kt:601`) + reader-state (`Main.kt:940`)** — `isDownloaded(title, name)` by
   name. With per-version folders it must check the *specific* upload's folder, or every version of a
   number lights up "downloaded" once any one is grabbed (today's illusion). Decide per-row semantics.
3. **`DownloadStore` counts** — a series' "chapter count" now includes alternate versions; the
   Downloads-manager and unique-count logic (the `467fab8` "unique chapters" fix) must count **numbers**,
   not folders, or the totals inflate again (e.g. 126 instead of 78).
4. **Broken-download / incomplete detection** — iterates folders; still works per-folder, but an
   incomplete *alternate* version now shows as its own broken entry.
5. **Delete (`deleteChapter(title, name)` by name)** — ambiguous with multiple versions. Decide: delete
   one version (needs url/disc) vs delete all versions of a number.
6. **Migration re-download & delete+redownload** — will now write *all* versions into separate folders
   (the desired behaviour) and the delete-old step must remove per-version.
7. **Existing on-disk downloads (all name-only, e.g. `Ch. 1`)** — after the change the exists-check must
   still recognise old-scheme folders, or it re-downloads everything you already have. **Backward compat:
   treat a name-only folder as "version 1" and match it by number; never rename existing folders.**
8. **Backup / restore & DYNO export** — copies folders; new names are fine, but old backups carry old
   names → the import/match must tolerate both schemes (same compat as #7).
9. **Reader** — *not affected if offline reading isn't built.* If/when offline reading lands, it must map
   chapter(url) → the right version folder (via ComicInfo url), and pick a **primary** per number.

---

## What it IMPROVES
- Alternate uploads are **actually stored** as backups (the stated goal) instead of silently skipped.
- No more discarding a possibly **more-complete** version (the "what if a diff Ch. 1 has more pages"
  case) — you keep both; page count picks the reader default, not an irreversible guess.
- **Matches Suwayomi** → cleaner interop for export/DYNO/import and a proven, documented scheme.
- Detail-page counts stop lying once state is version-aware (fixes the confusing 126/152).
- Foundation for a later reader "switch version / scanlator" control.

---

## Manual control — verify on ONE title before the whole library
A dedicated, **settings-openable** screen — same shape as the Relocate screen: a live, **verbose log** you
watch, mirrored to the server log. The whole point is to *prove it on one manga first*, never turn it
loose on the whole library blind.
- **Scope:** pick **a single manga** (test) *or* the whole library.
- **Dry-run (scan only):** reports what it *would* do, per chapter — "Ch. 1: have `[official]`, missing
  `[unofficial]` → would download" — **without downloading anything.** This is the verify step.
- **Run:** same, but actually queues the missing versions.
- **Verbose log (in-app + server log):** every step visible —
  `scanning 78 folders… Ch. 1 = official (ComicInfo url 8178166)… source has official+unofficial…
  missing: unofficial… queued 'Ch. 1 [unofficial]'…` — so you can confirm the logic before trusting it.
- **Intended workflow:** dry-run one manga → read the log → run it on that one → confirm the
  `[unofficial]` folders appear and nothing else changed → *then* dry-run + run library-wide.
- Endpoints mirror the relocate/health jobs: `POST /api/scanver/plan` (dry-run; per-manga or all),
  `POST /api/scanver/start`, `GET /api/scanver/progress` (phase + counters + step log). Reuse the
  Relocate screen's log/progress UI pattern.

---

## Decisions to lock (need your call)
1. **Download all versions by default, or one-per-number unless asked?** Suwayomi keeps whatever you
   queue. If "all", mass-download "missing" means *"I'm missing a number entirely"* (have ≥1 version =
   not missing) — otherwise you'd perpetually re-pull second versions. Recommended: **"missing" = number
   has zero versions**; a separate opt-in "grab all versions" for the backup case.
2. **Naming format** — `Ch. 1 [GroupB]` (readable-first, recommended) vs Suwayomi's `GroupB_Ch. 1`.
3. **Discriminator when scanlator IS blank** (rare — *not* MangaFire, which gives official/unofficial) —
   URL id (`[3945015]`) vs short hash.
4. **Primary version** (for badge / future reading) — most pages / first grabbed / preferred scanlator.
5. **Delete semantics** — per-version or all-versions-of-a-number.
6. **Back-compat** — leave existing name-only folders as-is (recommended) vs one-time rename to the new
   scheme.

---

## Phasing (build order)
- **P1 — Plumbing (no behaviour change):** add `scanlator` to `DownloadQueue.Chapter`, populate at all
  enqueue sites, set it on the `SChapter` for download. Ships invisibly; de-risks the rest.
- **P2 — Version-aware storage:** `destFor` discrimination + `shouldReplaceExisting`/skip keyed on the
  disc (write the second upload instead of skipping) + **backward-compat matching** for old name-only
  folders. Now both versions land on disk.
- **P3 — State correctness:** update mass-download "missing" (match by number), detail/reader badges
  (per-version), `DownloadStore` counting (numbers not folders), delete semantics. **This is where the
  breakage lives — most of the testing budget goes here.**
- **P4 — Manual rescan/fill control (the verification vehicle):** the settings-openable screen above —
  **dry-run + verbose log, single-manga scope first**, then library-wide. Build this alongside P2/P3 so
  you can *prove the whole thing on one title* before it ever touches the library. This is how you sign
  off P2/P3, not a nice-to-have.
- **P5 — (optional, later)** reader primary-pick + a "switch version" control — only once offline
  reading from downloads exists.

---

## Verification
- **Dry-run one manga first** (Black Leopard): the log lists, per chapter, the version you have (from
  ComicInfo) and the one it *would* fetch — and downloads **nothing**. Read it, sanity-check the logic.
- Then **run that one manga**: `Ch. 1 [unofficial]` (etc.) appear as their own complete folders; your
  original `Ch. 1` is untouched; nothing else changes. Only *then* run library-wide.
- Download a series with two same-name uploads → **two folders** on disk (`Ch. 1 [official]` +
  `Ch. 1 [unofficial]`), both complete; nothing skipped.
- **Existing** name-only downloads are still recognised → no mass re-download after upgrading.
- **Mass-download "missing"** shows the right number (e.g. 78/78 for a fully-downloaded series, not
  126/152) and only queues genuinely-absent numbers.
- Detail page marks the specific downloaded version(s) correctly, not "all versions downloaded".
- Delete removes the intended version(s); Downloads-manager totals count numbers, not inflated folders.
- Backup → restore on a clean data dir preserves all versions and still matches.
