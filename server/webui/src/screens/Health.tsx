import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type HealthReport, type HealthSource, type DiagResult } from '../api'
import { IconArrowLeft } from '../components/icons'

function ago(ts: number): string {
  if (!ts) return 'never'
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (s < 60) return 'just now'
  const m = Math.floor(s / 60); if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60); if (h < 24) return `${h}h ago`
  return `${Math.floor(h / 24)}d ago`
}

type State = { key: 'ok' | 'degraded' | 'down'; label: string }
function statusOf(h: HealthSource): State {
  if (h.down || h.cfState === 'red') return { key: 'down', label: h.cfState === 'red' ? 'Cloudflare — no bypass' : 'Unreachable' }
  if (h.imagesDown) return { key: 'degraded', label: 'Images down' }
  if (h.cfState === 'orange') return { key: 'degraded', label: 'Cloudflare (bypass on)' }
  if (h.circuitApiOpen || h.circuitImagesOpen) return { key: 'degraded', label: 'Recovering' }
  return { key: 'ok', label: 'OK' }
}

export default function Health() {
  const nav = useNavigate()
  const [rep, setRep] = useState<HealthReport | null>(null)
  const [sweep, setSweep] = useState<{ done: number; total: number } | null>(null)
  const [tests, setTests] = useState<Record<string, DiagResult | 'run'>>({})
  const alive = useRef(true)

  async function load() { setRep(await api.healthSources().catch(() => null)) }
  useEffect(() => { alive.current = true; load(); return () => { alive.current = false } }, [])

  async function runSweep() {
    const p = await api.runHealthSweep().catch(() => null)
    if (!p) return
    setSweep({ done: p.done, total: p.total })
    const poll = setInterval(async () => {
      const s = await api.sweepProgress().catch(() => null)
      if (!s || !alive.current) { clearInterval(poll); return }
      setSweep({ done: s.done, total: s.total })
      if (!s.running) { clearInterval(poll); setSweep(null); load() }
    }, 800)
  }

  async function test(id: string) {
    setTests((t) => ({ ...t, [id]: 'run' }))
    const r = await api.diag(id).catch(() => null)
    setTests((t) => ({ ...t, [id]: r ?? { source: '', baseUrl: '', pingMs: 0, speedMbps: 0, sampleBytes: 0, ok: false, error: 'failed' } }))
  }

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Source health</span>
      </div>

      {rep === null ? <div className="spinner" /> : (
        <>
          <div className="dm-overview">
            <div className="dm-stat"><span className="dm-stat-n hl-ok">{rep.healthy}</span><span className="dm-stat-l">healthy</span></div>
            <div className="dm-stat"><span className="dm-stat-n hl-deg">{rep.degraded}</span><span className="dm-stat-l">degraded</span></div>
            <div className="dm-stat"><span className="dm-stat-n hl-down">{rep.down}</span><span className="dm-stat-l">down</span></div>
          </div>

          <div className="hl-bar">
            {sweep ? (
              <div className="hl-sweep">
                <span>Checking… {sweep.done}/{sweep.total}</span>
                <div className="dlc-bar"><div className="dlc-fill" style={{ width: (sweep.total > 0 ? Math.round((sweep.done / sweep.total) * 100) : 5) + '%' }} /></div>
              </div>
            ) : (
              <button className="btn primary" onClick={runSweep}>Run health check</button>
            )}
          </div>

          <div className="hl-list">
            {rep.sources.map((h) => {
              const st = statusOf(h)
              const t = tests[h.id]
              return (
                <div key={h.id} className={'hl-card ' + st.key}>
                  <span className={'hl-dot ' + st.key} />
                  <div className="hl-main">
                    <div className="hl-name">{h.name}{h.usesWebView && <span className="hl-wv" title="Uses in-app browser">jb</span>}</div>
                    <div className="hl-sub">
                      {st.label}
                      {h.lastOkMs > 0 && <> · last ok {ago(h.lastOkMs)}</>}
                      {h.lastPingMs > 0 && <> · {h.lastPingMs}ms</>}
                    </div>
                    {t && t !== 'run' && (
                      <div className={'hl-test' + (t.ok ? '' : ' err')}>{t.ok ? `${Math.round(t.pingMs)}ms · ${t.speedMbps.toFixed(1)} Mbps` : (t.error || 'test failed')}</div>
                    )}
                  </div>
                  <button className="hl-testbtn" disabled={t === 'run'} onClick={() => test(h.id)}>{t === 'run' ? '…' : 'Test'}</button>
                </div>
              )
            })}
          </div>
        </>
      )}
    </div>
  )
}
