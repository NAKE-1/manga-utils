import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, DevStats, LibraryEntry, DevStorage, DevBucket, ReqLog, Source, SourceDiag, RawResult } from '../api'
import { IconArrowLeft } from '../components/icons'
import { MigrationModal } from '../components/MigrationModal'

// Hidden Developer screen (opened from Settings → Developer). Home for the dev/debug tools.

const mb = (n: number) => `${n.toFixed(0)} MB`
function fmtBytes(n: number) {
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)} GB`
  if (n >= 1e6) return `${(n / 1e6).toFixed(0)} MB`
  if (n >= 1e3) return `${(n / 1e3).toFixed(0)} KB`
  return `${n} B`
}
function fmtUptime(ms: number) {
  const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  return d > 0 ? `${d}d ${h}h ${m}m` : h > 0 ? `${h}h ${m}m` : `${m}m ${s % 60}s`
}

function Stat({ label, value, wide }: { label: string; value: string; wide?: boolean }) {
  return <div className={'dev-stat' + (wide ? ' wide' : '')}><div className="dev-stat-l">{label}</div><div className="dev-stat-v">{value}</div></div>
}
function Meter({ label, used, total }: { label: string; used: number; total: number }) {
  const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0
  return (
    <div className="dev-stat wide">
      <div className="dev-stat-l">{label}<span className="dev-meter-n">{mb(used)} / {mb(total)} · {pct.toFixed(0)}%</span></div>
      <div className="dev-bar"><div className="dev-bar-fill" style={{ width: pct + '%' }} /></div>
    </div>
  )
}

export function Dev() {
  const nav = useNavigate()
  const [s, setS] = useState<DevStats | null>(null)
  const [failed, setFailed] = useState(false)
  const [migOpen, setMigOpen] = useState(false)
  const [scanMarker, setScanMarker] = useState(() => localStorage.getItem('dev.scanMarker') === '1')
  const [library, setLibrary] = useState<LibraryEntry[]>([])
  const [simManga, setSimManga] = useState('')
  const [simMsg, setSimMsg] = useState('')
  const [simRunning, setSimRunning] = useState(false)
  const [storage, setStorage] = useState<DevStorage | null>(null)
  const [storageBusy, setStorageBusy] = useState(false)
  const [stateFiles, setStateFiles] = useState<DevBucket[]>([])
  const [stateSel, setStateSel] = useState('')
  const [stateContent, setStateContent] = useState('')
  const [stateBusy, setStateBusy] = useState(false)
  const [reqs, setReqs] = useState<ReqLog[]>([])
  const [reqLive, setReqLive] = useState(true)
  const [sources, setSources] = useState<Source[]>([])
  const [diagSel, setDiagSel] = useState('')
  const [diag, setDiag] = useState<SourceDiag | null>(null)
  const [rawUrl, setRawUrl] = useState('')
  const [rawResult, setRawResult] = useState<RawResult | null>(null)
  const [rawBusy, setRawBusy] = useState(false)

  useEffect(() => {
    const load = () => api.devStats().then((d) => { setS(d); setFailed(false) }).catch(() => setFailed(true))
    load()
    const t = setInterval(load, 3000)
    api.library().then(setLibrary).catch(() => {})
    api.devState().then(setStateFiles).catch(() => {})
    api.sources().then(setSources).catch(() => {})
    return () => clearInterval(t)
  }, [])

  useEffect(() => {
    if (!diagSel) { setDiag(null); return }
    api.devSourceDiag(diagSel).then((d) => { setDiag(d); setRawUrl(d.baseUrl) }).catch(() => setDiag(null))
  }, [diagSel])

  useEffect(() => {
    if (!reqLive) return
    const load = () => api.devRequests().then(setReqs).catch(() => {})
    load()
    const t = setInterval(load, 1500)
    return () => clearInterval(t)
  }, [reqLive])

  function toggleScanMarker() {
    const v = !scanMarker; setScanMarker(v)
    if (v) localStorage.setItem('dev.scanMarker', '1'); else localStorage.removeItem('dev.scanMarker')
  }
  async function scanStorage() {
    setStorageBusy(true)
    try { setStorage(await api.devStorage()) } catch { /* ignore */ } finally { setStorageBusy(false) }
  }
  async function openState(name: string) {
    setStateSel(name); setStateBusy(true); setStateContent('')
    try {
      const r = await api.devStateContent(name)
      let c = r.content
      try { c = JSON.stringify(JSON.parse(r.content), null, 2) } catch { /* keep raw */ }
      setStateContent(c)
    } catch { setStateContent('(failed to load)') } finally { setStateBusy(false) }
  }
  async function sendRaw() {
    if (!diagSel || !rawUrl) return
    setRawBusy(true); setRawResult(null)
    try { setRawResult(await api.devSourceRaw(diagSel, rawUrl)) }
    catch (e) { setRawResult({ status: -1, ms: 0, snippet: '', error: e instanceof Error ? e.message : 'failed' }) }
    finally { setRawBusy(false) }
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

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Developer</span>
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Runtime <span className="dev-sec-sub">live · 3s</span></div>
        {failed && !s ? <div className="center-msg">Couldn't load runtime stats.</div> : !s ? <div className="spinner" /> : (
          <div className="dev-grid">
            <Stat label="Uptime" value={fmtUptime(s.uptimeMs)} />
            <Stat label="CPU" value={`${s.processCpuPct.toFixed(0)}%`} />
            <Stat label="Threads" value={String(s.threads)} />
            <Stat label="Sources" value={String(s.installedSources)} />
            <Stat label="Downloads" value={`${s.activeDownloads} active · ${s.queuedDownloads} queued`} />
            <Stat label="PID" value={String(s.pid)} />
            <Meter label="JVM heap" used={s.heapUsedMb} total={s.heapMaxMb} />
            <Meter label="Process memory" used={s.processRssMb} total={s.systemRamTotalMb} />
            <Meter label="System RAM" used={s.systemRamUsedMb} total={s.systemRamTotalMb} />
            <Stat label="Heap committed" value={mb(s.heapCommittedMb)} />
            <Stat label="Non-heap" value={mb(s.nonHeapUsedMb)} />
            <Stat label="JVM" value={s.jvm} wide />
            <Stat label="OS" value={s.os} wide />
          </div>
        )}
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Storage <button className="btn sm" style={{ marginLeft: 'auto' }} disabled={storageBusy} onClick={scanStorage}>{storageBusy ? 'Scanning…' : storage ? 'Rescan' : 'Scan'}</button></div>
        {storage && (() => {
          const max = Math.max(1, ...storage.buckets.filter((b) => !b.label.startsWith('Data dir')).map((b) => b.bytes))
          return (
            <>
              <div className="dev-storage">
                {storage.buckets.map((b) => (
                  <div className="dev-store-row" key={b.label}>
                    <div className="dev-store-l"><span className="dev-store-name">{b.label}</span><span className="dev-store-b">{fmtBytes(b.bytes)}</span></div>
                    <div className="dev-bar"><div className="dev-bar-fill" style={{ width: Math.min(100, (b.bytes / max) * 100) + '%' }} /></div>
                  </div>
                ))}
              </div>
              {storage.downloadsTop.length > 0 && (
                <>
                  <div className="dev-sec-sub" style={{ padding: '10px 16px 4px' }}>Largest download folders</div>
                  <div className="dev-storage">
                    {storage.downloadsTop.map((b) => (
                      <div className="dev-store-row" key={b.label}>
                        <div className="dev-store-l"><span className="dev-store-name">{b.label}</span><span className="dev-store-b">{fmtBytes(b.bytes)}</span></div>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </>
          )
        })()}
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">State</div>
        <div className="dev-chips">
          {stateFiles.map((f) => (
            <button key={f.label} className={'dev-chip' + (stateSel === f.label ? ' on' : '')} onClick={() => openState(f.label)}>
              {f.label}<span className="dev-chip-b">{fmtBytes(f.bytes)}</span>
            </button>
          ))}
          {stateFiles.length === 0 && <span className="dev-sec-sub" style={{ padding: '0 4px' }}>No state files.</span>}
        </div>
        {stateSel && <pre className="dev-json">{stateBusy ? 'Loading…' : stateContent}</pre>}
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Network
          <button className="btn sm" style={{ marginLeft: 'auto' }} onClick={() => setReqLive((v) => !v)}>{reqLive ? 'Pause' : 'Live'}</button>
          <button className="btn sm" onClick={async () => { await api.devRequestsClear(); setReqs([]) }}>Clear</button>
        </div>
        <div className="dev-net">
          {reqs.length === 0 ? <div className="dev-sec-sub" style={{ padding: '0 4px' }}>No requests yet — browse a source.</div> : reqs.map((r, i) => (
            <div className="dev-net-row" key={i}>
              <span className={'dev-net-code ' + (r.code < 0 ? 'err' : 'c' + Math.floor(r.code / 100))}>{r.code < 0 ? 'ERR' : r.code}</span>
              <span className="dev-net-m">{r.method}</span>
              <span className="dev-net-host">{r.host}</span>
              <span className="dev-net-path">{r.path}</span>
              <span className="dev-net-ms">{r.ms}ms</span>
            </div>
          ))}
        </div>
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Source diagnostics</div>
        <div style={{ padding: '0 12px' }}>
          <select className="set-select" value={diagSel} onChange={(e) => setDiagSel(e.target.value)}>
            <option value="">Pick a source…</option>
            {sources.map((s) => <option key={s.id} value={s.id}>{s.name}{s.lang ? ` (${s.lang.toUpperCase()})` : ''}</option>)}
          </select>
        </div>
        {diag && (
          <div className="dev-grid" style={{ marginTop: 8 }}>
            <Stat label="Cloudflare" value={diag.cfBlocked ? 'blocked' : 'clear'} />
            <Stat label="Cooldown" value={diag.cooldownMs > 0 ? `${Math.ceil(diag.cooldownMs / 1000)}s` : 'none'} />
            <Stat label="Base URL" value={diag.baseUrl || '—'} wide />
            <Stat label="FlareSolverr UA" value={diag.flareUa || '—'} wide />
          </div>
        )}
        {diagSel && (
          <div className="ext-search" style={{ marginTop: 10 }}>
            <input value={rawUrl} onChange={(e) => setRawUrl(e.target.value)} placeholder="Raw GET URL (via this source's client)…" spellCheck={false} onKeyDown={(e) => { if (e.key === 'Enter') sendRaw() }} />
            <button className="btn primary" disabled={rawBusy || !rawUrl} onClick={sendRaw}>{rawBusy ? '…' : 'Send'}</button>
          </div>
        )}
        {rawResult && <pre className="dev-json">{rawResult.error ? `ERROR: ${rawResult.error}` : `HTTP ${rawResult.status} · ${rawResult.ms}ms · ${rawResult.contentType || ''}\n\n${rawResult.snippet}`}</pre>}
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Data &amp; migration</div>
        <div className="set-card">
          <div className="set-row-label">Mass data migration</div>
          <div className="set-hint">Move ALL app data — library, read state, resume points, history, settings, extensions, covers — from one instance to another. Everything <b>except downloads</b>, which you move as a mounted drive. You'll see exactly what's inside before creating or restoring.</div>
          <div className="set-actions"><button className="btn primary" onClick={() => setMigOpen(true)}>Open migration…</button></div>
        </div>
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Debug</div>
        <div className="set-card">
          <button className="set-toggle" onClick={toggleScanMarker}>
            <div>
              <div className="set-row-label">Scan marker in reader</div>
              <div className="set-hint">Show the scanlator of the chapter you're reading (Gamma, Official, unofficial…) as a box under the title at the top of the reader.</div>
            </div>
            <span className={'switch' + (scanMarker ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Testing</div>
        <div className="set-card">
          <div className="set-row-label">Simulate a new chapter</div>
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
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">Diagnostics bundle</div>
        <div className="set-card">
          <div className="set-hint">A zip of logs, the persisted state files, and a summary (runtime, storage, recent requests) — for troubleshooting or sharing.</div>
          <div className="set-actions"><a className="btn primary" href="/api/dev/diagnostics">Download diagnostics bundle</a></div>
        </div>
      </div>

      {migOpen && <MigrationModal onClose={() => setMigOpen(false)} />}
    </div>
  )
}
