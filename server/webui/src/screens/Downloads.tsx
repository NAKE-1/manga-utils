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
    await api.removeDownload(t.id).catch(() => {}) // drop ONLY this row so a retry doesn't double-up (keep other failed rows)
    await api.enqueueDownload(sid, mangaUrl, t.mangaTitle, t.failedChapters).catch(() => {})
    setData(await api.downloads().catch(() => data))
  }

  async function move(t: DlTask, dir: 'up' | 'down') { setData(await api.moveDownload(t.id, dir).then((r) => r.json()).catch(() => data)) }
  async function resume(t: DlTask) { setData(await api.resumeDownload(t.id).then((r) => r.json()).catch(() => data)) }
  async function resumeAll() { setData(await api.resumeAllDownloads().then((r) => r.json()).catch(() => data)) }

  if (!data) return <div className="spinner" />
  const tasks = data.tasks
  const queuedIds = tasks.filter((t) => t.state === 'queued').map((t) => t.id)

  return (
    <>
      <div className="list-head">
        <span className="list-title">Downloads{data.active > 0 ? ` · ${data.active} active` : ''}</span>
        <div className="dl-head-actions">
          {tasks.some((t) => t.state === 'interrupted') && <button className="dl-link dl-resume" onClick={resumeAll}>Resume all</button>}
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
        <div className="dl-list">{tasks.map((t) => {
          const qi = queuedIds.indexOf(t.id)
          return <TaskCard key={t.id} t={t} onStop={() => stop(t)} onRetry={() => retry(t)} onResume={() => resume(t)}
            onMove={(dir) => move(t, dir)} canUp={qi > 0} canDown={qi >= 0 && qi < queuedIds.length - 1} />
        })}</div>
      )}
    </>
  )
}

function TaskCard({ t, onStop, onRetry, onResume, onMove, canUp, canDown }: { t: DlTask; onStop: () => void; onRetry: () => void; onResume: () => void; onMove: (dir: 'up' | 'down') => void; canUp: boolean; canDown: boolean }) {
  const running = t.state === 'running' || t.state === 'queued'
  const queued = t.state === 'queued'
  const failed = t.state === 'failed'
  const interrupted = t.state === 'interrupted'
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
        <div className="dlc-title">{t.tag === 'migration' && <span className="dlc-m" title="Migration download">M</span>}{t.mangaTitle}</div>
        <div className="dlc-actions">
          {queued && (
            <span className="dlc-reorder">
              <button className="dlc-move" disabled={!canUp} onClick={() => onMove('up')} aria-label="Move up">▲</button>
              <button className="dlc-move" disabled={!canDown} onClick={() => onMove('down')} aria-label="Move down">▼</button>
            </span>
          )}
          {running
            ? <button className="dl-link" onClick={onStop}>Stop</button>
            : interrupted
              ? <button className="dl-link dl-resume" onClick={onResume}>Resume</button>
              : failed && t.failedChapters.length
                ? <button className="dl-link" onClick={onRetry}>Retry {t.failed}</button>
                : <span className={'dl-state ' + (failed || t.state === 'stopped' ? 'failed' : 'done')}>{t.state === 'stopped' ? 'Stopped' : failed ? 'Failed' : 'Done'}</span>}
        </div>
      </div>
      <div className="dlc-sub">
        <span>
          {interrupted ? `Interrupted · ${t.done}/${t.total} done — tap Resume` : queued ? 'Queued' : running
            ? `Chapter ${chapterNo} of ${t.total}${t.currentChapter ? ` · ${t.currentChapter}` : ''}`
            : `${t.done}/${t.total} chapter${t.total === 1 ? '' : 's'}${failed ? (t.failed > 0 ? ` · ${t.failed} failed` : ' · failed') : ' done'}`}
        </span>
        {running && t.pagesTotal > 0 && <span className="dlc-count">{t.pagesDone}/{t.pagesTotal}{t.kbps > 0 ? ` · ${fmtSpeed(t.kbps)}` : ''}</span>}
      </div>
      <div className="dlc-bar"><div className={'dlc-fill ' + (failed ? 'failed' : '')} style={{ width: pct + '%' }} /></div>
      {/* Which chapters failed AND why. The reason is what tells you whether retrying is worth it,
          so it can't be dropped just because we also have a list of names. */}
      {failed && (failedList || t.error) && (
        <div className="dlc-foot err">
          {failedList && <div>Failed: {failedList}</div>}
          {t.error && <div className="dlc-why">{t.error}</div>}
        </div>
      )}
    </div>
  )
}
