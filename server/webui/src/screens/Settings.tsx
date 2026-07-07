import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, Source, SettingsInfo, DiagResult, DevStats, LibraryEntry, VersionInfo, BackupJob, BackupResult } from '../api'
import { SourcePicker } from '../components/SourcePicker'
import { ConfirmDialog, ConfirmSpec } from '../components/ConfirmDialog'

// Browser reader/display prefs live in localStorage (per device). Gather them into the backup, and
// apply them back on restore, so a restore carries your reader setup — not just server-side state.
function gatherClientPrefs(): string {
  const out: Record<string, string> = {}
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i)
    if (k && /^(reader|dev|search|browse)\./.test(k)) { const v = localStorage.getItem(k); if (v != null) out[k] = v }
  }
  return JSON.stringify(out)
}
function applyClientPrefs(jsonStr?: string | null) {
  if (!jsonStr) return
  try { const o = JSON.parse(jsonStr) as Record<string, string>; for (const [k, v] of Object.entries(o)) localStorage.setItem(k, String(v)) } catch { /* ignore bad prefs */ }
}

const fmtUptime = (ms: number) => {
  const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  return d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : `${m}m ${s % 60}s`
}

// DYNO (USB backup) is hidden until the portable-library work is ready. Flip to true to restore it.
const SHOW_USB_BACKUP = false

export function Settings() {
  const nav = useNavigate()
  const [info, setInfo] = useState<SettingsInfo | null>(null)
  const [dir, setDir] = useState('')
  const [savingDir, setSavingDir] = useState(false)
  const [dirMsg, setDirMsg] = useState<{ text: string; err: boolean } | null>(null)
  const [sources, setSources] = useState<Source[]>([])
  const [diagSource, setDiagSource] = useState(localStorage.getItem('browse.source') || '')
  const [diag, setDiag] = useState<DiagResult | null>(null)
  const [diagRunning, setDiagRunning] = useState(false)
  const [languages, setLanguages] = useState<string[]>([])
  const [clearing, setClearing] = useState(false)
  const [clearMsg, setClearMsg] = useState('')
  const [clearingNew, setClearingNew] = useState(false)
  const [clearNewMsg, setClearNewMsg] = useState('')
  const [confirm, setConfirm] = useState<ConfirmSpec | null>(null)
  const [library, setLibrary] = useState<LibraryEntry[]>([])
  const [simManga, setSimManga] = useState('')
  const [simMsg, setSimMsg] = useState('')
  const [simRunning, setSimRunning] = useState(false)
  const [net, setNet] = useState<{ pingMs: number; downMbps: number; upMbps: number } | null>(null)
  const [netRunning, setNetRunning] = useState(false)
  const [stats, setStats] = useState<DevStats | null>(null)
  const [about, setAbout] = useState<VersionInfo | null>(null)
  const [showChangelog, setShowChangelog] = useState(false)
  const [devContinueRemove, setDevContinueRemove] = useState(localStorage.getItem('dev.continueRemove') === '1')
  const [importing, setImporting] = useState(false)
  const [importMsg, setImportMsg] = useState<{ text: string; err: boolean } | null>(null)
  const [importProg, setImportProg] = useState<{ phase: string; done: number; total: number; current: string } | null>(null)
  const [preview, setPreview] = useState<{ total: number; manga: { title: string; source: string; chapters: number; read: number; inLibrary: boolean }[]; hasSettings?: boolean; repos?: number; extensions?: number } | null>(null)
  const backupInput = useRef<HTMLInputElement>(null)
  const pendingBackup = useRef<ArrayBuffer | null>(null)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportSel, setExportSel] = useState<Record<string, boolean>>({ library: true, settings: true, repos: true, extensions: false })
  async function runExport() {
    const inc = Object.entries(exportSel).filter(([, v]) => v).map(([k]) => k)
    if (!inc.length) return
    // Fold browser reader/display prefs into the backup when settings are included.
    const prefs = inc.includes('settings') ? gatherClientPrefs() : null
    const blob = await api.exportBackup(inc, prefs).catch(() => null)
    if (!blob) return
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob); a.download = 'manga-utils.tachibk'; a.click()
    URL.revokeObjectURL(a.href)
    setExportOpen(false)
  }
  const [fsUrl, setFsUrl] = useState('')
  const [fsTest, setFsTest] = useState<{ ok: boolean; version?: string; error?: string } | null>(null)
  const [fsTesting, setFsTesting] = useState(false)
  const [usbDir, setUsbDir] = useState('')
  const [usbJob, setUsbJob] = useState<BackupJob | null>(null)
  const [usbMsg, setUsbMsg] = useState<{ text: string; err: boolean } | null>(null)

  function toggleDevContinueRemove() {
    const v = !devContinueRemove
    setDevContinueRemove(v)
    localStorage.setItem('dev.continueRemove', v ? '1' : '0')
  }

  useEffect(() => {
    api.getSettings().then((s) => { setInfo(s); setDir(s.downloadDir || ''); setFsUrl(s.flareSolverrUrl || ''); setUsbDir(s.usbBackupDir || '') }).catch(() => {})
    api.sources().then((s) => { setSources(s); setDiagSource((c) => (c && s.some((x) => x.id === c)) || !s.length ? c : s[0].id) }).catch(() => {})
    api.version().then(setAbout).catch(() => {})
    api.languages().then(setLanguages).catch(() => {})
    api.library().then(setLibrary).catch(() => {})
  }, [])

  async function toggleAutoUpdate() {
    if (!info) return
    const s = await api.saveSettings({ autoUpdate: !info.autoUpdate }).catch(() => null)
    if (s) setInfo(s)
  }
  async function setAutoHours(n: number) {
    const s = await api.saveSettings({ autoUpdateHours: Math.max(1, Math.min(168, n)) }).catch(() => null)
    if (s) setInfo(s)
  }
  // Choosing a file only PREVIEWS it — nothing changes until you confirm.
  async function onBackupFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setImporting(true); setImportMsg(null); setPreview(null)
    try {
      const buf = await file.arrayBuffer()
      pendingBackup.current = buf
      setPreview(await api.backupPreview(buf))
    } catch (err) {
      setImportMsg({ text: err instanceof Error ? err.message : 'Couldn’t read that backup', err: true })
    } finally { setImporting(false) }
  }
  async function doImport() {
    if (!pendingBackup.current) return
    setImporting(true); setImportMsg(null); setImportProg({ phase: 'Starting…', done: 0, total: 0, current: '' })
    try {
      await api.importStart(pendingBackup.current) // kicks off the background restore
      let r: BackupResult | null = null
      for (;;) { // poll live progress until the job finishes
        await new Promise((res) => setTimeout(res, 350))
        const j = await api.importProgress().catch(() => null)
        if (!j) continue
        setImportProg({ phase: j.phase, done: j.done, total: j.total, current: j.current })
        if (j.state === 'done') { r = j.result ?? null; break }
        if (j.state === 'failed') throw new Error(j.error || 'Import failed')
      }
      applyClientPrefs(r?.clientPrefsJson)
      const extras = [
        r?.settingsRestored ? 'settings' : '',
        r?.historyRestored ? `${r.historyRestored} history` : '',
        r?.clientPrefsJson ? 'reader prefs' : '',
        r?.reposAdded ? `${r.reposAdded} repo${r.reposAdded === 1 ? '' : 's'}` : '',
        r?.extensionsInstalled ? `${r.extensionsInstalled} extension${r.extensionsInstalled === 1 ? '' : 's'}` : '',
      ].filter(Boolean).join(' · ')
      setImportMsg({ text: `Imported ${r?.imported ?? 0} manga${r?.skipped ? ` · ${r.skipped} skipped` : ''}${extras ? ` · restored ${extras}` : ''}${r?.extensionsFailed ? ` · ${r.extensionsFailed} ext failed` : ''}.`, err: false })
      setPreview(null); pendingBackup.current = null
    } catch (err) {
      setImportMsg({ text: err instanceof Error ? err.message : 'Import failed', err: true })
    } finally { setImporting(false); setImportProg(null) }
  }
  function cancelImport() { setPreview(null); pendingBackup.current = null }
  // Poll the USB-backup job ~1s while it's running.
  useEffect(() => {
    if (!usbJob?.running) return
    const t = setInterval(() => {
      api.usbBackupProgress().then((j) => {
        setUsbJob(j)
        if (!j.running) {
          if (j.state === 'done') setUsbMsg({ text: `Backup complete · ${j.filesDone - j.filesSkipped} copied · ${j.filesSkipped} up to date · ${j.blobName}`, err: false })
          else if (j.state === 'failed') setUsbMsg({ text: j.error || 'Backup failed', err: true })
        }
      }).catch(() => {})
    }, 1000)
    return () => clearInterval(t)
  }, [usbJob?.running])
  async function saveUsbDir() {
    const s = await api.saveSettings({ usbBackupDir: usbDir.trim() }).catch(() => null)
    if (s) { setInfo(s); setUsbDir(s.usbBackupDir || '') }
  }
  async function startUsbBackup() {
    setUsbMsg(null)
    try {
      const j = await api.backupToUsb()
      setUsbJob(j)
    } catch (err) {
      setUsbMsg({ text: err instanceof Error ? err.message : 'Backup failed to start', err: true })
    }
  }
  async function toggleFlareSolverr() {
    if (!info) return
    const s = await api.saveSettings({ flareSolverrEnabled: !info.flareSolverrEnabled }).catch(() => null)
    if (s) setInfo(s)
  }
  async function saveFsUrl() {
    const s = await api.saveSettings({ flareSolverrUrl: fsUrl.trim() }).catch(() => null)
    if (s) setInfo(s)
  }
  async function testFs() {
    setFsTesting(true); setFsTest(null)
    const r = await api.flaresolverrTest(fsUrl.trim() || undefined).catch(() => ({ ok: false, error: 'Request failed' }))
    setFsTesting(false)
    // Auto-discovery (blank field): if the server found a working endpoint, fill it in + save it.
    if (r.ok && r.url && r.url !== fsUrl.trim()) {
      setFsUrl(r.url)
      const s = await api.saveSettings({ flareSolverrUrl: r.url }).catch(() => null)
      if (s) setInfo(s)
    }
    setFsTest(r)
  }
  async function setFsTtl(n: number) {
    const s = await api.saveSettings({ flareSolverrSessionTtlMinutes: Math.max(1, Math.min(1440, n)) }).catch(() => null)
    if (s) setInfo(s)
  }
  async function toggleAutoDownload() {
    if (!info) return
    const s = await api.saveSettings({ autoDownloadNew: !info.autoDownloadNew }).catch(() => null)
    if (s) setInfo(s)
  }
  async function simulate() {
    const i = simManga.indexOf('|'); if (i < 0) return
    const sid = simManga.slice(0, i), url = simManga.slice(i + 1)
    setSimRunning(true); setSimMsg('')
    const r = await api.simulateUpdate(sid, url).catch(() => null)
    setSimRunning(false)
    if (!r) setSimMsg('Failed')
    else if (r.newChapters < 0) setSimMsg('Open the manga once first (no chapters known yet)')
    else setSimMsg(`${r.title}: ${r.newChapters} new chapter${r.newChapters === 1 ? '' : 's'}${r.autoDownloaded ? ' · auto-downloading' : ''}`)
  }

  // Live server stats — poll while the Settings page is open.
  useEffect(() => {
    let alive = true
    const tick = () => {
      if (document.hidden) return // don't poll a backgrounded tab
      api.devStats().then((s) => { if (alive) setStats(s) }).catch(() => {})
      api.sources().then((s) => { if (alive) setSources(s) }).catch(() => {}) // keep source health fresh
    }
    tick()
    const t = setInterval(tick, 6000) // was 2s — that buried the server log in /api/sources spam
    return () => { alive = false; clearInterval(t) }
  }, [])

  async function toggleLang(code: string) {
    if (!info) return
    const set = new Set(info.visibleLanguages)
    set.has(code) ? set.delete(code) : set.add(code)
    const s = await api.saveSettings({ visibleLanguages: [...set] }).catch(() => null)
    if (s) setInfo(s)
  }

  async function saveDir() {
    setSavingDir(true); setDirMsg(null)
    try {
      const s = await api.saveSettings({ downloadDir: dir.trim() || null })
      setInfo(s); setDir(s.downloadDir || ''); setDirMsg({ text: 'Saved', err: false })
    } catch (e) {
      setDirMsg({ text: e instanceof Error ? e.message : 'Save failed', err: true })
    } finally { setSavingDir(false) }
  }

  async function toggleCbz() {
    if (!info) return
    const s = await api.saveSettings({ downloadAsCbz: !info.downloadAsCbz }).catch(() => null)
    if (s) setInfo(s)
  }
  async function setParallel(n: number) {
    const s = await api.saveSettings({ parallelDownloads: n }).catch(() => null)
    if (s) setInfo(s)
  }
  async function togglePerSource() {
    if (!info) return
    const s = await api.saveSettings({ perSourceParallel: !info.perSourceParallel }).catch(() => null)
    if (s) setInfo(s)
  }
  function clearContinue() {
    setConfirm({
      title: 'Clear continue reading?', message: 'Remove every item from Continue reading (your reading history)?',
      confirmLabel: 'Clear', danger: true, onCancel: () => setConfirm(null),
      onConfirm: async () => {
        setConfirm(null); setClearing(true); setClearMsg('')
        await api.clearHistory().catch(() => {})
        setClearing(false); setClearMsg('Cleared')
      },
    })
  }
  function clearNewBadges() {
    setConfirm({
      title: 'Clear new-chapter badges?', message: 'Remove the “!” new-chapter indicator from every series?',
      confirmLabel: 'Clear', danger: true, onCancel: () => setConfirm(null),
      onConfirm: async () => {
        setConfirm(null); setClearingNew(true); setClearNewMsg('')
        const r = await api.clearNewChapters().catch(() => ({ count: 0 }))
        setClearingNew(false); setClearNewMsg(r.count > 0 ? `Cleared ${r.count}` : 'Nothing to clear')
      },
    })
  }

  async function runNetTest() {
    setNetRunning(true); setNet(null)
    const mbps = (bytes: number, ms: number) => (bytes * 8) / (ms / 1000) / 1e6
    try {
      // Ping: several round-trips, keep the best (least jittery).
      let best = Infinity
      for (let i = 0; i < 5; i++) { const t = performance.now(); await fetch('/api/net/ping', { cache: 'no-store' }); best = Math.min(best, performance.now() - t) }
      // Download: fetch 8 MB and time the full body.
      const dn = 8_000_000; let t = performance.now()
      const buf = await (await fetch(`/api/net/down?bytes=${dn}`, { cache: 'no-store' })).arrayBuffer()
      const downMbps = mbps(buf.byteLength, performance.now() - t)
      // Upload: POST 4 MB and time it.
      const up = 4_000_000; t = performance.now()
      await fetch('/api/net/up', { method: 'POST', body: new Uint8Array(up) })
      const upMbps = mbps(up, performance.now() - t)
      setNet({ pingMs: best, downMbps, upMbps })
    } catch { setNet(null) } finally { setNetRunning(false) }
  }

  async function runDiag() {
    if (!diagSource) return
    setDiagRunning(true); setDiag(null)
    const r = await api.diag(diagSource).catch(() => null)
    setDiag(r ?? { source: '', baseUrl: '', pingMs: 0, speedMbps: 0, sampleBytes: 0, ok: false, error: 'Test failed' })
    setDiagRunning(false)
  }

  return (
    <div className="settings">
      <h2 className="set-h">Settings</h2>

      <section className="set-section">
        <div className="set-section-h">Downloads</div>
        <div className="set-card">
          <div className="set-row-label">Download folder</div>
          <div className="set-hint">Where chapters and covers are saved on the server. Leave blank for the default.</div>
          <input className="set-input" value={dir} onChange={(e) => setDir(e.target.value)} placeholder={info?.effectiveDownloadDir || 'default'} spellCheck={false} autoCapitalize="off" autoCorrect="off" />
          <div className="set-actions">
            <button className="btn primary" disabled={savingDir} onClick={saveDir}>{savingDir ? 'Saving…' : 'Save'}</button>
            {dirMsg && <span className={'set-msg' + (dirMsg.err ? ' err' : '')}>{dirMsg.text}</span>}
          </div>
          <div className="set-kv"><span>Saving to</span><code>{info?.effectiveDownloadDir || '…'}</code></div>
        </div>
        <div className="set-card">
          <button className="set-toggle" onClick={toggleCbz}>
            <div>
              <div className="set-row-label">Save as CBZ</div>
              <div className="set-hint">Off = a folder of page images.</div>
            </div>
            <span className={'switch' + (info?.downloadAsCbz ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
        <div className="set-card">
          <div className="set-row-label">Parallel downloads</div>
          <div className="set-hint">How many manga download at once (across different sources). Higher = faster.</div>
          <div className="stepper">
            <button className="step-btn" disabled={(info?.parallelDownloads ?? 3) <= 1} onClick={() => setParallel((info?.parallelDownloads ?? 3) - 1)}>−</button>
            <span className="step-val">{info?.parallelDownloads ?? 3}</span>
            <button className="step-btn" disabled={(info?.parallelDownloads ?? 3) >= 8} onClick={() => setParallel((info?.parallelDownloads ?? 3) + 1)}>+</button>
          </div>
        </div>
        <div className="set-card">
          <div className="set-row-label">Downloaded content</div>
          <div className="set-hint">Browse what's on disk per series, delete chapters to re-download, or mark a series unread.</div>
          <div className="set-actions"><button className="btn primary" onClick={() => nav('/downloads/manage')}>Manage downloads</button></div>
        </div>
        <div className="set-card">
          <button className="set-toggle" onClick={togglePerSource}>
            <div>
              <div className="set-row-label">Allow same-source parallel</div>
              <div className="set-hint">Off (default) = one manga per source at a time — gentler, fewer failures. On = multiple from the same source at once.</div>
            </div>
            <span className={'switch' + (info?.perSourceParallel ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
      </section>

      <section className="set-section">
        <div className="set-section-h">Sources &amp; languages</div>
        <div className="set-card">
          <div className="set-row-label">Source languages</div>
          <div className="set-hint">Show sources in these languages. None selected = all.</div>
          <div className="lang-chips">
            {languages.map((code) => (
              <button key={code} className={'chip' + (info?.visibleLanguages.includes(code) ? ' on' : '')} onClick={() => toggleLang(code)}>{code.toUpperCase()}</button>
            ))}
          </div>
        </div>
        <div className="set-card">
          <div className="set-row-label">Extensions &amp; repositories</div>
          <div className="set-hint">Install, update or remove extensions and manage repos.</div>
          <div className="set-actions"><button className="btn primary" onClick={() => nav('/extensions')}>Open manager</button></div>
        </div>
      </section>

      <section className="set-section">
        <div className="set-section-h">Reading</div>
        <div className="set-card">
          <div className="set-row-label">Continue reading</div>
          <div className="set-hint">Clear the “Continue reading” row (your reading history). Library and downloads are unaffected.</div>
          <div className="set-actions">
            <button className="btn danger" disabled={clearing} onClick={clearContinue}>{clearing ? 'Clearing…' : 'Clear continue reading'}</button>
            {clearMsg && <span className="set-msg">{clearMsg}</span>}
          </div>
          <div className="set-actions">
            <button className="btn" disabled={clearingNew} onClick={clearNewBadges}>{clearingNew ? 'Clearing…' : 'Clear new-chapter badges'}</button>
            {clearNewMsg && <span className="set-msg">{clearNewMsg}</span>}
          </div>
        </div>
        <div className="set-card">
          <button className="set-toggle" onClick={toggleDevContinueRemove}>
            <div>
              <div className="set-row-label">Continue-reading remove buttons</div>
              <div className="set-hint">Show a ✕ on each Continue-reading card to remove it individually.</div>
            </div>
            <span className={'switch' + (devContinueRemove ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
      </section>

      <section className="set-section">
        <div className="set-section-h">Automatic updates</div>
        <div className="set-card">
          <button className="set-toggle" onClick={toggleAutoUpdate}>
            <div>
              <div className="set-row-label">Check for updates automatically</div>
              <div className="set-hint">Periodically scan your whole library for new chapters in the background.</div>
            </div>
            <span className={'switch' + (info?.autoUpdate ? ' on' : '')}><span className="knob" /></span>
          </button>
          {info?.autoUpdate && (
            <div className="set-inline">
              <span className="set-row-label">Every</span>
              <div className="stepper">
                <button className="step-btn" disabled={(info?.autoUpdateHours ?? 12) <= 1} onClick={() => setAutoHours((info?.autoUpdateHours ?? 12) - 1)}>−</button>
                <span className="step-val">{info?.autoUpdateHours ?? 12}h</span>
                <button className="step-btn" disabled={(info?.autoUpdateHours ?? 12) >= 168} onClick={() => setAutoHours((info?.autoUpdateHours ?? 12) + 1)}>+</button>
              </div>
            </div>
          )}
        </div>
        <div className="set-card">
          <button className="set-toggle" onClick={toggleAutoDownload}>
            <div>
              <div className="set-row-label">Auto-download new chapters</div>
              <div className="set-hint">When an update finds new chapters (scheduled or manual), queue them for download automatically.</div>
            </div>
            <span className={'switch' + (info?.autoDownloadNew ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
      </section>

      <section className="set-section">
        <div className="set-section-h">Backup</div>
        <div className="set-card">
          <div className="set-row-label">Backup &amp; restore</div>
          <div className="set-hint">Import a Mihon / Tachiyomi .tachibk / .proto.gz — you’ll see a preview first and nothing changes until you confirm. Export writes your current library to a .tachibk you can re-import (great for testing round-trips). Install the matching source extensions to actually read imported manga.</div>
          <div className="set-actions">
            <button className="btn primary" disabled={importing} onClick={() => backupInput.current?.click()}>{importing && !preview ? 'Reading…' : 'Choose backup to restore'}</button>
            <button className="btn" onClick={() => setExportOpen(true)}>Export…</button>
            {importMsg && <span className={'set-msg' + (importMsg.err ? ' err' : '')}>{importMsg.text}</span>}
          </div>
          {exportOpen && (
            <div className="backup-preview">
              <div className="set-row-label">Export — choose what to include</div>
              {([['library', 'Library (manga, read, bookmarks & continue-reading history)'], ['settings', 'App settings & reader prefs'], ['repos', 'Extension repositories'], ['extensions', 'Installed extensions (reinstalled on restore)']] as [string, string][]).map(([k, label]) => (
                <label key={k} className="pref-check">
                  <input type="checkbox" checked={!!exportSel[k]} onChange={(e) => setExportSel((s) => ({ ...s, [k]: e.target.checked }))} />
                  <span>{label}</span>
                </label>
              ))}
              <div className="set-actions">
                <button className="btn primary" disabled={!Object.values(exportSel).some(Boolean)} onClick={runExport}>Export</button>
                <button className="btn" onClick={() => setExportOpen(false)}>Cancel</button>
              </div>
            </div>
          )}
          {preview && (
            <div className="backup-preview">
              <div className="set-row-label">Preview · {preview.total} manga {preview.total === 0 && '(nothing to import)'}</div>
              {preview.total > 0 && (
                <div className="backup-list">
                  {preview.manga.slice(0, 60).map((m, i) => (
                    <div key={i} className="backup-item">
                      <span className="backup-title">{m.title}</span>
                      <span className="backup-meta">{m.chapters} ch · {m.read} read{m.inLibrary ? ' · already in library' : ''}</span>
                    </div>
                  ))}
                  {preview.total > 60 && <div className="set-hint">…and {preview.total - 60} more</div>}
                </div>
              )}
              {(preview.hasSettings || !!preview.repos || !!preview.extensions) && (
                <div className="set-hint">
                  Also in this backup (applied on import):
                  {preview.hasSettings && <> · <b>app settings</b> (overwrites yours)</>}
                  {!!preview.repos && <> · <b>{preview.repos} repo{preview.repos === 1 ? '' : 's'}</b> (added)</>}
                  {!!preview.extensions && <> · <b>{preview.extensions} extension{preview.extensions === 1 ? '' : 's'}</b> (reinstalled if missing)</>}
                </div>
              )}
              <div className="set-actions">
                <button className="btn primary" disabled={importing || (preview.total === 0 && !preview.hasSettings && !preview.repos && !preview.extensions)} onClick={doImport}>{importing ? 'Importing…' : preview.total > 0 ? `Import ${preview.total} manga` : 'Restore'}</button>
                <button className="btn" onClick={cancelImport}>Cancel</button>
              </div>
            </div>
          )}
          {importProg && (
            <div className="backup-preview">
              <div className="set-row-label">{importProg.phase}{importProg.total > 0 ? ` · ${importProg.done}/${importProg.total}` : ''}</div>
              <div className="dlc-bar"><div className="dlc-fill" style={{ width: (importProg.total > 0 ? Math.round((importProg.done / importProg.total) * 100) : 15) + '%' }} /></div>
              {importProg.current && <div className="set-hint">{importProg.current}</div>}
            </div>
          )}
          <input ref={backupInput} type="file" accept=".tachibk,.gz,.proto.gz,application/gzip,application/octet-stream" style={{ display: 'none' }} onChange={onBackupFile} />
        </div>
        {/* DYNO Phase 0 (USB backup) — hidden for now; flip SHOW_USB_BACKUP to bring it back. */}
        {SHOW_USB_BACKUP && (
        <div className="set-card">
          <div className="set-row-label">Back up to USB</div>
          <div className="set-hint">Writes a full metadata backup (library + read/bookmarks) plus a copy of every downloaded chapter to a mounted drive. Additive — it never deletes anything on the drive, and skips files already copied. On the server this is a bind-mounted USB path (default <code>/dyno</code>).</div>
          <div className="set-actions">
            <input className="set-input" placeholder="/dyno" value={usbDir} onChange={(e) => setUsbDir(e.target.value)} onBlur={saveUsbDir} />
          </div>
          <div className="set-hint">Blank = use the <code>MU_DYNO_DIR</code> env var, else <code>/dyno</code>.</div>
          <div className="set-actions">
            <button className="btn primary" disabled={!!usbJob?.running} onClick={startUsbBackup}>{usbJob?.running ? 'Backing up…' : 'Back up to USB'}</button>
            {usbJob?.running && (
              <span className="set-msg">
                {usbJob.phase === 'EXPORTING' ? 'Exporting backup…' : usbJob.phase === 'COPYING' ? `Copying ${usbJob.filesDone}/${usbJob.filesTotal}` : 'Preparing…'}
              </span>
            )}
            {!usbJob?.running && usbMsg && <span className={'set-msg' + (usbMsg.err ? ' err' : '')}>{usbMsg.text}</span>}
          </div>
        </div>
        )}
      </section>

      <section className="set-section">
        <div className="set-section-h">Network</div>
        <div className="set-card">
          <button className="set-toggle" onClick={toggleFlareSolverr}>
            <div>
              <div className="set-row-label">Use FlareSolverr</div>
              <div className="set-hint">Solve Cloudflare challenges via a running FlareSolverr instance so protected sources work. Helps genuine CF challenges only — not a source outage.</div>
            </div>
            <span className={'switch' + (info?.flareSolverrEnabled ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
        <div className="set-card">
          <div className="set-row-label">FlareSolverr URL</div>
          <div className="set-hint">Where FlareSolverr is running (its /v1 endpoint is appended). <b>Leave blank and hit Test to auto-detect</b> a running instance (localhost, the Docker sibling, the host, …).</div>
          <input className="set-input" value={fsUrl} onChange={(e) => setFsUrl(e.target.value)} onBlur={saveFsUrl} placeholder="http://localhost:8191 — or blank to auto-detect" spellCheck={false} autoCapitalize="off" autoCorrect="off" />
          <div className="set-actions">
            <button className="btn" disabled={fsTesting} onClick={testFs}>{fsTesting ? 'Searching…' : 'Test / auto-detect'}</button>
            {fsTest && <span className={'set-msg' + (fsTest.ok ? '' : ' err')}>{fsTest.ok ? `✓ Found FlareSolverr${fsTest.url ? ` at ${fsTest.url}` : ''}${fsTest.version ? ` · v${fsTest.version}` : ''}` : (fsTest.error || 'Failed')}</span>}
          </div>
        </div>
        <div className="set-card">
          <div className="set-row-label">Session TTL (minutes)</div>
          <div className="set-hint">How long FlareSolverr keeps a solved browser session for reuse (faster repeat solves).</div>
          <div className="stepper">
            <button className="step-btn" disabled={(info?.flareSolverrSessionTtlMinutes ?? 15) <= 1} onClick={() => setFsTtl((info?.flareSolverrSessionTtlMinutes ?? 15) - 5)}>−</button>
            <span className="step-val">{info?.flareSolverrSessionTtlMinutes ?? 15}</span>
            <button className="step-btn" onClick={() => setFsTtl((info?.flareSolverrSessionTtlMinutes ?? 15) + 5)}>+</button>
          </div>
        </div>
        <div className="set-card">
          <div className="set-row-label">Connection speed (this device ↔ server)</div>
          <div className="set-hint">Ping and up/down throughput between your phone and the server over your network. Transfers ~12 MB.</div>
          <div className="set-actions">
            <button className="btn primary" disabled={netRunning} onClick={runNetTest}>{netRunning ? 'Testing…' : 'Run speed test'}</button>
          </div>
          {net && (
            <div className="diag-result">
              <div className="diag-stat"><span className="diag-num">{Math.round(net.pingMs)}<small>ms</small></span><span className="diag-lbl">Ping</span></div>
              <div className="diag-stat"><span className="diag-num">{net.downMbps.toFixed(1)}<small>Mbps</small></span><span className="diag-lbl">Download</span></div>
              <div className="diag-stat"><span className="diag-num">{net.upMbps.toFixed(1)}<small>Mbps</small></span><span className="diag-lbl">Upload</span></div>
            </div>
          )}
        </div>
        <div className="set-card">
          <div className="set-row-label">Connection test</div>
          <div className="set-hint">Ping + download speed to a source, measured from the server.</div>
          <div className="set-diag-row"><SourcePicker sources={sources} value={diagSource} onChange={setDiagSource} /></div>
          <div className="set-actions">
            <button className="btn primary" disabled={diagRunning || !diagSource} onClick={runDiag}>{diagRunning ? 'Testing…' : 'Run test'}</button>
          </div>
          {diag && (diag.ok ? (
            <div className="diag-result">
              <div className="diag-stat"><span className="diag-num">{Math.round(diag.pingMs)}<small>ms</small></span><span className="diag-lbl">Ping</span></div>
              <div className="diag-stat"><span className="diag-num">{diag.speedMbps.toFixed(1)}<small>Mbps</small></span><span className="diag-lbl">Speed</span></div>
              <div className="diag-stat"><span className="diag-num">{Math.round(diag.sampleBytes / 1024)}<small>KB</small></span><span className="diag-lbl">Sample</span></div>
            </div>
          ) : <div className="set-msg err">{diag.error || 'Test failed'}</div>)}
        </div>
      </section>

      <section className="set-section">
        <div className="set-section-h">System &amp; about</div>
        <div className="set-card">
          <div className="set-row-label">Source health</div>
          <div className="set-hint">Live from your reading — a source can serve its catalog fine while its images are down (an outage). Green = ok · orange = images failing · red = source unreachable / Cloudflare.</div>
          {sources.length === 0 ? <div className="set-hint">No sources installed.</div> : (
            <div className="srchealth">
              {[...sources]
                .sort((a, b) => (Number(b.imagesDown || b.down || b.cfState === 'red') - Number(a.imagesDown || a.down || a.cfState === 'red')) || a.name.localeCompare(b.name))
                .map((s) => {
                  const state = s.down || s.cfState === 'red' ? 'red' : s.imagesDown ? 'orange' : 'green'
                  const label = state === 'red' ? (s.cfState === 'red' ? 'Cloudflare' : 'Unreachable') : state === 'orange' ? 'Images down' : 'OK'
                  return (
                    <div key={s.id} className="srch-row">
                      <span className={'srch-dot srch-' + state} />
                      <span className="srch-name">{s.name}</span>
                      <span className={'srch-state srch-' + state}>{label}</span>
                    </div>
                  )
                })}
            </div>
          )}
        </div>
        <div className="set-card">
          <div className="set-row-label">Server status</div>
          {!stats ? <div className="set-hint">Loading…</div> : (
            <>
              <div className="diag-result">
                <div className="diag-stat"><span className="diag-num">{stats.processCpuPct.toFixed(0)}<small>%</small></span><span className="diag-lbl">Process CPU</span></div>
                <div className="diag-stat"><span className="diag-num">{stats.threads}</span><span className="diag-lbl">Threads</span></div>
                <div className="diag-stat"><span className="diag-num">{fmtUptime(stats.uptimeMs)}</span><span className="diag-lbl">Uptime</span></div>
              </div>
              <div className="set-kv"><span>Process RAM (RSS)</span><code>{stats.processRssMb >= 0 ? `${stats.processRssMb} MB` : 'n/a'}</code></div>
              <div className="set-kv"><span>Heap (used / max)</span><code>{stats.heapUsedMb} / {stats.heapMaxMb} MB</code></div>
              <div className="dlc-bar"><div className="dlc-fill" style={{ width: Math.min(100, Math.round((stats.heapUsedMb / Math.max(1, stats.heapMaxMb)) * 100)) + '%' }} /></div>
              <div className="set-kv"><span>Heap committed</span><code>{stats.heapCommittedMb} MB</code></div>
              <div className="set-kv"><span>Off-heap</span><code>{stats.nonHeapUsedMb} MB</code></div>
              <div className="set-kv"><span>System RAM</span><code>{(stats.systemRamUsedMb / 1024).toFixed(1)} / {(stats.systemRamTotalMb / 1024).toFixed(1)} GB</code></div>
              <div className="set-kv"><span>Downloads</span><code>{stats.activeDownloads} active · {stats.queuedDownloads} queued</code></div>
              <div className="set-kv"><span>Installed sources</span><code>{stats.installedSources}</code></div>
              <div className="set-kv"><span>PID</span><code>{stats.pid}</code></div>
              <div className="set-kv"><span>Runtime</span><code>{stats.jvm}</code></div>
              <div className="set-kv"><span>Host</span><code>{stats.os}</code></div>
            </>
          )}
        </div>
        <div className="set-card">
          <div className="set-row-label">Server paths</div>
          <div className="set-kv"><span>Downloads</span><code>{info?.effectiveDownloadDir || '…'}</code></div>
          <div className="set-kv"><span>Data folder</span><code>{info?.dataDir || '…'}</code></div>
        </div>
        <div className="set-card">
          {about ? (
            <>
              <div className="set-row-label">manga-utils · web</div>
              <div className="about-ver">v{about.version} · <code>{about.commit}</code> · built {about.buildTime.replace('T', ' ')}</div>
              <table className="tech-table">
                <tbody>
                  {about.tech.map((t) => (
                    <tr key={t.role}><td className="tech-role">{t.role}</td><td className="tech-val">{t.tech}</td></tr>
                  ))}
                </tbody>
              </table>
              <div className="set-actions">
                <button className="btn" onClick={() => setShowChangelog((v) => !v)}>{showChangelog ? 'Hide' : 'Show'} changelog ({about.changelog.length})</button>
              </div>
              {showChangelog && (
                <div className="changelog">
                  {about.changelog.map((c) => (
                    <div className="cl-row" key={c.sha}>
                      <span className="cl-meta"><code>{c.sha}</code> · {c.date}</span>
                      <span className="cl-sub">{c.subject}</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          ) : <div className="set-hint">Loading build info…</div>}
        </div>
      </section>

      <section className="set-section">
        <div className="set-section-h">Developer</div>
        <div className="set-card">
          <div className="set-row-label">Simulate a new chapter (test)</div>
          <div className="set-hint">Makes a library manga look like it got an update — sets its “!” badge, and auto-downloads it if that setting is on.</div>
          <select className="set-select" value={simManga} onChange={(e) => setSimManga(e.target.value)}>
            <option value="">Pick a manga…</option>
            {[...library].sort((a, b) => a.title.localeCompare(b.title)).map((e) => (
              <option key={e.sourceId + '|' + e.url} value={e.sourceId + '|' + e.url}>{e.title}</option>
            ))}
          </select>
          <div className="set-actions">
            <button className="btn primary" disabled={simRunning || !simManga} onClick={simulate}>{simRunning ? 'Simulating…' : 'Simulate update'}</button>
            {simMsg && <span className="set-msg">{simMsg}</span>}
          </div>
        </div>
      </section>

      {confirm && <ConfirmDialog spec={confirm} />}
    </div>
  )
}
