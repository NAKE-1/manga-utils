# DYNO — Implementation Specification (build-ready)

**Companion to:** `dyno md/dyno init.md` (the product vision).
**Audience:** an engineer/AI implementing DYNO **inside the `manga-utils` codebase**.
**Status:** design spec, not started. Read this whole doc before writing code.

This document translates the DYNO vision into a concrete, phased build plan grounded in the
**actual** `manga-utils` code. Where the vision and reality differ, this doc wins and explains why.

---

## 0. TL;DR

DYNO = a **portable manga-library USB format** ("cartridge") plus the server-side machinery to
**export → detect → import → incrementally sync** libraries across machines, plus a **bundled
Explorer** so any PC can browse/read the drive with no install.

It is a **multi-phase** project, not one feature. Ship in this order; each phase is independently
useful:

- **Phase 0 – USB backup (the "small" one):** Docker auto-mount a USB, dump existing backup +
  downloaded chapters to it, unmount. No new format.
- **Phase 1 – DYNO drive format + export:** write library/metadata/covers/CBZs into a canonical
  `.dyno/` layout with a manifest.
- **Phase 2 – Detection + Portable Drives page:** server recognizes `DYNO-*` drives, reads manifest,
  Settings → Portable Drives UI with Export/Import.
- **Phase 3 – Import + incremental sync + conflict resolution:** UUID + checksum diffing.
- **Phase 4 – Explorer:** a portable build of the existing web UI pointed at the drive.
- **Phase 5 – Verify / clone / repair / safe-eject polish.**

**Reuse, don't reinvent:** `manga-utils` already has a sectioned backup system, CBZ/folder downloads,
an offline local reader, and a full web UI. DYNO is mostly **repackaging + a drive-management layer +
sync**, not new-from-scratch.

---

## 1. Codebase primer (what already exists — READ THESE FILES FIRST)

**Modules** (`settings.gradle.kts`): `core`, `source-api`, `android-compat`, `desktop`, `server`.
DYNO lives primarily in **`core`** (portable, reusable by CLI/desktop/server) and **`server`**
(HTTP API + web UI). Put drive logic in `core` so the desktop app can use it too.

**Data + storage layout** — `core/.../config/AppConfig.kt`:
- `AppConfig.dataDir` — app data root. Override via `MU_DATA_DIR` (env or `-D`). Default:
  `%LOCALAPPDATA%/manga-utils` (Windows) / platform home dir. In Docker it's `/data` (`MU_DATA_DIR`).
- `AppConfig.downloadsDir` — `dataDir/downloads` unless overridden by `downloadDirOverride` or
  `Settings.downloadDir`.
- `AppConfig.extensionsDir`, `logsDir`, `databaseFile` (`library.db`), `reposFile` (`repos.json`).

**On-disk download layout** — `core/.../download/DownloadManager.kt::destFor`:
- Series folder: `downloadsDir/<sanitize(title)>/`
- Chapter: `.../<sanitize(chapterName)>.cbz` **or** `.../<sanitize(chapterName)>/` (folder of images),
  controlled by `Settings.downloadAsCbz`.
- Cover: `downloadsDir/<sanitize(title)>/cover.{jpg|png|webp}` (see `saveCover` + the
  "on-disk cover for a downloaded series" helper near line 429).
- `DownloadManager.sanitize(...)` is the canonical filename sanitizer — **use it everywhere** for
  path names so DYNO paths match the reader's expectations.

**Local offline reader** — `core/.../source/LocalChapterReader.kt`:
- `LocalChapterReader.localChapter(title, chapterName): LocalChapter?` — returns a `LocalChapter`
  (`Archive` for CBZ, `Folder` for image dirs) with `size` + `bytes(index)`. This is how the web
  reader serves downloaded pages offline. DYNO's offline reader reuses this.

**Library / reading state** (flat JSON in `dataDir`, all `object` singletons):
- `library/LibraryStore.kt` → `library.json`, `List<LibraryEntry>`.
  `LibraryEntry(sourceId: Long, mangaUrl: String, title, cover, ... , key = "sourceId:mangaUrl")`.
- `library/ReadStore.kt` — read/unread chapter marks.
- `library/HistoryStore.kt` — "continue reading" resume positions.
- `library/BookmarkStore.kt` + `MangaBookmarkStore.kt` — bookmarks.
- `library/LibraryService.kt` — update/new-chapter logic.
- `download/DownloadStore.kt` — cached index of what's downloaded on disk (has `invalidate()`).

**Backup system (the seed of DYNO export/import)** — `core/.../backup/BackupImport.kt`:
- Format = **Mihon/Tachiyomi-compatible** gzipped **protobuf** (`ProtoBuf`, `@ProtoNumber`).
- `BackupImport.export(sections): ByteArray` where
  `Sections(library, settings, repos, extensions)` — **note: this is metadata/state only, NOT the
  image files.** It carries library entries, read/bookmark marks, `settingsJson` (@ProtoNumber 900),
  `repoUrls` (901), `extensionPkgs` (902).
- `BackupImport.import(gzBytes): Result` and `preview(gzBytes): Preview`.
- **Reading progress IS included** (read marks + bookmarks per chapter). History/resume position is
  separate (HistoryStore) — confirm whether it's in the backup; if not, DYNO Full Backup must add it.

**Server + web UI** — `server/.../Main.kt` (Ktor Netty) + `server/webui/` (React/Vite/TS):
- REST under `/api/*`, images under `/img/*`, SPA served from `server/src/main/resources/web/`.
- Download queue: `server/.../DownloadQueue.kt`.
- Settings screen: `server/webui/src/screens/Settings.tsx` (this is where the **Portable Drives**
  page attaches). Backup export/import UI already lives here — extend it.

**Docker/deploy** — `deploy/` (Dockerfile, docker-compose.yml, image `nake1/manga-utils`).
Container data dir = `/data`. Phase 0 mounts a USB into the container.

---

## 2. DYNO drive — canonical on-disk format (THE contract)

A DYNO drive is any volume whose **label starts with `DYNO-`** (e.g. `DYNO-001`) **and** contains a
`.dyno/manifest.json`. Layout:

```
<DRIVE ROOT>/
  .dyno/
    manifest.json        # identity + capabilities (see schema below)
    state.json           # mutable runtime state (last sync, generation, etc.)
    settings.json        # DYNO drive settings (auto-sync, export profile, encryption…)
    index.json           # fast catalog: series + chapters + hashes (the "sync.db"/"hashes.db")
    logs/                # newline-JSON operation logs (connected/import/export/verify/eject)
  Library/               # the actual manga content
    <sanitized series title>/
      cover.<ext>
      series.json        # per-series metadata (see schema)
      <sanitized chapter name>.cbz        (or a folder of images)
  Covers/                # OPTIONAL duplicate/thumbnail cache (can be omitted; covers live in Library/)
  Explorer/              # Phase 4: portable app (Windows/ Linux/ macOS/)
  Backups/               # timestamped full-backup blobs (the existing gz-protobuf backups)
  Temp/                  # scratch for atomic writes (write here, fsync, rename into place)
  README.txt             # human note: "This is a DYNO manga drive. Open Explorer/ to browse."
```

**Design rules:**
- **Atomic writes:** always write to `Temp/` (or a `.tmp` sibling), `fsync`, then `rename` into the
  final path. Never leave a half-written manifest/cbz. (Mirrors how `DownloadManager` only writes a
  chapter after all pages are in memory.)
- **Human-readable where cheap:** manifest/state/settings/series/index are JSON. Chapters are CBZ
  (already the download format) so any comic reader also works.
- **Reuse `sanitize()`** from `DownloadManager` for every filesystem name.

### 2.1 `manifest.json`
```json
{
  "schema": "dyno.manifest/1",
  "driveUuid": "5fddde2b-f7e0-4ea5-9eaa-000000000000",
  "label": "DYNO-001",
  "owner": "nakanoduck",
  "createdAt": 1720000000000,
  "manifestVersion": 1,
  "libraryVersion": 1,
  "explorerVersion": null,
  "compatibleServer": ">=0.1.0",
  "capabilities": ["library", "metadata", "covers", "progress", "sync", "explorer"],
  "filesystem": "exFAT",
  "integrity": { "algo": "sha256" }
}
```
- `driveUuid` is generated once at init and **never changes**. `label` may be renamed.
- Keep it small and mostly read-only; only rewrite on init/export.

### 2.2 `index.json` (the sync catalog — replaces "sync.db"/"hashes.db")
A single JSON (or SQLite if it grows; start with JSON) listing everything on the drive with stable
IDs + content hashes so the server can diff without scanning archive contents.
```json
{
  "schema": "dyno.index/1",
  "generation": 42,
  "updatedAt": 1720000000000,
  "series": [
    {
      "seriesUuid": "…v5…",
      "sourceId": 2327480808438768017,
      "mangaUrl": "/manga/OGQkW",
      "title": "Kaiju Girl Caramelise",
      "path": "Library/Kaiju Girl Caramelise",
      "coverHash": "sha256:…",
      "metadataHash": "sha256:…",
      "chapters": [
        {
          "chapterUuid": "…v5…",
          "chapterUrl": "/manga/OGQkW/JwpQfSNN",
          "name": "Chapter 12",
          "number": 12.0,
          "file": "Chapter 12.cbz",
          "pages": 26,
          "bytes": 3290000,
          "fileHash": "sha256:…",
          "read": true,
          "bookmarked": false
        }
      ]
    }
  ]
}
```

### 2.3 `series.json` (per series, in its folder)
Full metadata for offline Explorer + import merge: title, altTitles, description, author, artist,
genres, tags, status, reading direction, language, sourceId, mangaUrl, seriesUuid, addedAt,
modifiedAt, plus per-chapter `{chapterUuid, url, name, number, pages, bytes, read, bookmarked,
resumePage}`. This is the offline-authoritative copy of what the server stores across LibraryStore /
ReadStore / BookmarkStore / HistoryStore.

### 2.4 `state.json`
```json
{ "schema":"dyno.state/1", "lastConnected":0, "lastSync":0, "lastImport":0, "lastExport":0,
  "currentServer":"<server instanceId>", "syncGeneration":42, "verified":false, "lastMounted":0 }
```

### 2.5 Identity model (UUIDs) — CRITICAL for sync
`manga-utils` identifies a manga by **`sourceId` + `mangaUrl`** and a chapter by its **`chapterUrl`**.
DYNO wants stable UUIDs. **Derive them deterministically** so they're reproducible on any server and
map back to our keys:
- `seriesUuid = UUIDv5(DYNO_NAMESPACE, "$sourceId|$mangaUrl")`
- `chapterUuid = UUIDv5(DYNO_NAMESPACE, "$sourceId|$mangaUrl|$chapterUrl")`
- Fix one constant `DYNO_NAMESPACE` UUID in code.
- Integrity: `fileHash = sha256(cbz bytes)`; `coverHash`, `metadataHash = sha256(canonical json)`.

This means two servers that downloaded the same source/manga produce the **same** UUIDs → sync can
match without any prior coordination. (If a series was added from a different source, UUIDs differ —
handle via a soft title-match suggestion in conflict resolution.)

---

## 3. Export profiles → mapping onto our data

Each profile = "which parts of the drive we write." Implement as a `core` service
`DynoExporter.export(drive: Path, profile: ExportProfile, selection)`.

| Profile | Library CBZs | series.json / Metadata | Covers | Full backup blob (gz-protobuf) | Explorer |
|---|---|---|---|---|---|
| **Library Only** | ✔ | ✔ | ✔ | ✖ | ✖ |
| **Offline Reader** | ✔ (downloaded only) | ✔ | ✔ | ✖ | ✔ |
| **Full Backup** | ✔ | ✔ | ✔ | ✔ (`BackupImport.export(all)`) | ✔ |
| **Clone Server** | ✔ | ✔ | ✔ | ✔ (+ settings/repos/extensions) | ✔ |
| **Selected Manga** | ✔ (chosen) | ✔ (chosen) | ✔ | ✖ | optional |

Sources of truth to pull from:
- CBZs/covers → copy from `AppConfig.downloadsDir/<sanitize(title)>/...` (only what's downloaded).
- Metadata/read/bookmark/history → LibraryStore / ReadStore / BookmarkStore / HistoryStore →
  write `series.json` + `index.json`.
- Full-backup blob → `BackupImport.export(Sections(all))` into `Backups/backup-<ts>.tachibk.gz`.
- **Reuse `BackupImport` for the state layer; DYNO adds the file/content layer + the drive format.**

---

## 4. Detection & registration (Phase 2)

**Cross-platform mounted-volume discovery** (implement in `core`, `DriveScanner`):
- **Windows:** enumerate drive roots via `FileSystems.getDefault().rootDirectories` /
  `File.listRoots()`; read the volume label (`FileStore` or `Kernel32`/`wmic`/PowerShell
  `Get-Volume` fallback). Match label `^DYNO-`.
- **Linux/Docker:** scan `/media/*`, `/mnt/*`, `/run/media/*/*`, and `/proc/mounts`; label from the
  mount dir name or `lsblk -o LABEL,MOUNTPOINT`. In Docker, the compose mounts the USB in (Phase 0).
- **macOS:** `/Volumes/*`.

For each candidate: verify `.dyno/manifest.json`, validate schema/UUID/compatibleServer, then
**register** in a new store `core/.../dyno/DriveRegistry.kt` → `data/dyno-drives.json`:
`{driveUuid, label, capacityBytes, usedBytes, freeBytes, lastConnected, lastSync, health,
status, mountPath}`. Drives **stay listed when disconnected** (mountPath = null, status=offline).

Poll on an interval (e.g. every 5–10 s) OR wire OS mount events later; polling is fine to start.

---

## 5. Server API + Portable Drives page (Phase 2)

**New REST (Ktor, in `server/.../Main.kt` or a `DynoRoutes.kt`):**
- `GET  /api/dyno/drives` → registered drives + live status.
- `POST /api/dyno/drives/{uuid}/init` → initialize a blank DYNO drive (write `.dyno/`, manifest).
- `POST /api/dyno/drives/{uuid}/export` `{profile, selection?}` → run export (async job, progress).
- `POST /api/dyno/drives/{uuid}/import` `{mode: merge|replace|skip, selection?}` → run import.
- `POST /api/dyno/drives/{uuid}/sync` → incremental sync (diff → transfer).
- `POST /api/dyno/drives/{uuid}/verify` → integrity report.
- `POST /api/dyno/drives/{uuid}/eject` → flush + safe unmount.
- `GET  /api/dyno/jobs/{id}` → progress for export/import/sync/verify (reuse the DownloadQueue-style
  progress pattern).

**Web UI** — new screen `server/webui/src/screens/PortableDrives.tsx`, linked from Settings.
Per drive: name, capacity/used/free bar, health, sync status, connection status, last seen; actions:
Open, Export (profile picker), Import (mode picker + preview), Sync, Clone, Backup, Verify, Repair,
Rename, View Logs, Open Explorer, Safe Eject. Mirror the existing Settings dialogs' look.

---

## 6. Import + incremental sync + conflict resolution (Phase 3)

**Import flow:** read drive `index.json` → diff against server (by `seriesUuid`/`chapterUuid`) →
present a summary (New / Updated / Deleted / Changed metadata / Bookmarks / Progress) → user picks
**Import / Merge / Replace / Skip** → execute → update `state.json` + server stores.

**Incremental sync (both directions):** compare `seriesUuid` → `chapterUuid` → `fileHash`/
`metadataHash`/`generation`. **Transfer only differences.** Never full-copy unless "Clone." Bump
`index.json.generation` on every write.

**Conflict resolution:** if both sides changed the same entity since the last common generation →
prompt **Keep Local / Keep USB / Merge / Duplicate / Cancel**. For read-progress specifically, prefer
the **furthest** progress (highest page/chapter) unless the user overrides — a sensible auto-merge.

**Progress/read-state sync uses `BackupImport`-style data**, not files: merge ReadStore/HistoryStore/
BookmarkStore by chapterUuid.

---

## 7. Verification, safe eject, logs (Phase 5)

- **Verify:** manifest schema/UUID, `index.json` vs actual files, `sha256` of each CBZ vs `fileHash`,
  covers present, missing/corrupt archives, broken JSON. Produce a report (surface in UI + Explorer).
- **Safe eject:** finish pending writes → update manifest/state/logs/index → flush/fsync → unmount
  (`eject`/`udisksctl unmount` on Linux, `mountvol`/`Remove-Volume` semantics on Windows) → report
  "Safe To Remove."
- **Logs:** append newline-JSON to `.dyno/logs/YYYYMMDD.log` for connect/import/export/verify/eject/
  errors. Viewable in UI + Explorer.

---

## 8. Explorer (Phase 4) — reuse the web UI, don't rebuild

**Strong recommendation:** the Explorer is **the existing `server/webui` React app** repackaged to
run against the drive, NOT a new native app.

Two viable shapes:
1. **Portable server + browser (preferred):** ship a tiny JRE + a slimmed `server` build in
   `Explorer/<os>/` that serves the web UI reading directly from the drive (`MU_DATA_DIR` = drive,
   `downloadDir` = `Library/`), launched by a `run.(bat|sh|command)` that opens the default browser.
   Maximal reuse: the reader/library/detail screens already exist and already read local CBZs via
   `LocalChapterReader`.
2. **Static offline build:** a Vite build that reads `index.json`/`series.json` + CBZs via the File
   System APIs. More work, less reuse — only if a zero-runtime double-click is mandatory.

Either way, Explorer is **read-only browsing/reading** of the drive; it does not need sources/network.

---

## 9. Phase 0 — the "small" USB backup (do this first, standalone)

Independent of the format work; delivers immediate value.
- **Docker:** compose mounts a host USB into the container (bind mount of the mounted device path, or
  `--device` + an entrypoint that `mount`s it) at e.g. `/dyno`. Needs `privileged`/`SYS_ADMIN` +
  the filesystem tools (`exfat`/`ntfs-3g`) in the image.
- **Entrypoint / a scheduled job:** on a trigger (cron, or a `POST /api/dyno/backup-now`):
  1. mount the USB, 2. write `BackupImport.export(all)` → `Backups/backup-<ts>.tachibk.gz`,
  3. `rsync`/copy `downloadsDir` → `/dyno/Library` (optional), 4. fsync, 5. unmount.
- **Safety:** never delete on the USB; only add/update. Log every run.
- This becomes the mechanical substrate that Phases 1–5 build the real format on top of.

**Get the exact mount/unmount lifecycle + trigger cadence from the user before wiring Docker** (it
depends on their Proxmox host + which USB + auto vs manual).

---

## 10. New/changed files by phase (map)

- **Phase 0:** `deploy/docker-compose.yml` (device/bind + privileges), `deploy/Dockerfile` (fs tools),
  optional `server` route `POST /api/dyno/backup-now`, an entrypoint backup script.
- **Phase 1 (`core/.../dyno/`):** `DynoFormat.kt` (paths/schemas), `DynoManifest.kt`, `DynoIndex.kt`,
  `DynoIds.kt` (UUIDv5 + sha256), `DynoExporter.kt`. Serialization via kotlinx-serialization JSON.
- **Phase 2:** `core/.../dyno/DriveScanner.kt`, `DriveRegistry.kt`; `server/.../DynoRoutes.kt`;
  `server/webui/src/screens/PortableDrives.tsx` + Settings link + `api.ts` methods.
- **Phase 3:** `core/.../dyno/DynoImporter.kt`, `DynoSync.kt`, `ConflictResolver.kt`.
- **Phase 4:** `Explorer/` packaging (Gradle task to assemble a portable `server` + JRE); reuse webui.
- **Phase 5:** `core/.../dyno/DynoVerify.kt`, safe-eject in `DriveScanner`/routes.

**Reused as-is:** `BackupImport`, `DownloadManager.sanitize`/`destFor`, `LocalChapterReader`,
Library/Read/Bookmark/History stores, the web UI screens, `AppConfig`.

---

## 11. Open decisions (ask the user / decide before coding)

1. **Explorer:** portable-web-server (reuse) vs native app? (Spec strongly recommends reuse.)
2. **index.json vs SQLite** for the catalog at scale (start JSON; migrate if a drive holds thousands
   of chapters).
3. **Encryption / compression** (listed as future) — defer.
4. **Filesystem:** exFAT is the safe cross-platform default (Win/mac/Linux). NTFS needs `ntfs-3g` on
   Linux; ext4 isn't Windows-friendly. Recommend **exFAT**.
5. **Phase 0 lifecycle:** auto-on-insert vs scheduled vs manual button; which host (Proxmox VM);
   which USB device path. (Blocks the Docker wiring.)
6. **Non-downloaded chapters:** Offline Reader can only export what's on disk. Should Export offer to
   **download-then-export** missing chapters first? (Reuse `DownloadQueue`.)
7. **History/resume in Full Backup:** confirm HistoryStore is included in `BackupImport`; if not, add
   it so "Continue Reading" survives a clone.

---

## 12. Testing (per phase)

- **P0:** insert USB → trigger backup → drive has `Backups/*.tachibk.gz` + optional `Library/`; import
  that blob on a fresh instance restores library/progress.
- **P1:** export a small library → inspect drive layout, manifest, index, series.json, CBZ hashes.
- **P2:** insert a `DYNO-*` drive → appears in Portable Drives with correct capacity/health; init a
  blank drive works.
- **P3:** modify library on server A, export to drive, import on server B → correct New/Updated diff;
  change both sides → conflict prompt; progress auto-merges to furthest.
- **P4:** double-click Explorer on a clean PC → browse covers, open a manga, read a CBZ offline.
- **P5:** corrupt a CBZ → verify flags it; safe-eject reports "Safe To Remove" and the drive is
  cleanly unmounted.

---

## 13. Guardrails

- Everything portable goes in **`core`**; the server/desktop just call it.
- **Atomic writes** always (Temp → fsync → rename). A yanked USB must never corrupt the manifest.
- **Additive by default** — never delete user content on the drive without an explicit destructive
  action + confirmation.
- Reuse `sanitize()` and existing stores so DYNO paths/state stay consistent with the live app.
- Log every drive operation.

*(End of spec. Vision/marketing copy lives in `dyno init.md`; this file is the engineering contract.)*
