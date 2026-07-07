#!/usr/bin/env python3
"""
manga-utils backup inspector — view what's inside a .tachibk (gzip+protobuf) backup, offline.

No dependencies, no server needed. Understands the Mihon/Tachiyomi library fields plus manga-utils'
native sections (settings 900, repos 901, extensions 902, history 903, reader prefs 904).

Usage:
  python inspect_backup.py <file.tachibk>            # summary + library table
  python inspect_backup.py <file.tachibk> --full     # also dump settings/history/prefs/repos/exts
"""
import gzip
import json
import struct
import sys

# ---- Minimal protobuf wire decoder ---------------------------------------------------------------
def _varint(b, i):
    shift = res = 0
    while True:
        x = b[i]; i += 1
        res |= (x & 0x7F) << shift
        if not (x & 0x80):
            return res, i
        shift += 7

def parse(b):
    """Decode one protobuf message → dict {field_num: [values]} (bytes for len-delimited)."""
    out, i, n = {}, 0, len(b)
    while i < n:
        tag, i = _varint(b, i)
        fn, wt = tag >> 3, tag & 7
        if wt == 0:      val, i = _varint(b, i)
        elif wt == 2:    ln, i = _varint(b, i); val = b[i:i+ln]; i += ln
        elif wt == 5:    val = b[i:i+4]; i += 4
        elif wt == 1:    val = b[i:i+8]; i += 8
        else:            raise ValueError(f"bad wire type {wt} at field {fn}")
        out.setdefault(fn, []).append(val)
    return out

def s(v):   return v.decode("utf-8", "replace") if isinstance(v, (bytes, bytearray)) else str(v)
def f32(v): return struct.unpack("<f", v)[0] if isinstance(v, (bytes, bytearray)) and len(v) == 4 else 0.0
def first(d, k, default=None):
    return d[k][0] if k in d and d[k] else default

# ---- Field maps (must match core/.../backup/BackupImport.kt) --------------------------------------
def chapter(d):
    return {
        "url": s(first(d, 1, b"")), "name": s(first(d, 2, b"")), "scanlator": s(first(d, 3, b"")),
        "read": bool(first(d, 4, 0)), "bookmark": bool(first(d, 5, 0)),
        "number": f32(first(d, 9, b"\0\0\0\0")),
    }

def manga(d):
    chs = [chapter(parse(c)) for c in d.get(16, [])]
    return {
        "source": first(d, 1, 0), "url": s(first(d, 2, b"")), "title": s(first(d, 3, b"")),
        "author": s(first(d, 5, b"")), "status": first(d, 8, 0),
        "genres": [s(g) for g in d.get(7, [])], "favorite": bool(first(d, 100, 1)),
        "chapters": chs, "read": sum(1 for c in chs if c["read"]), "bookmarks": sum(1 for c in chs if c["bookmark"]),
    }

def main():
    if len(sys.argv) < 2:
        print(__doc__); return 1
    path = sys.argv[1]
    full = "--full" in sys.argv[2:]
    raw = open(path, "rb").read()
    try:
        data = gzip.decompress(raw)
    except OSError:
        data = raw  # some exports may be uncompressed protobuf
    top = parse(data)

    mangas = [manga(parse(m)) for m in top.get(1, [])]
    settings = first(top, 900); repos = [s(r) for r in top.get(901, [])]
    exts = [s(e) for e in top.get(902, [])]
    history = first(top, 903); prefs = first(top, 904)

    fav = [m for m in mangas if m["favorite"]]
    tot_ch = sum(len(m["chapters"]) for m in mangas)
    tot_read = sum(m["read"] for m in mangas)

    print(f"== {path}  ({len(raw):,} bytes gz / {len(data):,} decoded) ==\n")
    print("Sections in this backup:")
    print(f"  Library ......... {len(mangas)} manga ({len(fav)} favorited), {tot_ch} chapters, {tot_read} read")
    print(f"  App settings .... {'yes' if settings else 'no'}")
    print(f"  Repositories .... {len(repos)}")
    print(f"  Extensions ...... {len(exts)}")
    hist = json.loads(s(history)) if history else []
    pref = json.loads(s(prefs)) if prefs else {}
    print(f"  History ......... {len(hist)} continue-reading entrie(s)")
    print(f"  Reader prefs .... {len(pref)} key(s)")

    if mangas:
        print("\nLibrary:")
        print(f"  {'TITLE':<44} {'SOURCE':>20} {'CH':>4} {'READ':>5} {'BM':>3}")
        for m in sorted(mangas, key=lambda x: x["title"].lower()):
            t = (m["title"] or m["url"])[:43]
            print(f"  {t:<44} {m['source']:>20} {len(m['chapters']):>4} {m['read']:>5} {m['bookmarks']:>3}")

    if full:
        if settings:
            print("\n-- App settings --")
            try: print(json.dumps(json.loads(s(settings)), indent=2))
            except Exception: print(s(settings))
        if repos: print("\n-- Repositories --\n  " + "\n  ".join(repos))
        if exts:  print("\n-- Extensions --\n  " + "\n  ".join(exts))
        if pref:  print("\n-- Reader / display prefs --\n  " + "\n  ".join(f"{k} = {v}" for k, v in pref.items()))
        if hist:
            print("\n-- Continue-reading history --")
            for h in hist[:40]:
                print(f"  {h.get('mangaTitle','?')} — {h.get('chapterName','?')}")
            if len(hist) > 40: print(f"  …and {len(hist)-40} more")
    else:
        print("\n(run with --full to dump settings, prefs, history, repos and extensions)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
