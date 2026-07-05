# Test checklist (web UI)

Things to verify after this batch of work. Restart the web server (`w`) and reload the UI first.

## Source preferences (⚙ per source)
- [ ] **Toggle** flips **instantly** (no wait-for-server jump), stays flipped after reopening the gear.
- [ ] **Dropdown** (e.g. content rating / thumbnail quality) persists on reopen.
- [ ] **Multi-select** (checkbox set, e.g. demographics/types) persists on reopen.
- [ ] **Text** pref: type → Save → reopen shows the saved value.
- [ ] A pref that changes results (e.g. content rating) actually changes what browse returns.

## FlareSolverr toasts
- [ ] A solve shows **one** toast that goes `FS · host solving…` → `FS · host cleared` (updates in place, **no** second toast).
- [ ] A failing solve (e.g. mangadot) shows `FS · host failed` (red).
- [ ] Rapid repeat solves for the same host don't stack a pile of toasts.

## Extensions
- [ ] **Check for updates** button reports `N updates available` / `All extensions up to date`.
- [ ] **Update all** shows `Updating N…` then `Updates complete`.
- [ ] Installing an extension still toasts `Installed X · N sources`.
- [ ] The **jb** JetBrains badge shows on WebView extensions (comix, allanime) in the installed list.

## Backup (Settings → Backup)
- [ ] **Export…** opens the section picker (Library / App settings / Extension repos / Installed extensions).
- [ ] Exporting downloads a `.tachibk`.
- [ ] **Choose backup to restore** → preview shows manga count **and** notes settings/repos/extensions present.
- [ ] **Import** restores and reports `restored settings · N repos · M extensions`.
- [ ] Round-trip: export (all sections) → import the same file → library/read/bookmarks intact, repos re-added, settings applied.
- [ ] A plain **Mihon `.tachibk`** still previews + imports the library (back-compat).

## WebView (JetBrains JCEF)
- [ ] comix: search → detail → **read a chapter end-to-end** (pages render) → next chapter **preloads**.
- [ ] The **jb** badge shows in the source picker and on a WebView source's Detail line.
- [ ] Restart is fast — console shows `CEF runtime ready` with **no** re-download (native cached in `bin/jcef`).
- [ ] First WebView hit right after start fail-fasts once ("downloading/starting Chromium"), then works on retry.
