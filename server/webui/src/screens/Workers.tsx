import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type WorkerStatus, type ExitNodesDto, type WorkerExt, type WorkerUsage } from '../api'
import { IconArrowLeft } from '../components/icons'

// View a worker's installed extensions (name, version, BETA for sideloads).
function ExtList({ name }: { name: string }) {
  const [open, setOpen] = useState(false)
  const [list, setList] = useState<WorkerExt[] | null>(null)
  async function toggle() {
    const n = !open; setOpen(n)
    if (n && !list) { try { setList(await api.workerExtensions(name)) } catch { setList([]) } }
  }
  return (
    <div style={{ marginTop: 10 }}>
      <button className="btn" onClick={toggle}>{open ? 'Hide' : 'View'} extensions{list ? ` (${list.length})` : ''}</button>
      {open && list && (
        <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 5, maxHeight: 240, overflowY: 'auto' }}>
          {list.length === 0 && <span className="set-hint">None, or node unreachable.</span>}
          {list.map((e) => (
            <div key={e.pkg} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
              <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.name}</span>
              {e.beta && <span className="dlc-w" style={{ background: '#e8a06e' }}>BETA</span>}
              <span className="set-hint" style={{ fontVariantNumeric: 'tabular-nums' }}>v{e.version}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// Tiny RAM sparkline over recent readings.
function Spark({ data }: { data: number[] }) {
  if (!data || data.length < 2) return null
  const w = 240, h = 30, max = Math.max(...data), min = Math.min(...data), rng = (max - min) || 1
  const pts = data.map((v, i) => `${(i / (data.length - 1)) * w},${h - ((v - min) / rng) * (h - 3) - 1.5}`).join(' ')
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" style={{ maxWidth: '100%', display: 'block', marginTop: 6 }}>
      <polyline points={pts} fill="none" stroke="var(--accent, #7c6cff)" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}
function URow({ label, s, unit }: { label: string; s: { min: number; max: number; avg: number }; unit: string }) {
  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <span style={{ width: 44, color: 'var(--muted)' }}>{label}</span>
      <span>avg <b>{s.avg}</b> · peak <b>{s.max}</b> · min {s.min}{unit}</span>
    </div>
  )
}
// Rolling resource usage (min/max/avg over 1h / 24h / since boot) - to size future nodes from real numbers.
function UsagePanel({ name }: { name: string }) {
  const [open, setOpen] = useState(false)
  const [u, setU] = useState<WorkerUsage | 'none' | null>(null)
  const [win, setWin] = useState<'h1' | 'h24' | 'boot'>('h24')
  async function toggle() {
    const n = !open; setOpen(n)
    if (n && u === null) { try { setU(await api.workerUsage(name)) } catch { setU('none') } }
  }
  const data = u && u !== 'none' ? u : null
  const w = data ? data[win] : null
  return (
    <div style={{ marginTop: 10 }}>
      <button className="btn" onClick={toggle}>{open ? 'Hide' : 'View'} usage</button>
      {open && u === 'none' && <div className="set-hint" style={{ marginTop: 8 }}>No usage data yet - it builds as the node reports in.</div>}
      {open && data && w && (
        <div style={{ marginTop: 8 }}>
          <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
            {(['h1', 'h24', 'boot'] as const).map((k) => (
              <button key={k} className={'btn' + (win === k ? ' primary' : '')} style={{ padding: '3px 10px', fontSize: 12 }} onClick={() => setWin(k)}>
                {k === 'h1' ? '1h' : k === 'h24' ? '24h' : 'Since boot'}
              </button>
            ))}
          </div>
          <div style={{ fontFamily: 'var(--mono, ui-monospace, monospace)', fontSize: 12.5, lineHeight: 1.7, fontVariantNumeric: 'tabular-nums' }}>
            <URow label="RAM" s={w.ram} unit={` / ${data.ramTotalGb} GB`} />
            <URow label="CPU" s={w.cpu} unit="%" />
            <URow label="Temp" s={w.temp} unit="°C" />
            <URow label="Disk" s={w.disk} unit={` / ${data.diskTotalGb} GB`} />
          </div>
          <Spark data={data.ramSpark} />
          <div className="set-hint" style={{ fontSize: 11, marginTop: 2 }}>{w.samples} samples · RAM over recent readings ↑</div>
        </div>
      )}
    </div>
  )
}

// Push main's extension jars (incl. beta/custom sideloads) to a worker so it can download the same sources.
function SyncButton({ name }: { name: string }) {
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [errs, setErrs] = useState<string[]>([])
  async function go() {
    setBusy(true); setMsg(''); setErrs([])
    try {
      const r = await api.syncWorkerExtensions(name)
      setMsg(`Synced ${r.synced}/${r.total}${r.failed ? ` (${r.failed} failed)` : ''}`)
      setErrs(r.errors || [])
    } catch { setMsg('Sync failed') } finally { setBusy(false) }
  }
  return (
    <>
      <button className="btn" disabled={busy} onClick={go}>{busy ? 'Syncing…' : 'Sync extensions'}</button>
      {msg && <span className="set-hint" style={{ marginLeft: 8, alignSelf: 'center' }}>{msg}</span>}
      {/* Show WHICH jars failed and why ("name.jar: reason"), not just a count. */}
      {errs.length > 0 && (
        <div style={{ flexBasis: '100%', marginTop: 6, display: 'flex', flexDirection: 'column', gap: 3 }}>
          {errs.map((e, i) => (
            <div key={i} className="set-hint" style={{ color: 'var(--danger, #e5484d)', fontSize: 12, wordBreak: 'break-word' }}>{e}</div>
          ))}
        </div>
      )}
    </>
  )
}

// Per-worker Tailscale exit-node switch: None (normal egress) or a chosen exit node.
function ExitNodeControl({ name, onChanged }: { name: string; onChanged?: () => void }) {
  const [data, setData] = useState<ExitNodesDto | null>(null)
  const [busy, setBusy] = useState(false)
  const load = () => api.workerExitNodes(name).then(setData).catch(() => setData({ available: false, current: '', nodes: [], error: 'unreachable' }))
  useEffect(() => { load() }, [name])
  if (!data) return <div className="set-hint" style={{ marginTop: 10 }}>Exit node: checking…</div>
  if (!data.available) return <div className="set-hint" style={{ marginTop: 10 }}>Exit node: Tailscale not available on this node</div>
  const currentIp = data.nodes.find((n) => n.current)?.ip ?? ''
  async function choose(ip: string) {
    setBusy(true)
    // workerSetExit returns only after the node has switched + re-probed egress, so refresh both the
    // exit-node list and the parent worker card (new egress IP) right away - no page reload needed.
    try { await api.workerSetExit(name, ip); await load(); onChanged?.() } finally { setBusy(false) }
  }
  return (
    <div style={{ marginTop: 10 }}>
      <div className="set-row-label">Exit node (egress IP)</div>
      <select className="set-input" value={currentIp} disabled={busy} onChange={(e) => choose(e.target.value)}>
        <option value="">None (normal egress)</option>
        {data.nodes.map((n) => <option key={n.ip} value={n.ip}>{n.name}{n.online ? '' : ' (offline)'}</option>)}
      </select>
    </div>
  )
}

// Human "2h 14m" from seconds.
function uptime(s?: number | null): string {
  if (!s || s <= 0) return ''
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}
const pct = (n?: number | null) => (n == null ? '-' : `${n}%`)
const temp = (n?: number | null) => (n == null ? '-' : `${n}°C`)
const gb = (n?: number | null) => (n == null ? '-' : `${n} GB`)

function Metric({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
      <span style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em' }}>{label}</span>
      <span style={{ fontSize: 15, fontWeight: 600, color: warn ? 'var(--danger, #e5484d)' : 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{value}</span>
    </div>
  )
}

export default function Workers() {
  const nav = useNavigate()
  const [workers, setWorkers] = useState<WorkerStatus[]>([])
  const [loaded, setLoaded] = useState(false)
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [token, setToken] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [test, setTest] = useState<WorkerStatus | null>(null)
  const [dispatch, setDispatch] = useState(false) // master toggle: route downloads through a worker
  const [pick, setPick] = useState('') // "" = Auto, else a node name
  const timer = useRef<ReturnType<typeof setInterval>>()

  const load = () => api.workers().then((w) => { setWorkers(w); setLoaded(true) }).catch(() => setLoaded(true))
  useEffect(() => {
    load()
    api.getSettings().then((s) => { setDispatch(s.workerDispatch); setPick(s.workerNode) }).catch(() => {})
    timer.current = setInterval(load, 5000) // live health; workers are polled server-side each call
    return () => clearInterval(timer.current)
  }, [])

  async function toggleDispatch() { const v = !dispatch; setDispatch(v); await api.saveSettings({ workerDispatch: v }).catch(() => setDispatch(!v)) }
  async function choose(n: string) { setPick(n); await api.saveSettings({ workerNode: n }).catch(() => {}) }

  async function add() {
    setErr(''); setBusy(true)
    try {
      await api.addWorker(name.trim(), url.trim(), token.trim())
      setName(''); setUrl(''); setToken(''); setTest(null)
      await load()
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not add worker')
    } finally { setBusy(false) }
  }
  async function doTest() {
    if (!url.trim()) return
    setErr(''); setBusy(true); setTest(null)
    try { setTest(await api.testWorker(url.trim(), token.trim())) } finally { setBusy(false) }
  }
  async function toggle(w: WorkerStatus) { await api.enableWorker(w.name, !w.enabled); load() }
  async function remove(w: WorkerStatus) {
    if (!confirm(`Remove worker "${w.name}"?`)) return
    await api.removeWorker(w.name); load()
  }

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Worker nodes</span>
      </div>

      <div className="set-hint" style={{ margin: '0 0 14px' }}>
        Headless nodes (run with <code>WORKER=1</code>) that fetch page images from their own IP. Add one
        by its tailnet URL and token; downloads route through online nodes automatically.
      </div>

      {/* Master toggle + node selector */}
      <div className="wh-card">
        <button className="set-toggle" onClick={toggleDispatch}>
          <div>
            <div className="set-row-label">Route downloads through a worker</div>
            <div className="set-hint">Off = always download on the main server. On = delegate chapters to the node below.</div>
          </div>
          <span className={'switch' + (dispatch ? ' on' : '')}><span className="knob" /></span>
        </button>
        {dispatch && (
          <div style={{ marginTop: 10 }}>
            <div className="set-row-label">Node</div>
            <select className="set-input" value={pick} onChange={(e) => choose(e.target.value)}>
              <option value="">Auto (any online)</option>
              {workers.filter((w) => w.enabled).map((w) => (
                <option key={w.name} value={w.name}>{w.name}{w.online ? '' : ' (offline)'}</option>
              ))}
            </select>
          </div>
        )}
      </div>

      {/* Add / test */}
      <div className="wh-card">
        <div className="set-row-label">Add a worker</div>
        <input className="set-input" placeholder="Name (e.g. orangepi-1)" value={name} onChange={(e) => setName(e.target.value)} spellCheck={false} autoCapitalize="off" />
        <input className="set-input" placeholder="URL (e.g. http://100.x.y.z:8790)" value={url} onChange={(e) => setUrl(e.target.value)} spellCheck={false} autoCapitalize="off" autoCorrect="off" style={{ marginTop: 8 }} />
        <input className="set-input" type="password" placeholder="Token (WORKER_TOKEN, blank if none)" value={token} onChange={(e) => setToken(e.target.value)} spellCheck={false} autoCapitalize="off" autoCorrect="off" style={{ marginTop: 8 }} />
        <div className="set-actions" style={{ gap: 8 }}>
          <button className="btn" disabled={busy || !url.trim()} onClick={doTest}>Test</button>
          <button className="btn primary" disabled={busy || !name.trim() || !url.trim()} onClick={add}>Add worker</button>
        </div>
        {err && <div className="set-hint" style={{ color: 'var(--danger, #e5484d)' }}>{err}</div>}
        {test && (
          <div className="set-hint" style={{ marginTop: 4 }}>
            {test.online
              ? `✓ Online · egress ${test.egressIp || '?'}${test.version ? ` · v${test.version}` : ''}`
              : `✗ Offline · ${test.error || 'unreachable'}`}
          </div>
        )}
      </div>

      {/* List */}
      {loaded && workers.length === 0 && <div className="center-msg" style={{ marginTop: 24 }}>No workers yet. Add one above.</div>}
      {workers.map((w) => (
        <div className="wh-card" key={w.name} style={{ opacity: w.enabled ? 1 : 0.55 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ width: 10, height: 10, borderRadius: '50%', flexShrink: 0, background: w.checking ? '#8b95a1' : w.online ? '#30a46c' : '#e5484d' }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 700 }}>{w.name}{!w.enabled && <span style={{ fontSize: 11, color: 'var(--muted)', fontWeight: 400 }}> · disabled</span>}</div>
              <div style={{ fontSize: 12, color: 'var(--muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{w.url}</div>
            </div>
            <button className="set-toggle" style={{ width: 'auto' }} onClick={() => toggle(w)} aria-label="Enable/disable">
              <span className={'switch' + (w.enabled ? ' on' : '')}><span className="knob" /></span>
            </button>
          </div>

          {w.online ? (
            <>
              {/* Egress IP is a full IPv4 - give it its own line so it can't overflow a narrow metric cell. */}
              <div style={{ marginTop: 12, fontSize: 13 }}>
                <span style={{ color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', fontSize: 11 }}>Egress IP </span>
                <b style={{ fontVariantNumeric: 'tabular-nums' }}>{w.egressIp || '-'}</b>
              </div>
              {/* RAM as used / total GB (+ bar) - percent alone doesn't tell you what a future node needs. */}
              <div style={{ marginTop: 8, fontSize: 13 }}>
                <span style={{ color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', fontSize: 11 }}>RAM </span>
                <b style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {gb(w.metrics?.memUsedGb)}{w.metrics?.memTotalGb != null ? ` / ${gb(w.metrics.memTotalGb)}` : ''}
                </b>
                {w.metrics?.memPct != null && <span className="set-hint" style={{ marginLeft: 6 }}>({w.metrics.memPct}% used)</span>}
                {w.metrics?.memPct != null && (
                  <div style={{ marginTop: 5, height: 5, borderRadius: 3, background: 'var(--border, #2a2a2a)', overflow: 'hidden' }}>
                    <div style={{ width: `${Math.min(100, w.metrics.memPct)}%`, height: '100%', background: w.metrics.memPct > 90 ? 'var(--danger, #e5484d)' : 'var(--accent, #7c6cff)' }} />
                  </div>
                )}
              </div>
              {/* Disk gets its own line (for the downloads volume) with room for free / total + used% and a
                  usage bar - it's the metric that matters most for a worker, and will grow once SSDs land. */}
              <div style={{ marginTop: 8, fontSize: 13 }}>
                <span style={{ color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', fontSize: 11 }}>Disk (downloads) </span>
                <b style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {gb(w.metrics?.diskFreeGb)} free{w.metrics?.diskTotalGb != null ? ` / ${gb(w.metrics.diskTotalGb)}` : ''}
                </b>
                {w.metrics?.diskPct != null && <span className="set-hint" style={{ marginLeft: 6 }}>({w.metrics.diskPct}% used)</span>}
                {w.metrics?.diskPct != null && (
                  <div style={{ marginTop: 5, height: 5, borderRadius: 3, background: 'var(--border, #2a2a2a)', overflow: 'hidden' }}>
                    <div style={{ width: `${Math.min(100, w.metrics.diskPct)}%`, height: '100%', background: w.metrics.diskPct > 90 ? 'var(--danger, #e5484d)' : 'var(--accent, #7c6cff)' }} />
                  </div>
                )}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(68px, 1fr))', gap: 12, marginTop: 10 }}>
                <Metric label="CPU" value={pct(w.metrics?.cpuPct)} warn={(w.metrics?.cpuPct ?? 0) > 90} />
                <Metric label="Temp" value={temp(w.metrics?.tempC)} warn={(w.metrics?.tempC ?? 0) > 80} />
                <Metric label="In-flight" value={w.inflight == null ? '-' : String(w.inflight)} />
                <Metric label="Load" value={w.metrics?.load1 == null ? '-' : String(w.metrics.load1)} />
                <Metric label="Up" value={uptime(w.uptimeS) || '-'} />
              </div>
            </>
          ) : w.checking ? (
            <div className="set-hint" style={{ marginTop: 8 }}>Checking…</div>
          ) : (
            <div className="set-hint" style={{ color: 'var(--danger, #e5484d)', marginTop: 8 }}>Offline · {w.error || 'unreachable'}</div>
          )}

          {w.online && <ExitNodeControl name={w.name} onChanged={load} />}
          {w.online && <UsagePanel name={w.name} />}
          {w.online && <ExtList name={w.name} />}

          <div className="set-actions" style={{ marginTop: 10, flexWrap: 'wrap' }}>
            {w.online && <SyncButton name={w.name} />}
            <button className="btn danger" onClick={() => remove(w)}>Remove</button>
          </div>
        </div>
      ))}
    </div>
  )
}
