#!/usr/bin/env python3
"""
Generate a realistic, internally-consistent mock DYNO drive for testing a DYNO implementation.

Everything is real and self-consistent per DYNO-IMPLEMENTATION-SPEC.md:
  - chapters are real .cbz zips of tiny PNG pages
  - index.json fileHash / coverHash / metadataHash are the real sha256 of the actual bytes
  - seriesUuid / chapterUuid are real UUIDv5(DYNO_NAMESPACE, key) matching the spec's identity model

Re-run any time:  python "generate_fixture.py"
Output:           ./DYNO-001/
"""
import base64
import gzip
import hashlib
import io
import json
import os
import shutil
import uuid
import zipfile

# ---- Constants that a real DYNO implementation MUST match ----------------------------------------
# The fixed namespace for deriving stable series/chapter UUIDs. The server code must use this exact
# value so UUIDs computed on any machine agree.
DYNO_NAMESPACE = uuid.UUID("d1c0d1c0-0000-5000-a000-000000000001")
DRIVE_UUID = "5fddde2b-f7e0-4ea5-9eaa-a1b2c3d4e5f6"
OWNER = "nakanoduck"
CREATED_AT = 1720051200000  # 2024-07-04, fixed so output is deterministic

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "DYNO-001")

# A valid 1x1 PNG (transparent) — real image bytes so magic-byte checks / readers accept it.
PNG_1x1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)


def sha256_hex(b: bytes) -> str:
    return "sha256:" + hashlib.sha256(b).hexdigest()


def series_uuid(source_id: int, manga_url: str) -> str:
    return str(uuid.uuid5(DYNO_NAMESPACE, f"{source_id}|{manga_url}"))


def chapter_uuid(source_id: int, manga_url: str, chapter_url: str) -> str:
    return str(uuid.uuid5(DYNO_NAMESPACE, f"{source_id}|{manga_url}|{chapter_url}"))


def make_cbz(pages: int) -> bytes:
    """A real CBZ: a zip of `pages` numbered PNGs."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for i in range(pages):
            z.writestr(f"{i+1:03d}.png", PNG_1x1)
    return buf.getvalue()


def canonical_json(obj) -> bytes:
    """Stable bytes for hashing metadata (sorted keys, no whitespace)."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode("utf-8")


def write(path: str, data: bytes):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def write_json(path: str, obj):
    write(path, json.dumps(obj, indent=2).encode("utf-8"))


# ---- Series definitions (mirrors manga-utils sourceId/mangaUrl identity) --------------------------
SERIES = [
    {
        "title": "Kaiju Girl Caramelise",
        "sourceId": 2327480808438768017,   # atsu.moe (matches the real test source in logs)
        "mangaUrl": "/manga/OGQkW",
        "author": "Spica Aoki",
        "artist": "Spica Aoki",
        "description": "An awkward high-schooler transforms into a kaiju when her heart races.",
        "genres": ["Romance", "Comedy", "Supernatural", "Shounen"],
        "status": "ongoing",
        "language": "en",
        "readingDirection": "ltr",
        "chapters": [
            {"url": "/manga/OGQkW/JwpQfSNN", "name": "Chapter 11", "number": 11.0,
             "pages": 4, "read": True, "bookmarked": False, "resumePage": 0},
            {"url": "/manga/OGQkW/wPdECWgo", "name": "Chapter 12", "number": 12.0,
             "pages": 3, "read": False, "bookmarked": True, "resumePage": 1},
        ],
    },
    {
        "title": "Frieren Beyond Journeys End",
        "sourceId": 8861274191478178487,
        "mangaUrl": "/series/frieren",
        "author": "Kanehito Yamada",
        "artist": "Tsukasa Abe",
        "description": "An elf mage reflects on her journey long after the hero's party disbands.",
        "genres": ["Adventure", "Drama", "Fantasy"],
        "status": "ongoing",
        "language": "en",
        "readingDirection": "ltr",
        "chapters": [
            {"url": "/series/frieren/ch-1", "name": "Chapter 1", "number": 1.0,
             "pages": 2, "read": True, "bookmarked": False, "resumePage": 0},
        ],
    },
]


def build():
    if os.path.isdir(ROOT):
        shutil.rmtree(ROOT)

    index_series = []
    generation = 42

    for s in SERIES:
        s_uuid = series_uuid(s["sourceId"], s["mangaUrl"])
        folder_name = s["title"]  # already filesystem-safe here (real code uses DownloadManager.sanitize)
        rel_dir = f"Library/{folder_name}"
        abs_dir = os.path.join(ROOT, rel_dir)

        # cover
        cover_bytes = PNG_1x1
        write(os.path.join(abs_dir, "cover.png"), cover_bytes)

        # chapters -> real cbz + hashes
        idx_chaps = []
        series_chaps_meta = []
        for c in s["chapters"]:
            cbz = make_cbz(c["pages"])
            cbz_name = f"{c['name']}.cbz"
            write(os.path.join(abs_dir, cbz_name), cbz)
            c_uuid = chapter_uuid(s["sourceId"], s["mangaUrl"], c["url"])
            idx_chaps.append({
                "chapterUuid": c_uuid,
                "chapterUrl": c["url"],
                "name": c["name"],
                "number": c["number"],
                "file": cbz_name,
                "pages": c["pages"],
                "bytes": len(cbz),
                "fileHash": sha256_hex(cbz),
                "read": c["read"],
                "bookmarked": c["bookmarked"],
            })
            series_chaps_meta.append({
                "chapterUuid": c_uuid, "url": c["url"], "name": c["name"], "number": c["number"],
                "pages": c["pages"], "bytes": len(cbz), "read": c["read"],
                "bookmarked": c["bookmarked"], "resumePage": c["resumePage"],
            })

        # series.json (offline-authoritative metadata)
        series_meta = {
            "schema": "dyno.series/1",
            "seriesUuid": s_uuid,
            "sourceId": s["sourceId"],
            "mangaUrl": s["mangaUrl"],
            "title": s["title"],
            "altTitles": [],
            "description": s["description"],
            "author": s["author"],
            "artist": s["artist"],
            "genres": s["genres"],
            "tags": [],
            "status": s["status"],
            "language": s["language"],
            "readingDirection": s["readingDirection"],
            "addedAt": CREATED_AT,
            "modifiedAt": CREATED_AT,
            "chapters": series_chaps_meta,
        }
        meta_bytes = canonical_json(series_meta)
        write_json(os.path.join(abs_dir, "series.json"), series_meta)

        index_series.append({
            "seriesUuid": s_uuid,
            "sourceId": s["sourceId"],
            "mangaUrl": s["mangaUrl"],
            "title": s["title"],
            "path": rel_dir,
            "coverHash": sha256_hex(cover_bytes),
            "metadataHash": sha256_hex(meta_bytes),
            "chapters": idx_chaps,
        })

    # ---- .dyno/ ----------------------------------------------------------------------------------
    manifest = {
        "schema": "dyno.manifest/1",
        "driveUuid": DRIVE_UUID,
        "label": "DYNO-001",
        "owner": OWNER,
        "createdAt": CREATED_AT,
        "manifestVersion": 1,
        "libraryVersion": 1,
        "explorerVersion": None,
        "compatibleServer": ">=0.1.0",
        "capabilities": ["library", "metadata", "covers", "progress", "sync"],
        "filesystem": "exFAT",
        "integrity": {"algo": "sha256"},
        "namespace": str(DYNO_NAMESPACE),
    }
    write_json(os.path.join(ROOT, ".dyno", "manifest.json"), manifest)

    index = {
        "schema": "dyno.index/1",
        "generation": generation,
        "updatedAt": CREATED_AT,
        "series": index_series,
    }
    write_json(os.path.join(ROOT, ".dyno", "index.json"), index)

    state = {
        "schema": "dyno.state/1", "lastConnected": CREATED_AT, "lastSync": CREATED_AT,
        "lastImport": 0, "lastExport": CREATED_AT, "currentServer": "test-fixture",
        "syncGeneration": generation, "verified": True, "lastMounted": CREATED_AT,
    }
    write_json(os.path.join(ROOT, ".dyno", "state.json"), state)

    settings = {
        "schema": "dyno.settings/1", "autoImport": False, "autoExport": False, "autoSync": False,
        "compression": False, "encryption": False, "thumbnailGeneration": True, "safeEject": True,
        "preferredExportProfile": "OFFLINE_READER", "preferredImportMode": "MERGE",
        "conflictResolution": "PROMPT", "explorerTheme": "dark", "explorerLanguage": "en",
    }
    write_json(os.path.join(ROOT, ".dyno", "settings.json"), settings)

    # logs (newline-delimited JSON)
    log_lines = [
        {"ts": CREATED_AT, "event": "DRIVE_INITIALIZED", "detail": "fixture generated"},
        {"ts": CREATED_AT, "event": "EXPORT_FINISHED", "detail": "profile=OFFLINE_READER series=2 chapters=3"},
        {"ts": CREATED_AT, "event": "VERIFY_COMPLETED", "detail": "ok=3 corrupt=0"},
    ]
    write(os.path.join(ROOT, ".dyno", "logs", "20240704.log"),
          ("\n".join(json.dumps(l) for l in log_lines) + "\n").encode("utf-8"))

    # ---- Backups/ : a real gz blob standing in for BackupImport.export() (content is a placeholder note)
    note = b"PLACEHOLDER: a real DYNO Full Backup here is BackupImport.export(all) = gzipped Tachiyomi protobuf."
    write(os.path.join(ROOT, "Backups", "backup-20240704T000000.tachibk.gz"), gzip.compress(note))

    # ---- Explorer/ placeholder -------------------------------------------------------------------
    for plat in ("Windows", "Linux", "macOS"):
        write(os.path.join(ROOT, "Explorer", plat, "README.txt"),
              (f"DYNO Explorer ({plat}) goes here. "
               "Recommended impl: a portable manga-utils :server build + JRE that serves the web UI "
               "with MU_DATA_DIR pointed at this drive and downloadDir=Library/. See "
               "DYNO-IMPLEMENTATION-SPEC.md section 8.\n").encode("utf-8"))

    # Temp + README
    os.makedirs(os.path.join(ROOT, "Temp"), exist_ok=True)
    write(os.path.join(ROOT, "Temp", ".gitkeep"), b"")
    write(os.path.join(ROOT, "README.txt"),
          b"This is a DYNO manga drive (test fixture). Open Explorer/ to browse the library.\n"
          b"Structure and schemas: see dyno md/DYNO-IMPLEMENTATION-SPEC.md\n")

    # ---- Summary ---------------------------------------------------------------------------------
    total_ch = sum(len(s["chapters"]) for s in SERIES)
    print(f"Built {ROOT}")
    print(f"  DYNO_NAMESPACE = {DYNO_NAMESPACE}")
    print(f"  series = {len(SERIES)}, chapters = {total_ch}")
    for s in index_series:
        print(f"  - {s['title']}  seriesUuid={s['seriesUuid']}")
        for c in s["chapters"]:
            print(f"      {c['name']}: {c['pages']} pages, {c['bytes']} B, {c['fileHash'][:23]}…")


if __name__ == "__main__":
    build()
