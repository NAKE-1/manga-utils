import { useEffect, useRef, useState } from 'react'
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

export function Downloads() {
  const [data, setData] = useState<DownloadsT | null>(null)
  const [eta, setEta] = useState(0)
  const rate = useRef({ t: 0, done: 0, rate: 0 })

  useEffect(() => {
    let alive = true
    const tick = async () => {
      const d = await api.downloads().catch(() => null)
      if (!alive || !d) return
      // pages/sec (smoothed) over remaining chapter-pages (chapters not yet started use the average).
      const totalDone = d.tasks.reduce((a, t) => a + t.pagesDone, 0)
      const now = Date.now()
      const p = rate.current
      let r = p.rate
      if (p.t > 0) { const dt = (now - p.t) / 1000; if (dt > 0.4) { const inst = Math.max(0, (totalDone - p.done) / dt); r = p.rate > 0 ? p.rate * 0.6 + inst * 0.4 : inst } }
      rate.current = { t: now, done: totalDone, rate: r }
      let remaining = 0
      for (const t of d.tasks) {
        if (t.state !== 'running' && t.state !== 'queued') continue
        const inCur = t.pagesTotal > 0 ? t.pagesTotal - t.pagesDone : 0
        const avg = t.pagesTotal > 0 ? t.pagesTotal : 18
        const chaptersLeft = Math.max(0, t.total - t.done - 1) // chapters after the current one
        remaining += inCur + chaptersLeft * avg
      }
      setEta(d.active > 0 && r > 0.05 ? remaining / r : 0)
      setData(d)
    }
    tick()
    const tm = setInterval(tick, 1000)
    return () => { alive = false; clearInterval(tm) }
  }, [])

  async function stopAll() { setData(await api.stopAllDownloads().then((r) => r.json()).catch(() => data)) }
  async function clearFinished() { setData(await api.clearDownloads().then((r) => r.json()).catch(() => data)) }
  async function stop(t: DlTask) { setData(await api.stopDownload(t.id).then((r) => r.json()).catch(() => data)) }
  async function retry(t: DlTask) {
    if (!t.failedChapters.length) return
    const i = t.mangaKey.indexOf('|'); const sid = t.mangaKey.slice(0, i); const mangaUrl = t.mangaKey.slice(i + 1)
    await api.clearDownloads().catch(() => {}) // drop finished rows so the retried task doesn't double-up
    await api.enqueueDownload(sid, mangaUrl, t.mangaTitle, t.failedChapters).catch(() => {})
    setData(await api.downloads().catch(() => data))
  }

  if (!data) return <div className="spinner" />
  const tasks = data.tasks

  return (
    <>
      <div className="list-head">
        <span className="list-title">Downloads{data.active > 0 ? ` · ${data.active} active` : ''}</span>
        <div className="dl-head-actions">
          {data.active > 0 && <button className="dl-link" onClick={stopAll}>Stop all</button>}
          {tasks.some((t) => t.state === 'done' || t.state === 'failed' || t.state === 'stopped') && <button className="dl-link" onClick={clearFinished}>Clear finished</button>}
        </div>
      </div>
      {(data.totalKbps > 0 || eta > 0) && (
        <div className="update-msg">{data.totalKbps > 0 ? fmtSpeed(data.totalKbps) : ''}{data.totalKbps > 0 && eta > 0 ? ' · ' : ''}{eta > 0 ? fmtEta(eta) : ''}</div>
      )}

      {tasks.length === 0 ? (
        <div className="center-msg">No downloads. Use the download button on a manga.</div>
      ) : (
        <div className="dl-list">{tasks.map((t) => <TaskCard key={t.id} t={t} onStop={() => stop(t)} onRetry={() => retry(t)} />)}</div>
      )}
    </>
  )
}

function TaskCard({ t, onStop, onRetry }: { t: DlTask; onStop: () => void; onRetry: () => void }) {
  const running = t.state === 'running' || t.state === 'queued'
  const queued = t.state === 'queued'
  const failed = t.state === 'failed'
  // Bar = finished chapters + the fraction of the chapter in progress.
  const cur = t.pagesTotal > 0 ? t.pagesDone / t.pagesTotal : 0
  const pct = t.total > 0 ? Math.round(((t.done + (running ? cur : 0)) / t.total) * 100) : 0
  // Which chapter we're on: finished count + the one in progress.
  const chapterNo = Math.min(t.total, t.done + (t.currentChapter ? 1 : 0))
  const failedNames = t.failedChapters.map((c) => c.name).filter(Boolean)
  const failedList = failedNames.slice(0, 4).join(', ') + (failedNames.length > 4 ? `, +${failedNames.length - 4} more` : '')
  return (
    <div className="dlc">
      <div className="dlc-top">
        <div className="dlc-title">{t.mangaTitle}</div>
        {running
          ? <button className="dl-link" onClick={onStop}>Stop</button>
          : failed && t.failedChapters.length
            ? <button className="dl-link" onClick={onRetry}>Retry {t.failed}</button>
            : <span className={'dl-state ' + (failed || t.state === 'stopped' ? 'failed' : 'done')}>{t.state === 'stopped' ? 'Stopped' : failed ? 'Failed' : 'Done'}</span>}
      </div>
      <div className="dlc-sub">
        <span>
          {queued ? 'Queued' : running
            ? `Chapter ${chapterNo} of ${t.total}${t.currentChapter ? ` · ${t.currentChapter}` : ''}`
            : `${t.done}/${t.total} chapter${t.total === 1 ? '' : 's'}${failed ? (t.failed > 0 ? ` · ${t.failed} failed` : ' · failed') : ' done'}`}
        </span>
        {running && t.pagesTotal > 0 && <span className="dlc-count">{t.pagesDone}/{t.pagesTotal}{t.kbps > 0 ? ` · ${fmtSpeed(t.kbps)}` : ''}</span>}
      </div>
      <div className="dlc-bar"><div className={'dlc-fill ' + (failed ? 'failed' : '')} style={{ width: pct + '%' }} /></div>
      {failed && (failedList || t.error) && <div className="dlc-foot err">{failedList ? `Failed: ${failedList}` : t.error}</div>}
    </div>
  )
}
