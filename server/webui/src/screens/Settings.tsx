import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, Source, SettingsInfo, DiagResult, DevStats, LibraryEntry } from '../api'
import { SourcePicker } from '../components/SourcePicker'
import { ConfirmDialog, ConfirmSpec } from '../components/ConfirmDialog'

const fmtUptime = (ms: number) => {
  const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  return d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : `${m}m ${s % 60}s`
}

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
  const [devContinueRemove, setDevContinueRemove] = useState(localStorage.getItem('dev.continueRemove') === '1')

  function toggleDevContinueRemove() {
    const v = !devContinueRemove
    setDevContinueRemove(v)
    localStorage.setItem('dev.continueRemove', v ? '1' : '0')
  }

  useEffect(() => {
    api.getSettings().then((s) => { setInfo(s); setDir(s.downloadDir || '') }).catch(() => {})
    api.sources().then((s) => { setSources(s); setDiagSource((c) => (c && s.some((x) => x.id === c)) || !s.length ? c : s[0].id) }).catch(() => {})
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
    const tick = () => api.devStats().then((s) => { if (alive) setStats(s) }).catch(() => {})
    tick()
    const t = setInterval(tick, 2000)
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
        <div className="set-section-h">Sources</div>
        <div className="set-card">
          <div className="set-row-label">Source languages</div>
          <div className="set-hint">Show sources in these languages. None selected = all.</div>
          <div className="lang-chips">
            {languages.map((code) => (
              <button key={code} className={'chip' + (info?.visibleLanguages.includes(code) ? ' on' : '')} onClick={() => toggleLang(code)}>{code.toUpperCase()}</button>
            ))}
          </div>
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
          <div className="set-row-label">Extensions &amp; repositories</div>
          <div className="set-hint">Install, update or remove extensions and manage repos.</div>
          <div className="set-actions"><button className="btn primary" onClick={() => nav('/extensions')}>Open manager</button></div>
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

        <div className="set-card">
          <button className="set-toggle" onClick={toggleDevContinueRemove}>
            <div>
              <div className="set-row-label">Continue-reading remove buttons</div>
              <div className="set-hint">Show a ✕ on each Continue-reading card to remove it individually.</div>
            </div>
            <span className={'switch' + (devContinueRemove ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>

        <div className="set-card">
          <div className="set-row-label">Server paths</div>
          <div className="set-kv"><span>Downloads</span><code>{info?.effectiveDownloadDir || '…'}</code></div>
          <div className="set-kv"><span>Data folder</span><code>{info?.dataDir || '…'}</code></div>
        </div>
      </section>

      {confirm && <ConfirmDialog spec={confirm} />}
    </div>
  )
}
