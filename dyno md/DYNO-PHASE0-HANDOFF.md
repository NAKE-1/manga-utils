# DYNO Phase 0 — Handoff (USB Backup)

**For:** the next engineer/Claude picking up DYNO.
**Companion docs:** `dyno md/dyno init.md` (product vision), `dyno md/DYNO-IMPLEMENTATION-SPEC.md`
(build-ready phased spec — **read §9 + §1 first**). This doc records **what Phase 0 actually built**,
why, and what's left. Where this doc and the spec differ, **this doc reflects reality**.

**Status:** Phase 0 is **implemented and verified end-to-end, NOT yet committed** (all changes live in
the working tree). Phases 1–5 are **not started**.

---

## 0. TL;DR — what Phase 0 is

A **manual "Back up to USB" button** in the server's Settings screen that writes two things to a
mounted drive:
1. `Backups/backup-<ts>.tachibk.gz` — the existing gz-protobuf full metadata backup (library + read
   marks + bookmarks), produced by the existing `BackupImport.export(...)`.
2. `Library/<series>/<chapter>.cbz` (+ `cover.<ext>`) — a mirror-copy of the downloads directory.

Plus a `.dyno-backup.log` newline-JSON run log on the drive.

**There is NO DYNO format yet** — no `.dyno/`, no manifest, no `index.json`, no detection, no sync, no
Explorer. That is all Phases 1–5. Phase 0 is deliberately the "small, standalone, immediately useful"
slice that becomes the mechanical substrate for the rest.

---

## 1. Decisions locked with the user (do not re-litigate without asking)

These were chosen explicitly by the user via a decision prompt. They shape the whole implementation:

1. **Mount strategy = host mounts, bind into container.**
   The Proxmox host mounts the USB (fstab/udev); `docker-compose.yml` bind-mounts that host path into
   the container at `/dyno`. **The container stays non-root (UID 1000), unprivileged, with no image
   changes and no `mount`/`umount` shell-outs.** The app only reads its data dir and writes files into
   the bind-mounted directory.
   - *Why:* the current container runs as UID 1000 with no `privileged`/`devices`/`cap_add` and no
     filesystem tooling (`util-linux`/`exfat`/`ntfs-3g`) in the image. In-container raw-device mounting
     (the original spec §9 vision) would need all of that + a bigger blast radius. The host-mount +
     bind pattern is already documented (commented) in the compose file and needs zero privilege.
   - *Consequence:* the app never shells out. "Is the USB there?" = "does the bind-mounted directory
     exist and is it writable?" — a plain filesystem check.

2. **Trigger = manual button first.** `POST /api/dyno/backup-now`. Scheduling is deferred. When we add
   it, the hook is the existing `UpdateScheduler` (a `ScheduledExecutorService`), not OS cron.

3. **Contents = backup blob + downloaded chapters.** Additive only; never deletes on the drive.

---

## 2. Approach & design rules

- **Reuse, don't reinvent.** The blob is literally the existing `BackupImport.export(...)`. The source
  paths are the existing `AppConfig.downloadsDir` layout. The job/progress engine mirrors the existing
  `DownloadQueue`. The progress-polling UI mirrors the existing library-update progress pattern.
- **Portable logic in `core`.** All disk logic is in `core/.../backup/UsbBackup.kt` (no server deps),
  so desktop/CLI can reuse it later. The server is a thin job wrapper + routes.
- **Additive & idempotent.** Never delete anything on the drive. A file already present with an
  identical size is **skipped**, so repeat runs only transfer what changed (cheap incremental).
- **Atomic writes always.** Every file is written to a sibling under `Temp/` (`<name>.<nanos>.part`),
  `fsync`'d (`FileChannel.force(true)`), then renamed with `ATOMIC_MOVE` (falling back to a plain
  replace if the FS rejects atomic move). A yanked USB never leaves a half-written blob or CBZ — worst
  case is a stray `.part` cleaned up next run. This mirrors the spec §13 guardrail.
- **Never throws to the caller.** `UsbBackup.run` returns a `Result(ok, error, …)`; precheck failures
  (not mounted / not writable) and IO errors come back as `ok=false` with a message.

---

## 3. Files (exact paths)

### New
- `core/src/main/kotlin/mangautils/core/backup/UsbBackup.kt` (~190 lines) — the engine.
- `server/src/main/kotlin/mangautils/server/BackupJob.kt` (~71 lines) — the progress job.

### Edited (all **purely additive** — no existing logic removed/rewritten)
- `core/src/main/kotlin/mangautils/core/config/Settings.kt` — one new field `usbBackupDir: String = ""`.
- `server/src/main/kotlin/mangautils/server/Main.kt`:
  - `BackupJobDto` (new @Serializable DTO).
  - `dynoTarget(): Path` helper — resolves the target path (see §5).
  - `backupJobDto(task)` mapper.
  - `usbBackupDir` added to `SettingsDto`, `SettingsPatch`, the `settingsDto(...)` mapper, and one
    `body.usbBackupDir?.let { s = s.copy(...) }` line in `POST /api/settings`.
  - Two routes: `POST /api/dyno/backup-now`, `GET /api/dyno/backup/progress`.
  - `/api/dyno/backup/progress` added to the `CallLogging` filter (so ~1s polling doesn't flood logs).
- `server/webui/src/api.ts` — `backupToUsb()`, `usbBackupProgress()`, `BackupJob` type, and
  `usbBackupDir` on `SettingsInfo` + the `saveSettings` patch type.
- `server/webui/src/screens/Settings.tsx` — state hooks, `saveUsbDir()`, `startUsbBackup()`, a polling
  `useEffect`, and a "Back up to USB" card inside the existing Backup section.
- `deploy/docker-compose.yml` — commented `- /mnt/usb:/dyno` bind mount + `MU_DYNO_DIR=/dyno` env.
- `deploy/README.md` — a "Back up to a USB drive (DYNO Phase 0)" section (host mount + chown steps).

### NOT mine — leave alone
- `docs/TEST-CHECKLIST.md` was already modified in the working tree before this work started. It is
  unrelated to Phase 0.

---

## 4. How `UsbBackup.run` works (the core contract)

```kotlin
object UsbBackup {
    enum class Phase { PREPARING, EXPORTING, COPYING, DONE, FAILED }
    fun interface Progress { fun update(phase: Phase, filesDone: Int, filesTotal: Int, bytesCopied: Long) }
    data class Result(ok, error?, blobName?, filesCopied, filesSkipped, bytesCopied)
    fun run(target: Path, progress: Progress = {}): Result
}
```

Flow:
1. **PREPARING** — verify `target` is a directory; create `Backups/`, `Temp/`, `Library/` (creating
   them is also the real writability test — a read-only/absent mount throws here → clean failure).
2. **EXPORTING** — `BackupImport.export(Sections(library=true, settings=true, repos=true, extensions=true))`
   → write atomically to `Backups/backup-<yyyyMMdd-HHmmss>.tachibk.gz`.
   - **Note:** there is no built-in "all" factory; you must pass all four booleans explicitly.
3. **COPYING** — `Files.walk(AppConfig.downloadsDir)` → for each regular file, mirror to
   `Library/<relative path>`; skip if dest exists with identical size; else copy atomically. Progress
   reports `filesDone/filesTotal` (done counts copied+skipped so the bar completes) and running bytes.
4. **DONE** — clear `Temp/`, append a run record to `.dyno-backup.log`.

The blob carries **read marks + bookmarks** (per `BackupImport`) but **NOT history/resume** — see §7.

---

## 5. Target-path resolution (`dynoTarget()` in Main.kt)

Order: `Settings.usbBackupDir` (if non-blank) → `MU_DYNO_DIR` env var → default `/dyno`.
This lets the shipped Docker default work with zero config while staying overridable from the UI.

---

## 6. Verification already performed (and how to re-run it)

Build gates (all green):
- `./gradlew :core:compileKotlin :server:compileKotlin` — compiles.
- `cd server/webui && npx tsc --noEmit` — clean; `npm run build` — bundles.

Runtime (drove the real compiled `UsbBackup.run` against a scratch dir):
- Fresh run: copied files + wrote an atomic blob; all phases fire; `.dyno-backup.log` written.
- Second run: **0 copied / N skipped** (idempotent size-check) + a fresh blob.
- Bad target (a file, not a dir): `ok=false`, clear error, no crash, nothing written.
- Blob validity: gunzips to valid protobuf bytes (it IS the format `/api/backup/import` consumes).

**Gotcha found while testing:** `AppConfig.dataDir` runs a one-time **legacy-data migration** — if the
data dir has no `extensions/installed.json` marker, it copies a `./data` (cwd-relative) dir into it.
When testing with a fresh scratch `MU_DATA_DIR` from the repo root, this pulled the repo's real
`data/downloads` in. Harmless, but set `MU_DATA_DIR` to an isolated dir (and don't run from a cwd with
a `data/` folder) if you want a clean fixture.

To re-drive the core logic without the server: build `:core:jar`, grab its runtime classpath, compile a
tiny Java `main` that calls `UsbBackup.INSTANCE.run(target, lambda)`, and run with `-DMU_DATA_DIR=…`.
(That's how it was verified; the server path is identical — the route just calls `BackupJob.start`.)

---

## 7. Known limitations / open items (Phase 0)

- **No history/resume in the blob.** `BackupImport` includes read marks + bookmarks but not
  `HistoryStore`, and there is **no page-level resume model in the codebase at all** (read state is
  chapter-level URL sets). "Continue reading" position does not survive a backup/restore. Spec §11.7
  flags fixing this for a true "Full Backup" — deferred.
- **exFAT & `ATOMIC_MOVE`.** On some FAT/exFAT setups `ATOMIC_MOVE` may be unsupported; the code falls
  back to a plain replace. Fine for correctness (still no partial final file), just not atomic on those
  volumes. Worth a real-hardware test on the user's actual USB.
- **No scheduling / no auto-on-insert.** Manual only, by decision.
- **No hashing yet.** Phase 0 skips by file size, not hash. The user's future Python/Tkinter
  verify/migrate tool (see §8) will want sha256 — that arrives with Phase 1's `index.json`.

---

## 8. What's next (Phases 1–5) + a captured user request

Per `DYNO-IMPLEMENTATION-SPEC.md`:
- **P1** — the real `.dyno/` format: `manifest.json`, `index.json` (with UUIDv5 IDs + sha256 hashes),
  `series.json`, atomic layout. `DynoFormat/DynoManifest/DynoIndex/DynoIds/DynoExporter` in `core`.
- **P2** — drive detection (`^DYNO-` label) + `DriveRegistry` + Settings → **Portable Drives** page.
- **P3** — import + incremental sync (diff by UUID + hash) + conflict resolution.
- **P4** — Explorer (spec recommends repackaging the React web UI to read the drive).
- **P5** — verify / clone / repair / safe-eject / scheduling.

**Captured later request (≈P4):** the user wants a **small self-contained Python + Tkinter GUI app**
(built and ready to run) that can **view** the drive's data/backups, **verify data via generated
sha256 hashes** (flag missing/corrupt files), and **migrate downloaded data** between drives/machines
with that verification. Treat it as a lightweight *verify/migrate* companion to the web-UI Explorer;
its data source is Phase 1's hash-bearing `index.json`, and its verify logic mirrors Phase 5's
`DynoVerify`. **Consider recording sha256 per file starting in P1** so this tool has hashes to check.

---

## 9. Codebase primer (the pieces Phase 0 relies on)

- `core/.../config/AppConfig.kt` — `dataDir` (override `MU_DATA_DIR`), `downloadsDir`
  (= `dataDir/downloads` unless overridden). Runs the legacy-migration noted in §6.
- `core/.../backup/BackupImport.kt` — `export(Sections): ByteArray` (gz-protobuf, Mihon-compatible);
  `import`/`preview`. `Sections` defaults to library-only.
- `core/.../download/DownloadManager.kt` — `sanitize()` + `destFor()` define the on-disk
  `<series>/<chapter>.cbz` + `cover.<ext>` layout Phase 0 copies verbatim.
- `server/.../DownloadQueue.kt` — the async-job/`@Volatile`-progress pattern `BackupJob` mirrors.
- `server/.../Main.kt` — single `routing { }` block; `ContentNegotiation` JSON; `SettingsStore.get()`
  server-side; the library-update progress route is the polling template.
- `server/webui/src/{api.ts, screens/Settings.tsx}` — the existing backup export/import UI to match.
- `deploy/{Dockerfile, docker-compose.yml, README.md}` — container is UID 1000, `/data` volume,
  image `nake1/manga-utils` (Docker Hub). No fs/mount tooling installed (intentional for Phase 0).

---

## 10. Committing

Nothing is committed. If asked to commit, scope it to the 8 Phase-0 files (2 new + 6 edited listed in
§3) and **exclude `docs/TEST-CHECKLIST.md`** (pre-existing unrelated edit). Suggested message:
`feat(dyno): Phase 0 — manual "Back up to USB" (blob + downloads mirror)`.
Repo default branch is `main`; branch before committing if the user wants a PR.
