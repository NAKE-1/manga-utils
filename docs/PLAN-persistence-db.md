# PLAN — persistence: JSON today, DB (SQLite/Exposed) if ever needed

Reference / brainstorm doc. **Not planned work** — parked here so we don't re-derive it.
Current verdict: **flat JSON is fine at single-user scale; add a cache before ever reaching for a DB.**
Companion artifact (richer, same content): the "Flat JSON vs SQLite" page.

---

## 1. How persistence works today

- ~13 small JSON files under `data/` (`read.json`, `positions.json`, `history.json`, `library.json`,
  bookmarks, download queue, settings, repos, installed extensions, …). Each is a Kotlin `object`
  wrapping a keyed map, e.g. `ReadStore`: `"sourceId|mangaUrl" → Set<chapterUrl>`.
- **Whole-file, every time.** One `setRead(...)` loads *all* of `read.json`, mutates one set, and
  rewrites the *entire* file. Reads mirror it — **no in-memory cache**, so `isRead()` re-reads +
  re-parses the whole file on every call.
- **Crash-safe writes (`SafeFile`)**: write→`.tmp`→`fsync`→rotate old to `.old`→atomic rename; reads
  fall back to `.old` if the live file is unparseable. Built after a half-written `library.json` once
  ate the library.
- Concurrency = coarse `@Synchronized` per store. Backup/restore + portable `.mudata` just zip the files.
- **Precedent:** `DownloadQueue` already runs "memory is truth, disk is journal" (in-memory
  `ConcurrentHashMap` + `persist()`). Option A below just extends that pattern to the library stores.

## 2. The road already half-built

- `android-compat` is forked from **Suwayomi/Tachidesk** (`xyz.nulldev.*`). Suwayomi keeps all library
  state in an embedded SQL DB — **H2 via JetBrains Exposed**, typed tables + versioned boot migrations.
- manga-utils forked that runtime but **replaced** the DB with flat JSON for simplicity.
- A `:data` Gradle module exists, is wired into `server`/`cli`/`desktop`/`gui`, and its build file is
  commented *"persistence: Exposed ORM over SQLite … Phase 2+ adds exposed + sqlite-jdbc and the
  schema/repositories."* `exposed 0.56.1` + `sqlite-jdbc 3.47.1.0` are catalogued in `libs.versions.toml`.
  **But the module has zero source — an empty stub.** The migration was scoped, wired, then never
  written because JSON kept working. That inaction *is* the answer.

## 3. Where flat JSON actually hurts (write/read amplification)

| Hot path | Now | Cost |
|---|---|---|
| `setRead()` | rewrites all of `read.json` to flip one chapter | O(total reads) per mark |
| `setPosition()` | rewrites all of `positions.json` on every scroll checkpoint | O(total positions) per save |
| `DownloadQueue.persist()` | 16 sites; whole-file per state change | frequent during DLs |
| `isRead()` / `positions()` | re-parse whole file (no cache) | O(file) per query |
| cross-store writes | mark-read + position + history = 3 separate file writes | not atomic together |
| stats / global search | load every store fully + scan | O(n) full scans |

Core problem: **work per edit scales with whole-file size, not edit size.** Today `library.json`≈34 KB,
so nothing is felt. Bites only when `read`/`positions` reach ~1–2 MB.

## 4. Options (ranked)

- **A — JSON + in-memory cache (RECOMMENDED).** Two parts:
  - **A1 read cache** — cache the parsed map; reads from memory; **writes stay immediately durable.**
    Near-pure win, low risk. Kills read-amplification (stats, list loads, `mangaState`).
  - **A2 write coalescing** — debounce writes (one flush after a burst of scroll checkpoints). Removes
    write-amplification but introduces tradeoffs (§5). Do this **only for `positions`**, short debounce,
    shutdown flush, flush-before-backup. Leave `read`/`library`/`history`/`settings` immediately durable.
- **B — Hybrid: SQLite for ONE store** (finish `:data` for just `positions`/`read` or download history).
  Only if a store measurably outgrows files. Contains the cost to one place.
- **C — Full SQLite migration.** Correct long-term architecture; large rewrite. Not now (YAGNI).
- **0 — Do nothing.** The choice currently in effect; defensible until symptoms appear.

## 5. A2 (coalescing) tradeoffs — and why a DB dissolves them

Concerns introduced by debounced writes:
1. **Durability window** — a crash between mutation and flush loses recent edits. Mitigate: short
   debounce (~1–2 s), flush on shutdown, keep high-value/low-freq data immediately durable.
2. **Shutdown flush** — need a SIGTERM hook to flush dirty caches. `docker stop` grace = 10 s (ok if
   fast); `docker kill`/SIGKILL/power-loss in the window loses un-flushed edits.
3. **Cache ↔ disk divergence** — external writers bypass the store methods:
   - backup restore / `.mudata` import must update the *cache*, not just the file;
   - taking a backup reads files from disk → **flush dirty stores before any snapshot/export**;
   - manual edits / data-migration importer → need cache invalidation/reload.

**A DB removes all three** (SQLite = durable *and* partial writes together):
| A2 problem | With SQLite |
|---|---|
| Durability window | every `commit` durable now (`synchronous=NORMAL`+WAL) but it's a one-row write → no need to debounce → no window |
| Shutdown flush | nothing dirty in memory; committed rows on disk; kill mid-write rolls back the open txn |
| Cache divergence | the DB *is* the single source of truth; restore/import/migrations write through the same tables |

## 6. Would a full switch be faster?

At this scale, **not in raw stopwatch terms** — it improves how ops *scale* (row `UPDATE` = O(1 row) vs
O(whole file)). Felt only in the pathological cases: `setPosition` when `positions.json` is MBs, and
stats/global-search scans. It's future-proofing, not a current speedup.

## 7. What a full switch (C) would take

- **~13 tables.** Flat maps map cleanly. Friction = **nested structures** — `LibraryEntry` holds
  `knownChapters: List<ChapterRef>` → either a child table (`library_known_chapters`, proper) or a JSON
  column (pragmatic, forfeits query win).
- **Repositories** replacing each store `object`; every method in a `transaction { }`.
- **Rewire all callers** across `core`/`server`/`cli`/`desktop`/`gui`.
- **One-time JSON→DB migration** on first boot — idempotent, lossless (not one read marker/resume point).
- **Redo backup/restore/`.mudata`** — no more zipping files: checkpoint + copy `.db`, or keep a JSON
  export/import layer for portability; replace `.old` recovery with online-backup / `VACUUM INTO` +
  `PRAGMA integrity_check`.
- **Connection lifecycle + boot-time schema-migration runner** (new failure surface).
- Risk is **coverage**, not logic: miss one caller/field and data silently stops persisting.

### DB's own new concerns
- **WAL backups ≠ copy-the-file** — data spans `.db`/`-wal`/`-shm`; must checkpoint or use online-backup.
- **`database is locked`** — the passed-through **NTFS-in-Docker** mount is exactly where SQLite
  locking/WAL can misbehave. Mitigate: WAL, `busy_timeout`, single writer connection. (Suwayomi's H2 has
  the same class of concern.) Flat-file atomic rename is far more forgiving here.
- **Opaque corruption** — a bad page isn't a text file you can eyeball; recovery = `.recover`/integrity tools.

## 8. Revisit triggers (migrate on evidence, not vibes)

- `positions.json`/`read.json` cross **~1–2 MB** and scroll-save / mark-read feel laggy.
- Background jobs visibly **contend** — UI stalls behind a store's write lock.
- A real need for **relational queries** (global search, indexed stats joins).
- A torn multi-file write ever **corrupts a cross-store invariant** in practice.

Until then: cache (A) buys the felt wins now; the DB is the right move only when a store outgrows files.
