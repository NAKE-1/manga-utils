# DYNO test suite

A **mock DYNO drive** for developing/testing a DYNO implementation without a real USB. Everything is
real and internally consistent per `../DYNO-IMPLEMENTATION-SPEC.md`.

## Contents
- **`DYNO-001/`** — a complete fake DYNO drive: `.dyno/` (manifest, index, state, settings, logs),
  `Library/` with 2 series and 3 **real `.cbz`** chapters (zips of tiny PNG pages), covers,
  `series.json`, a gz `Backups/` blob, `Explorer/` placeholders, `Temp/`, `README.txt`.
- **`generate_fixture.py`** — rebuilds `DYNO-001/` from scratch (deterministic). Re-run after schema
  changes: `python generate_fixture.py`.
- **`verify_fixture.py`** — checks the drive is consistent (file hashes == `index.json`, UUIDs ==
  UUIDv5 derivation, CBZs are valid zips). Doubles as a reference for DYNO's **Verify** pass:
  `python verify_fixture.py [drive-path]` (exit 0 = ok).

## Key constant (the real implementation MUST match)
```
DYNO_NAMESPACE = d1c0d1c0-0000-5000-a000-000000000001
seriesUuid  = UUIDv5(DYNO_NAMESPACE, "<sourceId>|<mangaUrl>")
chapterUuid = UUIDv5(DYNO_NAMESPACE, "<sourceId>|<mangaUrl>|<chapterUrl>")
fileHash / coverHash / metadataHash = "sha256:" + sha256(bytes)   (metadata hashed as canonical JSON)
```
If your code uses a different namespace, the fixture's UUIDs won't match — keep them in sync.

## What it's good for
- **Detection/registration (Phase 2):** point `DriveScanner` at this folder; it should see `DYNO-001`,
  read the manifest, and register 2 series / 3 chapters.
- **Import/sync (Phase 3):** import this drive into a server, then re-import to prove "no changes";
  bump a chapter's `read`/`resumePage` in `series.json` + regenerate to test diffing.
- **Reader (Phase 4):** the CBZs open with any comic reader / `LocalChapterReader`.
- **Verify (Phase 5):** corrupt a `.cbz` (append a byte) and run `verify_fixture.py` — it flags the
  `fileHash` mismatch, exactly what DYNO Verify must catch.

Sample data uses the real atsu.moe `sourceId` (2327480808438768017) + `/manga/OGQkW` so it lines up
with the manga-utils identity model (`sourceId` + `mangaUrl`).
