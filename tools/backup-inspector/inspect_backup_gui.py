#!/usr/bin/env python3
"""
manga-utils backup inspector — Tkinter GUI.

A basic windowed viewer for .tachibk backups: open a file, see the sections, browse the library in a
table, and read the settings / history / reader-prefs / repos / extensions in tabs. Reuses the
decoder from inspect_backup.py. No third-party deps (tkinter ships with Python).

Run:  python inspect_backup_gui.py [optional-file.tachibk]
"""
import gzip
import json
import os
import sys
import tkinter as tk
from tkinter import ttk, filedialog

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import inspect_backup as bi  # noqa: E402  (decoder: parse/manga/s/first)


def load_backup(path):
    raw = open(path, "rb").read()
    try:
        data = gzip.decompress(raw)
    except OSError:
        data = raw
    top = bi.parse(data)
    mangas = [bi.manga(bi.parse(m)) for m in top.get(1, [])]
    settings = bi.first(top, 900)
    history = bi.first(top, 903)
    prefs = bi.first(top, 904)
    return {
        "raw": len(raw), "data": len(data), "mangas": mangas,
        "settings": bi.s(settings) if settings else "",
        "repos": [bi.s(r) for r in top.get(901, [])],
        "exts": [bi.s(e) for e in top.get(902, [])],
        "history": json.loads(bi.s(history)) if history else [],
        "prefs": json.loads(bi.s(prefs)) if prefs else {},
    }


BG = "#17171a"; FG = "#e8e8ea"; ACC = "#7c6cf0"; CARD = "#1f1f23"; MUTED = "#9a9aa2"


class InspectorGUI:
    def __init__(self, root):
        self.root = root
        root.title("manga-utils · backup inspector")
        root.geometry("880x600")
        root.configure(bg=BG)

        style = ttk.Style()
        try: style.theme_use("clam")
        except tk.TclError: pass
        style.configure("Treeview", background=CARD, fieldbackground=CARD, foreground=FG, rowheight=24, borderwidth=0)
        style.configure("Treeview.Heading", background=BG, foreground=ACC, borderwidth=0)
        style.map("Treeview", background=[("selected", ACC)])
        style.configure("TNotebook", background=BG, borderwidth=0)
        style.configure("TNotebook.Tab", background=CARD, foreground=FG, padding=(12, 6))
        style.map("TNotebook.Tab", background=[("selected", ACC)])

        top = tk.Frame(root, bg=BG)
        top.pack(fill="x", padx=12, pady=(12, 6))
        tk.Button(top, text="Open backup…", command=self.open_file, bg=ACC, fg="white",
                  activebackground=ACC, relief="flat", padx=14, pady=6, font=("Segoe UI", 10, "bold")).pack(side="left")
        self.summary = tk.Label(top, text="No file loaded.", bg=BG, fg=MUTED, font=("Segoe UI", 9), justify="left")
        self.summary.pack(side="left", padx=14)

        nb = ttk.Notebook(root)
        nb.pack(fill="both", expand=True, padx=12, pady=(0, 12))

        # Library tab (table)
        lib = tk.Frame(nb, bg=BG)
        cols = ("title", "source", "chapters", "read", "bm")
        self.tree = ttk.Treeview(lib, columns=cols, show="headings")
        for c, (h, w) in {"title": ("Title", 380), "source": ("Source", 190), "chapters": ("Chapters", 80),
                          "read": ("Read", 80), "bm": ("Bookmarks", 90)}.items():
            self.tree.heading(c, text=h)
            self.tree.column(c, width=w, anchor="w" if c in ("title", "source") else "center")
        vs = ttk.Scrollbar(lib, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=vs.set)
        self.tree.pack(side="left", fill="both", expand=True)
        vs.pack(side="right", fill="y")
        nb.add(lib, text="Library")

        self.txt = {}
        for name in ("Settings", "History", "Reader prefs", "Repos & extensions"):
            frame = tk.Frame(nb, bg=BG)
            t = tk.Text(frame, bg=CARD, fg=FG, insertbackground=FG, relief="flat", wrap="word",
                        font=("Consolas", 10), padx=10, pady=8)
            sb = ttk.Scrollbar(frame, orient="vertical", command=t.yview)
            t.configure(yscrollcommand=sb.set)
            t.pack(side="left", fill="both", expand=True)
            sb.pack(side="right", fill="y")
            nb.add(frame, text=name)
            self.txt[name] = t

        if len(sys.argv) > 1 and os.path.isfile(sys.argv[1]):
            self.show(sys.argv[1])

    def open_file(self):
        path = filedialog.askopenfilename(
            title="Open a manga-utils / Mihon backup",
            filetypes=[("Backup files", "*.tachibk *.gz *.proto.gz"), ("All files", "*.*")],
        )
        if path:
            self.show(path)

    def _set(self, name, content):
        t = self.txt[name]
        t.delete("1.0", "end")
        t.insert("1.0", content or "(none)")

    def show(self, path):
        try:
            b = load_backup(path)
        except Exception as e:
            self.summary.config(text=f"Couldn't read backup: {e}")
            return
        fav = sum(1 for m in b["mangas"] if m["favorite"])
        tot_ch = sum(len(m["chapters"]) for m in b["mangas"])
        tot_read = sum(m["read"] for m in b["mangas"])
        self.root.title(f"backup inspector · {os.path.basename(path)}")
        self.summary.config(text=(
            f"{os.path.basename(path)}  ·  {b['raw']:,} bytes\n"
            f"{len(b['mangas'])} manga ({fav} fav) · {tot_ch} chapters · {tot_read} read · "
            f"{len(b['history'])} history · {len(b['prefs'])} prefs · "
            f"{len(b['repos'])} repos · {len(b['exts'])} extensions · settings: {'yes' if b['settings'] else 'no'}"
        ))

        self.tree.delete(*self.tree.get_children())
        for m in sorted(b["mangas"], key=lambda x: x["title"].lower()):
            self.tree.insert("", "end", values=(
                m["title"] or m["url"], m["source"], len(m["chapters"]),
                f"{m['read']}/{len(m['chapters'])}", m["bookmarks"],
            ))

        # Settings (pretty JSON)
        try:
            self._set("Settings", json.dumps(json.loads(b["settings"]), indent=2) if b["settings"] else "(none)")
        except Exception:
            self._set("Settings", b["settings"])
        # History
        self._set("History", "\n".join(
            f"{h.get('mangaTitle', '?')}  —  {h.get('chapterName', '?')}" for h in b["history"]) or "(none)")
        # Reader prefs
        self._set("Reader prefs", "\n".join(f"{k} = {v}" for k, v in b["prefs"].items()) or "(none)")
        # Repos & extensions
        parts = []
        if b["repos"]: parts.append("Repositories:\n  " + "\n  ".join(b["repos"]))
        if b["exts"]: parts.append("Extensions:\n  " + "\n  ".join(b["exts"]))
        self._set("Repos & extensions", "\n\n".join(parts) or "(none)")


def main():
    root = tk.Tk()
    InspectorGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
