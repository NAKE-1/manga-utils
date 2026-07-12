# Implementation plan — in-app file explorer (+ how it feeds DYNO & the storage layout)

**Status:** planned, not started. Logged 2026-07-12. Nothing here is implemented until picked.

## Framing (three separate concerns — don't conflate them)

1. **File explorer** *(this doc)* — an in-app browser of the data + downloads directories, so you can
   see, verify, download and (later) manage what's actually on disk.
2. **DYNO** *(already spec'd in `dyno md/DYNO-IMPLEMENTATION-SPEC.md`)* — the portable **USB cartridge**
   holding the **complete manga backup: all downloads + metadata + reading state**. The full-downloads
   backup is DYNO's job, not the explorer's and not ZFS's. The explorer is a *building block* for it.
3. **Server storage layout (ZFS / Jellyfin)** — purely *where the data lives on the server* so a Jellyfin
   VM can share the same NVMe. A **deployment** concern (see §4). It is not a backup and does not replace
   DYNO.

The explorer (#1) is worth building on its own AND de-risks DYNO (its browse/verify/copy logic is
reusable by the DYNO export/verify flow).

---

## 1. File explorer — scope & design

**Goal:** browse `dataDir` and `downloadsDir` from the web UI — folders, file sizes, dates — and download
a file. Read-only first; manage (delete/rename) as a later, opt-in step.

### Roots (whitelist)
Only two roots are ever browsable, resolved from `AppConfig`:
- **Downloads** — `AppConfig.downloadsDir` (CBZ/folders + covers)
- **Data** — `AppConfig.dataDir` (library.json, read.json, history.json, bookmarks, settings, the backup
  blob, installed extension `.apk`s, JCEF bits)

A top-level picker chooses which root; navigation stays inside it.

### Backend (new `server/.../FileRoutes.kt` or inline in `Main.kt`)
- `GET /api/files?root=downloads|data&path=<relative>` → directory listing:
  `[{name, isDir, size, mtime}]`, sorted (dirs first, then name). Also returns breadcrumb parts.
- `GET /api/files/download?root=&path=` → streams the file with the right `Content-Type` +
  `Content-Disposition: attachment`.
- *(Later)* `DELETE /api/files?root=&path=` — behind a confirm; never allowed on a root itself.
- **PATH SAFETY (the one hard requirement):** `val abs = root.resolve(rel).normalize(); require(abs.startsWith(root))`
  — reject anything that escapes the root (`../`, absolute paths, symlink-out). Applies even though the
  server is Tailscale-only. No exceptions. Add a unit-style check.

### Frontend (new `screens/Files.tsx`, route, Settings link)
- A root toggle (Downloads / Data), breadcrumb path, and a list: folders (tap to descend) then files with
  size + relative date. A download icon per file.
- Reuses existing list/card styling. Optional: a size rollup per folder (cheap: sum immediate children).
- Link from Settings → "System" (e.g. "Browse files") and/or the Downloads-manager.

### Effort & test guide
- **Effort:** ~M read-only (browse + download); +S for delete.
- **Test:**
  - Open Files → Downloads → descend into a series folder → see chapter folders/CBZs with sizes; download a
    CBZ → it saves and opens in a reader.
  - Switch to Data → see `library.json` etc.; download one → valid JSON.
  - **Traversal attempt:** hand-craft `path=../../etc` (or `..\\..\\`) → server returns 400/403, never a
    file outside the root. (Do this deliberately as the security check.)
  - Watch for: huge directories (a big library) — paginate or cap the listing if a folder has thousands of
    entries; don't stat every page file recursively on a plain listing.

---

## 2. Full metadata export (portable "everything" file) — small companion

Separate from DYNO's on-USB copy: a one-file, restore-anywhere archive of the **data dir** (all JSON +
settings + blob + installed extensions). Small (MBs), portable.
- `POST /api/backup/full` → streams a `.tar.gz` of `dataDir`.
- Complements the existing Mihon-compatible protobuf backup (which is portable *metadata*); this is the
  rawer, exact-files snapshot. Downloads are **not** included here — that's DYNO.
- **Effort:** S–M. Good to build alongside the explorer (shared file-walk code).

---

## 3. How it feeds DYNO

DYNO (per its spec) writes a **complete cartridge**: metadata blob + reading state + a copy of every
downloaded chapter, onto a removable drive, with identity/UUID + incremental sync + a standalone explorer.
The pieces here are reusable inputs:
- The explorer's **file-walk + size/verify** logic → DYNO's "what's on disk / what to copy / verify copied".
- The **full metadata export** (§2) → the metadata half of a DYNO cartridge.
- The path-safe root handling → DYNO's staging.

So build order that de-risks DYNO: **explorer (§1) → full export (§2) → DYNO Phase 1** (which then reuses
both). This matches the deferred DYNO plan — it just gives it foundations first.

---

## 4. Server storage layout (ZFS + Jellyfin coexistence) — deployment note, not a backup

Goal: manga-utils' data + downloads live on the **shared NVMe ZFS dataset**, so a Jellyfin VM can use the
same drive; ZFS snapshots protect the bulk.

- **Env-configurable dirs (already supported):** point `MU_DATA_DIR` and the download-dir override at a
  path on the ZFS dataset; bind-mount that into the manga-utils container. (Confirm both are honoured on
  the server build — data dir override exists; verify the downloads override env when we wire compose.)
- **Layout sketch (Proxmox):**
  - `nvme-pool/manga` dataset → mounted in the manga-utils container as `/data` (data) + `/downloads`.
  - Jellyfin VM/container mounts the same pool (its own dataset or a read path) so both share the NVMe.
  - Boot/OS on a separate boot drive; only the *data* dataset is the valuable, snapshotted one.
- **Snapshots** = the server-side safety net for the terabytes (and cover Jellyfin too). Offsite via
  `zfs send | recv`. This is **infrastructure backup**, orthogonal to DYNO's **portable** backup — keep
  both: ZFS for "my server drive is safe", DYNO for "I can carry my whole library on a stick".
- Ties into the existing **deploy plan** (Dockerize `:server` on Proxmox + FlareSolverr sibling). When we
  do compose, add the dataset bind-mounts + document the Jellyfin-sharing pattern.

**Effort:** no app code for the ZFS part itself — it's compose/host config; the only app touch is making
sure the downloads dir is env-overridable (verify) so it can point at the dataset.

---

## Build order

| Step | What | Size | Notes |
|------|------|------|-------|
| A | File explorer (read-only browse + download) | M | Standalone value; path-safety is the critical bit |
| B | Full metadata export (`.tar.gz` of data dir) | S–M | Shares file-walk with A |
| C | *(deploy)* ZFS dataset + compose bind-mounts + Jellyfin sharing | — | Host/compose config; verify download-dir env |
| D | DYNO Phase 1 | L | Reuses A + B; per `dyno md/` spec |

Do A first (useful immediately, de-risks the rest). B alongside A. C when we ship to Proxmox. D is the big
one, on its own timeline.
