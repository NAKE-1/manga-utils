# Plan — per-scanlator chapter versions (keep every upload)

**Status:** designed, not started. Decisions locked 2026-07-19.

## Goal

Stop silently discarding alternate uploads of the same chapter. A chapter folder is named
`sanitize(chapter.name)` only, so two uploads called "Ch. 1" from different scanlators collide — the
second is skipped (`"already exists"`) and never written.

Confirmed case: *The Black Leopard Family's Snow Leopard Baby* queued 126 entries and wrote **78**
folders, silently dropping 48 second-uploads, while the detail page still showed all 126 as downloaded
because the check matches by **name**, not by version.

You want every version kept deliberately — a second scanlator's copy is the fallback when one has bad
quality, missing pages, or a broken upload.

---

## Decisions locked

| # | Decision | Answer |
|---|---|---|
| 1 | What counts as "missing" | **Every (chapter, scanlator) pair not on disk.** Alternates are downloaded, not just numbers with zero copies. |
| 2 | Delete semantics | **Delete removes every version of that chapter.** |
| 3 | Existing folders | **Never renamed or moved.** A name-only folder is treated as a version and matched by content, not by name. |
| 4 | Naming format | Still open — see below. Your note ("*a decent chunk on atsu name themselves alpha, beta, delta, alpha 1, alpha 2*") is the real constraint and is designed for regardless of format. |

| 5 | Auto-download of new versions | **Eventually yes** — a new scan of chapter 12 should be picked up. **Gated off until verified**: nothing automatic or scheduled pulls alternate versions until the dev smoke-test signs it off. |

| 6 | Folder format | **`Chapter 4 [Gamma 2]` — always name the scanlator when the source gives one**, including the first version downloaded. |

### Why every version is suffixed, including the first

An earlier draft let the first download keep a plain name and suffixed only later ones. Real data killed
that: on atsu a chapter routinely has three versions (`Gamma`, `Gamma 2`, `Gamma 3`), so one folder per
chapter would always be the ambiguous one — you couldn't tell which scan `Chapter 4` held without opening
its ComicInfo.

```
Chapter 4 [Gamma]        Chapter 4 [Gamma 2]        Chapter 4 [Gamma 3]
```

Existing folders are still never renamed, so a library will mix plain (old) and suffixed (new) names.
That's accepted: identity comes from ComicInfo either way, and the mixing is visible rather than
misleading.

### Path length — measured, not assumed

Scanned all **444,922** files under `F:\manga-utils\downloads`:

- longest full path today: **199** chars → **61 chars of headroom** under Windows' 260 limit
- a typical ` [Gamma 3]` costs 10 → 51 left. Comfortable.
- longest manga dir 113, longest chapter dir 88. Those don't co-occur today, but if they ever did that
  path alone is **241** chars, and a suffix would overflow. `LongPathsEnabled` is not set on this machine,
  so 260 is a real ceiling.

Rules that follow:
- Cap the discriminator at **32 chars** (generous — `Gamma 3` is 7).
- If the full path would still exceed a **240-char** budget, **trim the chapter-name portion, never the
  discriminator.** The name is cosmetic; the discriminator is identity, and truncating it could make two
  versions collide again — the exact bug being fixed.
- Sanitize as before: strip path-illegal characters, collapse whitespace, drop `[`/`]`. If two scanlators
  sanitize to the same string, append the URL id.

Examples:
```
Chapter 4 [Gamma 2]              typical
Chapter 4 [Gamma 2].cbz          CBZ mode — suffix before the extension
Chapter 7 [3945015]              blank scanlator → stable id from the chapter URL
Chapter 9 [Team-Scans]           "Team/Scans" sanitized
Chapter 9 [Team-Scans 88214]     two groups collide after sanitizing → +URL id
```

---

## P0 results — map verified 2026-07-19

Verified directly against the code. Five confirmations and **one correction that changes the plan**.

| Item | Status |
|---|---|
| `destFor` — `base.resolve(sanitize(chapter.name))` (+`.cbz`), the only place a folder name is formed | ✅ `DownloadManager.kt:386` |
| Skip-on-collision `"already exists: $dest"`, guarded by `shouldReplaceExisting` | ✅ `:185` |
| `isDownloaded(title, chapterName)` name-only | ✅ `:427` — callers `Main.kt:637`, `:1007`, `desktop/App.kt:392,2194,2226,2260` |
| `downloadedChapterNames(title)` | ✅ `:451` — callers `Main.kt:1047,1239,1259`, `MigrationJob.kt:58,125` |
| Missing calc groups by number, then matches `sanitize(c.name) in onDisk` | ✅ `Main.kt:1047-1050`, `:1239-1243` — highest-risk item confirmed |
| `DownloadQueue.Chapter(url, name)` — **no scanlator** | ✅ `DownloadQueue.kt:39` — P1 target |
| `ChapterRef` **already has `scanlator`** | ✅ `LibraryEntry.kt:40` — plumbing is half-built already |
| **Reader reads from disk — offline reading EXISTS** | ❌ **Correction.** `LocalChapterReader.localChapter(title, name)` at `Main.kt:1680,1737`, keyed by **name only**. The reader *is* in the blast radius and needs a primary-version pick. |
| New chapters detected **by URL** | ⚠️ `LibraryService.kt:130` — `current.filter { it.url !in knownUrls }`. This is the exact line that makes a new scan look like a new chapter. |

---

## New chapter vs new version

A new upload of an existing chapter must not be mistaken for a new chapter.

`LibraryService.kt:130` treats any unseen URL as a new chapter, so a second scan of chapter 11 fires the
`!` badge and inflates the library's new-chapter count. Split the one concept into two:

```kotlin
val fresh       = current.filter { it.url !in knownUrls }
val newChapters = fresh.filter { key(it) !in knownNumbers }   // a number we've never had  -> "!" badge
val newVersions = fresh.filter { key(it) in knownNumbers }    // another scan of a number we have
```

(`key()` is the same number-or-name grouping the download counts already use, so the two agree.)

- **`newChapters`** keeps its current meaning: drives the `!` marker, the library "N new" count, and
  auto-download. 12 → 13 chapters is new; chapter 11 gaining a scan is **not**.
- **`newVersions`** is tracked separately and surfaced **in the chapter list only**, with its own marker
  distinct from the new-chapter one. It never contributes to the `!` count and never triggers
  auto-download while gating is on.

`LibraryEntry` gains `newVersions: MutableList<String>` beside the existing `newChapters`, cleared by the
same paths.

---

## Gating — nothing automatic until it's proven

Scheduled updates must not start pulling alternate versions on their own.

- A setting, **default off**: alternate versions are never queued by the library update or the
  scheduler. Detection still runs (so `newVersions` populates and the chapter list marks them) — only
  *fetching* is gated.
- Mass-download's missing calculation follows the same flag, so "download missing" doesn't silently
  become a library-wide alternate fetch before you want it.
- Turning it on is a deliberate act after the smoke test passes.

## Dev smoke-test controls (temporary)

A dev-only panel to prove this on one series before anything runs at scale. Explicitly temporary —
removed or folded into the real screen once P5 lands.

- **Pick a series** from the library list.
- **Scan** — dry-run for that series only: per chapter, what's on disk (from ComicInfo), which versions
  the source has, what's missing. Downloads nothing.
- **Fetch versions** — queue the missing alternates **for that one series**.
- **Show identity** — dump what we read from each folder's ComicInfo (url, scanlator, number), so a
  wrong identity is visible directly rather than inferred from behaviour.

This is what signs off P3/P4 before the flag is ever turned on library-wide.

---

## Design

### Identity lives in ComicInfo, not in the folder name

The single most important structural decision, and a change from the earlier draft.

Arbitrary scanlator names (`alpha 2`, `Δelta`, `Team/Scans`, blank) make folder names unreliable as
identity: they need sanitizing, they can collide after sanitizing, they get truncated by path limits,
and `[` `]` can appear inside a scanlator name. **So nothing ever parses identity back out of a folder
name.**

- `ComicInfo.xml` already stores `web = chapter.url`, a unique per-upload fingerprint.
- We additionally write the **scanlator** into ComicInfo, so new folders are self-identifying.
- A folder's identity = `(chapterUrl, scanlator, number)` read from its ComicInfo.
- The folder name is **cosmetic** — for you browsing the filesystem. Renaming a folder by hand must not
  break anything, and won't.

This also makes decision 4a genuinely low-stakes and reversible.

### Folder naming (cosmetic layer)

- One upload for a number → unchanged: `Chapter 1`. The overwhelmingly common case stays identical, and
  existing libraries keep working.
- A second upload arrives → `Chapter 1 [<disc>]`.
- Discriminator, in priority order:
  1. **scanlator**, sanitized: illegal path chars stripped, whitespace collapsed, `[`/`]` removed,
     **capped at 32 chars**.
  2. If blank, or if it sanitizes to the same string as an existing sibling's, append/fall back to a
     **stable short id from the chapter URL** (the numeric id where present, else a 6-char hash).
- **Windows path length is a real constraint.** `dataDir + manga title + chapter name + [disc] + page
  file` must stay under the limit. The chapter-name portion gets capped so the discriminator can never
  be truncated off — if it were, two versions could collide again, which is the exact bug we're fixing.

### Version-aware existence

Today `isDownloaded(title, chapterName)` answers "is there a folder with this name". It needs to answer
two different questions, because different callers want different things:

- **`hasAnyVersion(title, number)`** — "do I have this chapter at all?" → drives the badge/count that
  should not inflate.
- **`hasVersion(title, chapterUrl)`** — "do I have *this specific upload*?" → drives the missing
  calculation and per-row state.

Backward compatibility: a name-only folder with no scanlator in its ComicInfo is matched by its
`web` URL. If it has no URL either (ancient), it counts as "one unidentifiable version present" and that
number is never re-pulled — we never risk duplicating something you already have.

### Reading identity without re-reading everything

`hasVersion` needs each folder's ComicInfo, and the detail page asks per chapter row. Reading hundreds
of small XML files per page load is fine once and wasteful repeatedly.

Start with an **in-memory cache per manga directory, invalidated on the directory's mtime** — no new
files, no new format, and correct across external changes. Only if that measures slow do we add a
persisted per-manga index. `ponytail:` documented so the upgrade path is obvious.

### The three download flows, kept distinct

They currently blur together, and decision 1 affects them differently:

| Flow | Behaviour |
|---|---|
| **Mass download / download missing** (explicit) | All versions — decision 1. |
| **Fill alternates** (new job, below) | All versions, and the safe way to do the big first pass. |
| **Auto-download new chapters** (background) | Primary only — pending decision 4b. |

---

## What this breaks — the fragile core

Ordered by risk. The earlier draft's line references are from a previous session and **must be
re-verified before editing** (see P0).

1. **Mass-download "missing" calculation** — matches `sanitize(it.name) in onDisk`. Once folders carry a
   discriminator this match fails and *everything* reads as missing → re-downloads the library. Must move
   to identity-based matching. **Highest risk in the whole plan.**
2. **The upgrade shock.** Decision 1 means that after this ships, the first library-wide "download
   missing" legitimately wants every alternate you never had — potentially thousands of chapters and a
   large fraction of your library size again. This is *correct behaviour* and still needs to not
   ambush you: dry-run is mandatory, it reports counts and estimated size before queuing, and the
   recommended path is one manga first.
3. **Detail-page badge + reader state** — `isDownloaded` by name. Must become per-version, or every
   version of a number lights up as downloaded once any one is fetched (today's illusion).
4. **`DownloadStore` counts** — a series' chapter count must count **numbers**, not folders, or totals
   inflate again (126 instead of 78) — re-breaking the `467fab8` "unique chapters" fix.
5. **Delete** — becomes "all versions of this number" (decision 2); must enumerate versions rather than
   delete one path.
6. **Broken-download detection** — still per-folder, but an incomplete *alternate* now shows as its own
   broken entry. Ensure repair targets the right folder.
7. **Backup / restore / DYNO export** — copies folders, so new names are fine, but old backups carry
   old names; import must tolerate both, same compatibility rule as decision 3.
8. **Reader** — believed unaffected because offline reading isn't built (reader fetches live).
   **Verify in P0**; if offline reading exists, it must pick a primary version per number.

---

## Build order

**P0 — Re-verify the map (do not skip).** The line references in this document come from an earlier
session and the installer has changed since. Re-confirm: `destFor`, the skip-on-collision branch,
`isDownloaded`/`downloadedChapterNames` call sites, the mass-download missing calculation, `DownloadStore`
counting, delete, and whether the reader reads from disk. Correct this document, *then* write code.

**P1 — Plumbing, no behaviour change.** `DownloadQueue.Chapter` gains `scanlator`; populate it at every
enqueue site (mass-download, migration, auto-download-new, manual). Write scanlator into ComicInfo.
Ships invisibly and de-risks everything after it.

**P2 — Identity layer.** Read `(url, scanlator, number)` from ComicInfo with the mtime cache. Implement
`hasAnyVersion` / `hasVersion` alongside the existing checks, still unused. Testable in isolation.

**P3 — Version-aware storage.** `destFor` discrimination + replace the skip-on-collision with "write the
alternate". Both versions now land on disk. Existing folders untouched.

**P4 — State correctness.** Switch the callers: missing calculation, badges, counts, delete. **This is
where the breakage lives and where most of the testing goes.**

**P5 — The verification vehicle: a scan/fill screen.** Same shape as Relocate — pick **one manga** or the
whole library, **dry-run** that downloads nothing and logs per chapter ("Ch. 1: have `[official]`,
missing `[alpha 2]` → would download"), then a real run. Endpoints mirror the relocate job
(`plan` / `start` / `progress`). Build alongside P3/P4 — this is how P3/P4 get signed off, not a
nice-to-have afterwards.

**P6 — optional, later.** Reader "switch version" control, once offline reading exists.

---

## Verification

1. **Dry-run one manga** (Black Leopard) — log lists per chapter what's on disk and what it *would*
   fetch, downloads nothing. Read it before trusting it.
2. **Run that one manga** — `Chapter 1 [unofficial]` appears as its own complete folder, the original
   `Chapter 1` is byte-identical and untouched, nothing else changes.
3. **Existing downloads still recognised** — no mass re-download of things you already have. Check
   against a series with scanlators named unhelpfully (atsu's `alpha 1` / `alpha 2`).
4. **Counts stay honest** — detail page shows 78/78 for a fully-downloaded series, not 126/152.
5. **Delete** removes all versions of a number; Downloads manager totals count numbers, not folders.
6. **Backup → restore** on a clean data dir preserves every version and still matches.
7. Only after all of the above: dry-run library-wide, read the totals, then run.

---

## Risks and rollback

- **Rollback is cheap by construction.** Nothing existing is renamed, moved, or deleted, so reverting the
  code leaves a library that still works — alternates simply become inert extra folders.
- **The dangerous direction is the missing calculation**, not the storage. A storage bug writes an extra
  folder; a missing-calculation bug re-downloads a 71 GB library. P4 is gated behind P5's dry-run for
  exactly this reason.
- **Path length on Windows** is the most likely functional surprise; the Linux deploy won't show it, so
  test on Windows with a long manga title and a long scanlator name.
