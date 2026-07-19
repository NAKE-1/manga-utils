# DYNO docs — start here

**DYNO** = a portable manga-library USB "cartridge" format for `manga-utils`, plus the machinery to
export → detect → import → sync libraries across machines, and a bundled Explorer to browse a drive
with no install. It's a **multi-phase** project (P0–P5); each phase is independently useful.

If you're a new engineer/Claude picking this up, **read the docs in this order**:

## 1. Read these two first (self-sufficient for all phases)
- **[`DYNO-IMPLEMENTATION-SPEC.md`](DYNO-IMPLEMENTATION-SPEC.md)** — the build-ready, phased plan for
  **P0–P5**, grounded in the actual codebase. The master roadmap for everything not-yet-built.
- **[`DYNO-PHASE0-HANDOFF.md`](DYNO-PHASE0-HANDOFF.md)** — what **Phase 0 actually built** (manual
  "Back up to USB"), the locked decisions, verification, gotchas, and what's left. **This doc wins
  where it differs from the spec.**

## 2. Context / intent
- **[`dyno init.md`](dyno%20init.md)** — the original product vision (what DYNO is *for*).

## 3. Code the docs point at (pre-load to skip a search pass)
Phase 0 (built):
- `core/src/main/kotlin/mangautils/core/backup/UsbBackup.kt` — the backup engine (new).
- `server/src/main/kotlin/mangautils/server/BackupJob.kt` — the progress job (new).

Reused / foundational for later phases:
- `core/src/main/kotlin/mangautils/core/backup/BackupImport.kt` — gz-protobuf backup (P1+ builds on it).
- `core/src/main/kotlin/mangautils/core/config/AppConfig.kt` — data/download paths (+ legacy-migration gotcha).
- `core/src/main/kotlin/mangautils/core/download/DownloadManager.kt` — `sanitize()`/`destFor()` on-disk layout.

---

## Phase status at a glance
| Phase | What | Status |
|---|---|---|
| **P0** | Manual USB backup: `.tachibk.gz` blob + downloads mirror | **BUILT** (uncommitted), verified |
| P1 | `.dyno/` format: manifest + `index.json` (UUIDv5 + sha256) + `series.json` | not started |
| P2 | Drive detection (`^DYNO-`) + Portable Drives page | not started |
| P3 | Import + incremental sync + conflict resolution | not started |
| P4 | Explorer (repackaged web UI) — see Python/Tkinter note below | not started |
| P5 | Verify / clone / repair / safe-eject / scheduling | not started |

**Captured user request (≈P4):** a small self-contained **Python + Tkinter** GUI to view drive
data/backups, **verify data via generated sha256 hashes**, and **migrate downloads** with that
verification — a lightweight verify/migrate companion to the web-UI Explorer. Consider recording
sha256 per file starting in **P1** so this tool has hashes to check against. Details in the handoff §8.

> Note: the plan file `~/.claude/plans/start-phase-0-i-temporal-lerdorf.md` (outside the repo) has the
> full P0 design + later-phase notes, if available.
