#!/usr/bin/env python3
"""
Verify a DYNO drive is internally consistent — a reference for what the real DYNO "Verify" pass
should check, and a self-test for the fixture.

Checks:
  - manifest/index/state/settings JSON parse and have expected schema tags
  - every index chapter file exists, is a valid zip (CBZ), and its bytes hash == fileHash
  - cover + series.json hashes match index coverHash / metadataHash
  - seriesUuid / chapterUuid == UUIDv5(namespace, key)  (identity model holds)

Usage:  python verify_fixture.py [path-to-DYNO-drive]   (default ./DYNO-001)
Exit 0 = all good, 1 = problems (prints them).
"""
import hashlib
import io
import json
import os
import sys
import uuid
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DRIVE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "DYNO-001")

problems = []


def fail(msg):
    problems.append(msg)


def sha256_hex(b):
    return "sha256:" + hashlib.sha256(b).hexdigest()


def canonical_json_bytes(path):
    with open(path, "r", encoding="utf-8") as f:
        obj = json.load(f)
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode("utf-8")


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    dyno = os.path.join(DRIVE, ".dyno")
    if not os.path.isfile(os.path.join(dyno, "manifest.json")):
        print(f"NOT A DYNO DRIVE: missing {dyno}/manifest.json")
        return 1

    manifest = load(os.path.join(dyno, "manifest.json"))
    index = load(os.path.join(dyno, "index.json"))
    load(os.path.join(dyno, "state.json"))
    load(os.path.join(dyno, "settings.json"))

    if not str(manifest.get("schema", "")).startswith("dyno.manifest/"):
        fail("manifest.schema missing/wrong")
    ns = uuid.UUID(manifest["namespace"])

    for s in index["series"]:
        sdir = os.path.join(DRIVE, s["path"])
        # series uuid derivation
        expect_s = str(uuid.uuid5(ns, f'{s["sourceId"]}|{s["mangaUrl"]}'))
        if expect_s != s["seriesUuid"]:
            fail(f'{s["title"]}: seriesUuid {s["seriesUuid"]} != derived {expect_s}')

        # cover hash
        cover = os.path.join(sdir, "cover.png")
        if os.path.isfile(cover):
            with open(cover, "rb") as f:
                if sha256_hex(f.read()) != s["coverHash"]:
                    fail(f'{s["title"]}: coverHash mismatch')
        else:
            fail(f'{s["title"]}: cover.png missing')

        # metadata hash
        sj = os.path.join(sdir, "series.json")
        if os.path.isfile(sj):
            if sha256_hex(canonical_json_bytes(sj)) != s["metadataHash"]:
                fail(f'{s["title"]}: metadataHash mismatch (series.json changed?)')
        else:
            fail(f'{s["title"]}: series.json missing')

        # chapters
        for c in s["chapters"]:
            cbz = os.path.join(sdir, c["file"])
            if not os.path.isfile(cbz):
                fail(f'{s["title"]} / {c["name"]}: file missing ({c["file"]})')
                continue
            with open(cbz, "rb") as f:
                data = f.read()
            if len(data) != c["bytes"]:
                fail(f'{s["title"]} / {c["name"]}: bytes {len(data)} != index {c["bytes"]}')
            if sha256_hex(data) != c["fileHash"]:
                fail(f'{s["title"]} / {c["name"]}: fileHash mismatch (corrupt?)')
            try:
                z = zipfile.ZipFile(io.BytesIO(data))
                pages = [n for n in z.namelist() if n.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".gif"))]
                if len(pages) != c["pages"]:
                    fail(f'{s["title"]} / {c["name"]}: pages {len(pages)} != index {c["pages"]}')
            except zipfile.BadZipFile:
                fail(f'{s["title"]} / {c["name"]}: not a valid CBZ/zip')
            expect_c = str(uuid.uuid5(ns, f'{s["sourceId"]}|{s["mangaUrl"]}|{c["chapterUrl"]}'))
            if expect_c != c["chapterUuid"]:
                fail(f'{s["title"]} / {c["name"]}: chapterUuid != derived')

    n_series = len(index["series"])
    n_ch = sum(len(s["chapters"]) for s in index["series"])
    if problems:
        print(f"FAIL — {len(problems)} problem(s):")
        for p in problems:
            print("  -", p)
        return 1
    print(f"OK — {DRIVE}")
    print(f"  {n_series} series, {n_ch} chapters, all hashes + UUIDs + CBZs consistent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
