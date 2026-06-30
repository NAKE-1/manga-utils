import { useEffect, useMemo, useRef, useState } from 'react'
import { api, DlTask, Downloads as DownloadsT } from '../api'

const fmtSpeed = (kbps: number) => (kbps >= 1024 ? `${(kbps / 1024).toFixed(1)} MB/s` : `${Math.round(kbps)} KB/s`)
function fmtEta(s: number): string {
  if (!isFinite(s) || s <= 0) return ''
  s = Math.round(s)
  if (s < 60) return `~${s}s left`
  const m = Math.floor(s / 60)
  if (m < 60) return `~${m}m ${s % 60}s left`
  const h = Math.floor(m / 60)
  return `~${h}h ${m % 60}m left`
}

type Series = { key: string; title: string; tasks: DlTask[] }

export function Downloads() {
  const [data, setData] = useState<DownloadsT | null>(null)
  const [eta, setEta] = useState(0)
  const rate = useRef({ t: 0, done: 0, rate: 0 })

  useEffect(() => {
    let alive = true
    const tick = async () => {
      const d = await api.downloads().catch(() => null)
      if (!alive || !d) return
      // pages/sec (smoothed) -> ETA over remaining pages (queued chapters use the average page count)
      const totalDone = d.tasks.reduce((a, t) => a + t.pagesDone, 0)
      const now = Date.now()
      const p = rate.current
      let r = p.rate
      if (p.t > 0) { const dt = (now - p.t) / 1000; if (dt > 0.4) { const inst = Math.max(0, (totalDone - p.done) / dt); r = p.rate > 0 ? p.rate * 0.6 + inst * 0.4 : inst } }
      rate.current = { t: now, done: totalDone, rate: r }
      const totals = d.tasks.filter((t) => t.pagesTotal > 0).map((t) => t.pagesTotal)
      const avg = totals.length ? totals.reduce((a, b) => a + b, 0) / totals.length : 18
      let remaining = 0
      for (const t of d.tasks) { if (t.state === 'done' || t.state === 'failed' || t.state === 'stopped') continue; remaining += t.pagesTotal > 0 ? t.pagesTotal - t.pagesDone : avg }
      setEta(d.active > 0 && r > 0.05 ? remaining / r : 0)
      setData(d)
    }
    tick()
    const tm = setInterval(tick, 1000)
    return () => { alive = false; clearInterval(tm) }
  }, [])

  const series: Series[] = useMemo(() => {
    const map = new Map<string, Series>()
    for (const t of data?.tasks ?? []) {
      const g = map.get(t.mangaKey) ?? { key: t.mangaKey, title: t.mangaTitle, tasks: [] }
      g.tasks.push(t); map.set(t.mangaKey, g)
    }
    return [...map.values()]
  }, [data])

  async function stopAll() { setData(await api.stopAllDownloads().then((r) => r.json()).catch(() => data)) }
  async function clearFinished() { setData(await api.clearDownloads().then((r) => r.json()).catch(() => data)) }
  async function stopSeries(s: Series) {
    for (const t of s.tasks) if (t.state === 'running' || t.state === 'queued') await api.stopDownload(t.id)
    setData(await api.downloads().catch(() => data))
  }

  if (!data) return <div className="spinner" />

  return (
    <>
      <div className="list-head">
        <span className="list-title">Downloads{data.active > 0 ? ` · ${data.active} active` : ''}</span>
        <div className="dl-head-actions">
          {data.active > 0 && <button className="dl-link" onClick={stopAll}>Stop all</button>}
          {series.some((s) => s.tasks.some((t) => t.state === 'done' || t.state === 'failed' || t.state === 'stopped')) && <button className="dl-link" onClick={clearFinished}>Clear finished</button>}
        </div>
      </div>
      {(data.totalKbps > 0 || eta > 0) && (
        <div className="update-msg">{data.totalKbps > 0 ? fmtSpeed(data.totalKbps) : ''}{data.totalKbps > 0 && eta > 0 ? ' · ' : ''}{eta > 0 ? fmtEta(eta) : ''}</div>
      )}

      {series.length === 0 ? (
        <div className="center-msg">No downloads. Use the download button on a manga.</div>
      ) : (
        <div className="dl-list">{series.map((s) => <SeriesCard key={s.key} s={s} onStop={() => stopSeries(s)} />)}</div>
      )}
    </>
  )
}

function SeriesCard({ s, onStop }: { s: Series; onStop: () => void }) {
  const total = s.tasks.length
  const done = s.tasks.filter((t) => t.state === 'done').length
  const running = s.tasks.some((t) => t.state === 'running' || t.state === 'queued')
  const failed = s.tasks.some((t) => t.state === 'failed' || t.state === 'stopped')
  const frac = s.tasks.reduce((a, t) => a + (t.state === 'done' ? 1 : t.pagesTotal > 0 ? t.pagesDone / t.pagesTotal : 0), 0) / total
  const pct = Math.round(frac * 100)
  const speed = s.tasks.filter((t) => t.state === 'running').reduce((a, t) => a + t.kbps, 0)
  return (
    <div className="dlc">
      <div className="dlc-top">
        <div className="dlc-title">{s.title}</div>
        {running ? <button className="dl-link" onClick={onStop}>Stop</button> : <span className={'dl-state ' + (failed ? 'failed' : 'done')}>{failed ? 'Incomplete' : 'Done'}</span>}
      </div>
      <div className="dlc-sub">
        <span>{done}/{total} chapter{total === 1 ? '' : 's'}{running ? ' · downloading' : ''}</span>
        {running && speed > 0 && <span className="dlc-count">{fmtSpeed(speed)}</span>}
      </div>
      <div className="dlc-bar"><div className={'dlc-fill ' + (failed && !running ? 'failed' : '')} style={{ width: pct + '%' }} /></div>
    </div>
  )
}
