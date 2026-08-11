import { useEffect, useState, type MouseEvent as ReactMouseEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, DevStats, LibraryEntry, DevStorage, DevBucket, ReqLog, Source, SourceDiag, RawResult } from '../api'
import { IconArrowLeft } from '../components/icons'
import { MigrationModal } from '../components/MigrationModal'
import { WebviewModal } from '../components/WebviewModal'
import { toast } from '../components/Toast'

// Hidden Developer screen (opened from Settings → Developer). Home for the dev/debug tools.

const mb = (n: number) => `${n.toFixed(0)} MB`
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const CAP_MAX_TRIES = 6 // refresh-and-retry cap when a captcha can't be fully detected/matched
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
  const [verbose, setVerbose] = useState(false)
  const [autoSolve, setAutoSolve] = useState(false)
  const [wvUrl, setWvUrl] = useState('')
  const [wvSourceId, setWvSourceId] = useState('')
  const [wvOpen, setWvOpen] = useState<{ url?: string; source?: string } | null>(null)
  const [lifecycle, setLifecycle] = useState('') // 'restart' | 'shutdown' while in flight
  const [cap, setCap] = useState<import('../api').DevCaptcha | null>(null)
  const [capBusy, setCapBusy] = useState(false)
  const [capErr, setCapErr] = useState('')
  const [capClicks, setCapClicks] = useState<{ x: number; y: number }[]>([]) // in B's natural pixel coords, in order
  const [capBDim, setCapBDim] = useState<{ w: number; h: number }>({ w: 1, h: 1 })
  const [capADim, setCapADim] = useState<{ w: number; h: number }>({ w: 1, h: 1 })
  const [solve, setSolve] = useState<import('../api').CapSolve | null>(null)
  const [solving, setSolving] = useState(false)
  const [solveAttempt, setSolveAttempt] = useState(0)
  const [capStats, setCapStats] = useState<import('../api').CapStats | null>(null)

  useEffect(() => {
    const load = () => api.devStats().then((d) => { setS(d); setFailed(false) }).catch(() => setFailed(true))
    load()
    const t = setInterval(load, 3000)
    api.library().then(setLibrary).catch(() => {})
    api.devState().then(setStateFiles).catch(() => {})
    api.sources().then(setSources).catch(() => {})
    api.getSettings().then((s) => { setVerbose(s.verboseLogging); setAutoSolve(s.autoSolveCaptcha) }).catch(() => {})
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
  useEffect(() => {
    const cs = () => { if (!document.hidden) api.captchaStats().then(setCapStats).catch(() => {}) }
    cs(); const t = setInterval(cs, 3000)
    return () => clearInterval(t)
  }, [])

  async function toggleVerbose() {
    const v = !verbose; setVerbose(v)
    const r = await api.saveSettings({ verboseLogging: v }).catch(() => null)
    if (!r) { setVerbose(!v); toast('Failed to change logging', 'error') }
  }
  async function toggleAutoSolve() {
    const v = !autoSolve; setAutoSolve(v)
    const r = await api.saveSettings({ autoSolveCaptcha: v }).catch(() => null)
    if (!r) { setAutoSolve(!v); toast('Failed to change setting', 'error') }
  }
  async function doLifecycle(kind: 'restart' | 'shutdown') {
    if (!confirm(kind === 'restart' ? 'Restart the server now? Downloads and the WebView will briefly stop.' : 'Shut down the server now? You’ll need to start it again from the machine.')) return
    setLifecycle(kind)
    await (kind === 'restart' ? api.devRestart() : api.devShutdown()).catch(() => {})
    toast(kind === 'restart' ? 'Restarting… reconnecting shortly' : 'Server shutting down', 'info', 8000)
  }
  async function genCaptcha() {
    setCapBusy(true); setCapErr(''); setCapClicks([]); setSolve(null)
    try { setCap(await api.devCaptcha()) }
    catch (e) { setCap(null); setCapErr(e instanceof Error ? e.message : 'failed to fetch captcha') }
    finally { setCapBusy(false) }
  }
  // Solve loop: detect A+B; if not every shape is detected/matched, REFRESH a new captcha and re-detect
  // (bounded), then reveal the click order one dot/second — emulating the real 1s-between-clicks pacing.
  async function attemptSolve() {
    setSolving(true); setCapErr(''); setCapClicks([])
    try {
      let current = cap
      let win: import('../api').CapSolve | null = null
      for (let attempt = 1; attempt <= CAP_MAX_TRIES; attempt++) {
        if (!current) { current = await api.devCaptcha(); setCap(current) }
        setSolveAttempt(attempt); setCapClicks([])
        const s = await api.devCaptchaSolve(current.imageA, current.imageB)
        setSolve(s)
        // "complete" = every A shape detected AND matched in B (no missing). count = shapes A asks for.
        const complete = s.solved && (current.count > 0 ? s.aDets.length === current.count : true)
        if (complete) { win = s; break }
        if (attempt < CAP_MAX_TRIES) { await sleep(500); current = await api.devCaptcha(); setCap(current) } // refresh + re-detect
      }
      if (win) {
        const pts = win.clicks.map((c) => ({ x: Math.round((c.x0 + c.x1) / 2), y: Math.round((c.y0 + c.y1) / 2) }))
        for (let i = 0; i < pts.length; i++) { setCapClicks(pts.slice(0, i + 1)); if (i < pts.length - 1) await sleep(1000) }
      } else {
        setCapErr(`couldn't fully detect/match after ${CAP_MAX_TRIES} refreshes`)
      }
    } catch (e) { setCapErr(e instanceof Error ? e.message : 'solve failed') }
    finally { setSolving(false); setSolveAttempt(0) }
  }
  // Map a click on the (scaled) B image back to its native pixel coords and record it, in order.
  function clickB(e: ReactMouseEvent<HTMLImageElement>) {
    const img = e.currentTarget
    const rect = img.getBoundingClientRect()
    if (!rect.width || !rect.height || !img.naturalWidth) return
    const x = Math.round(((e.clientX - rect.left) / rect.width) * img.naturalWidth)
    const y = Math.round(((e.clientY - rect.top) / rect.height) * img.naturalHeight)
    setCapClicks((c) => [...c, { x, y }])
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
        <div className="set-card">
          <button className="set-toggle" onClick={toggleVerbose}>
            <div>
              <div className="set-row-label">Verbose logging</div>
              <div className="set-hint">⚠ Traces every network request/response in the server console. Noisy and can slow a busy server — turn on only while diagnosing, then off.</div>
            </div>
            <span className={'switch' + (verbose ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">WebView</div>
        <div className="set-card">
          <div className="set-row-label">WebView tester</div>
          <div className="set-hint">Open any site (or a source's homepage) in the streamed Chromium WebView — handy for eyeballing captchas, popups, cookies, and layout while iterating on the WebView.</div>
          <div className="wv-test-row">
            <select className="wv-test-src" value={wvSourceId} onChange={(e) => setWvSourceId(e.target.value)}>
              <option value="">Pick a source…</option>
              {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
            <input
              className="wv-test-url"
              value={wvUrl}
              onChange={(e) => setWvUrl(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && wvUrl.trim()) setWvOpen({ url: wvUrl.trim() }) }}
              placeholder="https://example.com/  (overrides the source)"
              inputMode="url" autoCapitalize="off" autoCorrect="off" spellCheck={false}
            />
          </div>
          <div className="set-actions">
            <button className="btn primary" disabled={!wvUrl.trim() && !wvSourceId} onClick={() => setWvOpen(wvUrl.trim() ? { url: wvUrl.trim() } : { source: wvSourceId })}>Open WebView</button>
          </div>
        </div>

        <div className="set-card">
          <button className="set-toggle" onClick={toggleAutoSolve}>
            <div>
              <div className="set-row-label">Auto-solve MangaFire captcha</div>
              <div className="set-hint">When a MangaFire block is hit (incl. unattended overnight updates/downloads), open the challenge and solve it with the ONNX detector automatically. On give-up you still get the Discord ping + manual WebView fallback.</div>
            </div>
            <span className={'switch' + (autoSolve ? ' on' : '')}><span className="knob" /></span>
          </button>
        </div>

        <div className="set-card">
          <div className="set-row-label">MangaFire captcha tester</div>
          <div className="set-hint">Pulls a fresh shape-captcha from <code>/@waf/generate</code> through JCEF. A = the order to click; B = the grid — click the shapes on B in order (this is where the solver will click). Coordinates are shown in B's native pixels.</div>
          <div className="set-actions">
            <button className="btn primary" disabled={capBusy} onClick={genCaptcha}>{capBusy ? 'Fetching…' : cap ? 'New captcha' : 'Generate captcha'}</button>
            {cap && <button className="btn primary" disabled={solving} onClick={attemptSolve}>{solving ? (solveAttempt ? `Solving… (try ${solveAttempt})` : 'Solving…') : 'Attempt solve'}</button>}
            {cap && capClicks.length > 0 && <button className="btn" onClick={() => { setCapClicks([]); setSolve(null) }}>Clear</button>}
            {cap && <span className="cap-meta">id {cap.captchaId || '?'} · need {cap.count} · clicked {capClicks.length}</span>}
          </div>
          {solve && (
            <div className={'cap-verdict ' + (solve.solved ? 'ok' : 'bad')}>
              {solve.solved ? '✓ solvable' : `✗ missing: ${solve.missing.join(', ') || '(nothing matched)'}`}
              <div className="cap-diag">
                A ({solve.aDets.length}): {solve.aDets.map((d) => `${d.name} ${(d.conf * 100).toFixed(0)}%`).join('  →  ') || '(no shapes detected)'}
              </div>
              <div className="cap-diag">
                B ({solve.bDets.length}): {solve.bDets.map((d) => d.name).join(', ') || '(no shapes detected)'} · will click {solve.clicks.length}
              </div>
            </div>
          )}
          {capErr && <div className="cap-err">⚠ {capErr}</div>}
          {capStats && (capStats.solved + capStats.failed > 0) && (
            <div className="cap-stats">
              <div className="cap-stats-row">
                <span><b>{capStats.solved}</b> solved</span>
                <span><b>{capStats.failed}</b> failed</span>
                <span><b>{capStats.reloads}</b> reloads</span>
                <span>avg <b>{(capStats.avgMs / 1000).toFixed(1)}s</b></span>
                <span>rate <b>{Math.round((capStats.solved / (capStats.solved + capStats.failed)) * 100)}%</b></span>
              </div>
              {capStats.recent.length > 0 && (
                <div className="cap-stats-recent">
                  {capStats.recent.slice(0, 6).map((a, i) => (
                    <span key={i} className={'cap-att ' + a.result}>{a.result === 'solved' ? `✓ ${a.clicks}clk/${a.tries}try ${(a.ms / 1000).toFixed(1)}s` : `✗ ${a.tries}try`}</span>
                  ))}
                </div>
              )}
            </div>
          )}
          {cap && (
            <div className="cap-wrap">
              <div className="cap-col">
                <div className="cap-label">A — order (detected)</div>
                <div className="cap-bwrap">
                  <img
                    className="cap-a" src={cap.imageA} alt="order" draggable={false}
                    onLoad={(e) => setCapADim({ w: e.currentTarget.naturalWidth || 1, h: e.currentTarget.naturalHeight || 1 })}
                  />
                  {solve?.aDets.map((d, i) => (
                    <span key={'abox' + i} className="cap-box" title={`${d.name} ${(d.conf * 100).toFixed(0)}%`}
                      style={{ left: `${(d.x0 / capADim.w) * 100}%`, top: `${(d.y0 / capADim.h) * 100}%`, width: `${((d.x1 - d.x0) / capADim.w) * 100}%`, height: `${((d.y1 - d.y0) / capADim.h) * 100}%` }} />
                  ))}
                  {solve?.aDets.map((d, i) => (
                    <span key={'adot' + i} className="cap-dot" style={{ left: `${(((d.x0 + d.x1) / 2) / capADim.w) * 100}%`, top: `${(((d.y0 + d.y1) / 2) / capADim.h) * 100}%` }}>{i + 1}</span>
                  ))}
                </div>
              </div>
              <div className="cap-col">
                <div className="cap-label">B — click in order</div>
                <div className="cap-bwrap">
                  <img
                    className="cap-b" src={cap.imageB} alt="grid" draggable={false} onClick={clickB}
                    onLoad={(e) => setCapBDim({ w: e.currentTarget.naturalWidth || 1, h: e.currentTarget.naturalHeight || 1 })}
                  />
                  {solve?.bDets.map((d, i) => (
                    <span
                      key={'box' + i}
                      className="cap-box"
                      title={`${d.name} ${(d.conf * 100).toFixed(0)}%`}
                      style={{ left: `${(d.x0 / capBDim.w) * 100}%`, top: `${(d.y0 / capBDim.h) * 100}%`, width: `${((d.x1 - d.x0) / capBDim.w) * 100}%`, height: `${((d.y1 - d.y0) / capBDim.h) * 100}%` }}
                    />
                  ))}
                  {capClicks.map((c, i) => (
                    <span key={i} className="cap-dot" style={{ left: `${(c.x / capBDim.w) * 100}%`, top: `${(c.y / capBDim.h) * 100}%` }}>{i + 1}</span>
                  ))}
                </div>
                {capClicks.length > 0 && (
                  <div className="cap-coords">{capClicks.map((c, i) => `${i + 1}:(${c.x},${c.y})`).join('  ')}</div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="dev-sec">
        <div className="dev-sec-h">System</div>
        <div className="set-card">
          <div className="set-row-label">Server lifecycle</div>
          <div className="set-hint">Restart cleanly tears down Chromium (JCEF) and relaunches — the reliable fix for a “stuck on initializing” hang from a leftover helper holding the cache lock. Restart only relaunches when the server was started via start.bat.</div>
          <div className="set-actions">
            <button className="btn primary" disabled={!!lifecycle} onClick={() => doLifecycle('restart')}>{lifecycle === 'restart' ? 'Restarting…' : 'Restart server'}</button>
            <button className="btn danger" disabled={!!lifecycle} onClick={() => doLifecycle('shutdown')}>{lifecycle === 'shutdown' ? 'Shutting down…' : 'Shut down server'}</button>
          </div>
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
      {wvOpen && <WebviewModal url={wvOpen.url} source={wvOpen.source} onClose={() => setWvOpen(null)} />}
    </div>
  )
}
