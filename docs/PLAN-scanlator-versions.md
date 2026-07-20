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

### Still open

- **4a. Folder format:** `Chapter 1 [alpha 2]` (readable-first) vs `alpha 2_Chapter 1` (Suwayomi-identical).
  Recommendation stands at readable-first: versions of a chapter sort together, and the Suwayomi form
  buys interop we don't otherwise use. **Not blocking** — see "identity" below, which makes the folder
  name cosmetic.
- **4b. Do *background* library updates grab all versions too,** or only new chapters' primary version?
  Decision 1 covers explicit downloads. Auto-download-new running "all versions" silently doubles
  background traffic on every update. Recommendation: **new-chapter auto-download stays primary-only**;
  alternates come from an explicit action. Flagged because it is easy to get wrong by omission.

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
