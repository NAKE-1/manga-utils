# Implementation plan — download-queue persistence, auto-resume & migration label

**Status:** planned, not started. Logged 2026-07-16. Build after the current download finishes + the
migration count-fix is verified.

## Context
`DownloadQueue` is fully in-memory (`ConcurrentHashMap<String, Task>`). A server crash/restart wipes the
queue: completed chapters stay on disk, the one mid-download becomes a repairable "broken" partial, but
**queued + in-progress tasks vanish and don't auto-resume.** No corruption, but no pickup — "server
rebooted, my 40-chapter download is gone." This plan makes the queue survive restarts and resume itself,
plus tags migration-triggered downloads so they're labelled in the UI.

Key enabler already in place: `DownloadManager` **skips chapters that already exist** ("already exists"
outcome), and a chapter is only "complete" once its `ComicInfo.xml` is written last. So resuming a task =
re-run it: finished chapters are skipped instantly, the rest download. (A chapter that was mid-write
restarts from page 1 — see Part C for true mid-chapter resume.)

---

## Part A — "Migration download" label (small)
**Goal:** a download queued by a migration is visibly tagged in the Downloads list.
- `DownloadQueue.Task` gains `val tag: String = ""` (e.g. `"migration"`); `enqueue(..., tag = "")` param.
- `MigrationJob` passes `tag = "migration"` when it re-queues chapters from the new source.
- `DlTask` DTO + `Downloads.tsx`: render a small **MIGRATION** chip on tagged task cards.
- The tag persists with the task (Part B).

## Part B — Queue persistence + auto-resume (the main piece)
**Goal:** active downloads survive a restart and resume automatically.

### Persist (`server/.../DownloadQueue.kt`)
- Write the queue to `data/downloadqueue.json` on every structural change — **enqueue, task
  done/failed/stopped, reorder (move), remove, clearFinished** — NOT on per-page progress (too chatty and
  unnecessary; disk is re-checked on resume anyway).
- Persist only what's needed to resume, for **active (queued/running)** tasks:
  `{ id, sourceId, mangaUrl, title, order, tag, chapters:[{url,name}] }`. Coarse state only; live progress
  (doneCount/pages) is NOT persisted — it's recomputed from disk on resume.
- Finished tasks (done/failed/stopped) are ephemeral — not persisted (they're just history; "Clear
  finished" already discards them). *(Decision below: could persist a few for UI continuity.)*
- Writes are cheap (a few KB); guard with the existing `@Synchronized`.

### Resume (`server/.../Main.kt` startup)
- On boot, `DownloadQueue.loadAndResume()`: read the file, re-add each task as **`queued`**, then `pump()`.
- **Auto-repair partials on resume (important):** pages download in memory and the chapter is written to
  disk only at the end (ComicInfo last), so a crash *while downloading* leaves NOTHING on disk → that
  chapter re-downloads fresh. But a crash during the final *write* can leave a partial folder without
  ComicInfo, and the "already exists → skip" check only tests existence (SKIP policy), so it would SKIP
  the partial. So before re-queueing each resumed task, **delete its incomplete chapters** (no ComicInfo —
  reuse `DownloadStore.listChapters(title).filterNot { complete }` + `deleteChapter`). This guarantees any
  half-written chapter is discarded and re-downloaded fresh (no broken/corrupt pages); complete chapters
  are skipped.
- Wire it right after `LogBuffer.install()` / before the banner, on a daemon thread so startup isn't
  blocked by a resolve.

### Edge cases
- A source no longer installed → task fails cleanly (existing path), stays as failed.
- The persisted chapter URLs are stale? They're the same source URLs — still valid unless the source
  changed them (then they fail → Retry, as today).
- Don't double-resume: clear/rewrite the file after load so a second restart doesn't re-add stale tasks.

---

## Part C — DROPPED (per user, 2026-07-16)
- **Mid-chapter resume (gap #2): WON'T DO.** It would require streaming pages to disk incrementally (a
  per-page manifest) instead of the current atomic "all pages → write the whole chapter at the end,
  ComicInfo last" design. That atomic write is exactly what guarantees a chapter on disk is never
  partial/corrupt. User's call: **prefer re-downloading a whole interrupted chapter over ANY risk of a
  randomly corrupt chapter.** Keep the atomic write; a resumed chapter re-downloads from scratch.
- **Migration in-progress marker (gap #3):** parked with Part C. (Trivial detection-only; the migration
  ordering already prevents data loss. Can revisit standalone if ever wanted.)

---

## Decisions to confirm
1. **Persist active-only** (queued/running), letting finished tasks vanish on restart — or also keep the
   last N finished so the Downloads list looks continuous after a reboot? *(Rec: active-only, simplest.)*
2. **Resume = re-queue** running tasks (completed chapters skipped, a mid-download chapter restarts from
   page 1). OK for v1, with true mid-chapter resume as Part C later? *(Rec: yes.)*
3. **Migration label** text/placement — a small red **MIGRATION** chip on the task card. OK?
4. Include **Part C** now or defer? *(Rec: defer; ship A + B first.)*

## Build order
A (migration label — small) → B (persist + auto-resume — the main) → optionally C later.

## Verification
- Queue 5+ chapters; **kill the server mid-download**; restart → the queue reappears and **resumes**;
  already-downloaded chapters are skipped, the rest download; the migration-tagged task shows its chip.
- `data/downloadqueue.json` exists while active, is cleared/rewritten correctly, and finished tasks don't
  resurrect on a second restart.
- Trigger a migration with "delete + re-download" → the re-download task shows the **MIGRATION** chip and
  survives a restart.
