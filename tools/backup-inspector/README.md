# Backup inspector

A tiny **standalone** viewer for manga-utils / Mihon / Tachiyomi `.tachibk` backups. No dependencies,
no server — decodes the gzip+protobuf and shows what's inside. Comes in two forms:

**GUI (window):**
- `inspect-backup-gui.bat` — double-click to open the window (then *Open backup…*), or drag a
  `.tachibk` onto it. Library shows as a table; settings / history / reader prefs / repos+extensions
  in tabs.
- or `python inspect_backup_gui.py [file.tachibk]`

**CLI (terminal):**
- `inspect-backup.bat` — drag a `.tachibk` onto it (or double-click and paste a path).
- or:
```bash
python inspect_backup.py my-backup.tachibk          # summary + library table
python inspect_backup.py my-backup.tachibk --full    # also dump settings, prefs, history, repos, extensions
```

Shows:
- **Sections present** — library (manga / chapters / read counts), app settings, repositories,
  extensions, continue-reading history, reader/display prefs.
- **Library table** — title, source id, chapter count, read count, bookmarks per series.
- With `--full` — the settings JSON, repo URLs, extension packages, reader prefs, and history entries.

Field numbers mirror `core/src/main/kotlin/mangautils/core/backup/BackupImport.kt` (Mihon library
fields + manga-utils native sections: settings 900, repos 901, extensions 902, history 903,
reader prefs 904). If that schema changes, update the maps in `inspect_backup.py`.
