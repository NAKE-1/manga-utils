import { useEffect, useRef, useState } from 'react'
import { api, DlTask, Downloads as DownloadsT } from '../api'
import { WebviewModal } from '../components/WebviewModal'

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
// Time until a parked (retrywait) task re-runs. Switches to seconds as it counts down.
function fmtCountdown(ms: number): string {
  if (ms <= 0) return 'now'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return `${m}m ${(s % 60).toString().padStart(2, '0')}s`
}

export function Downloads() {
  const [data, setData] = useState<DownloadsT | null>(null)
  const [eta, setEta] = useState(0)
  const [tab, setTab] = useState('all')       // 'all' or a sourceId — filters the active list
  const [showDone, setShowDone] = useState(false) // Completed section expanded?
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

  async function forceRetry(t: DlTask) { setData(await api.forceRetryDownload(t.id).then((r) => r.json()).catch(() => data)) }
  async function move(t: DlTask, dir: 'up' | 'down') { setData(await api.moveDownload(t.id, dir).then((r) => r.json()).catch(() => data)) }
  async function resume(t: DlTask) { setData(await api.resumeDownload(t.id).then((r) => r.json()).catch(() => data)) }
  async function resumeAll() { setData(await api.resumeAllDownloads().then((r) => r.json()).catch(() => data)) }

  if (!data) return <div className="spinner" />
  const tasks = data.tasks
  const sourceOf = (t: DlTask) => t.sourceId || t.mangaKey.split('|')[0]
  // Live work vs history. Interrupted (post-restart, awaiting Resume) counts as live — it needs attention.
  const active = tasks.filter((t) => t.state === 'running' || t.state === 'queued' || t.state === 'retrywait' || t.state === 'interrupted' || t.state === 'waitvf')
  const completed = tasks.filter((t) => t.state === 'done' || t.state === 'failed' || t.state === 'stopped')
  const queuedIds = tasks.filter((t) => t.state === 'queued').map((t) => t.id) // global order — reorder is global

  // One tab per source that has active work, with a count. Only shown when there's live work.
  const bySource = new Map<string, { id: string; name: string; count: number }>()
  for (const t of active) {
    const id = sourceOf(t)
    const e = bySource.get(id) || { id, name: t.sourceName || id, count: 0 }
    e.count++; bySource.set(id, e)
  }
  const sources = [...bySource.values()].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
  const curTab = tab === 'all' || sources.some((s) => s.id === tab) ? tab : 'all' // fall back if a source finished
  const shown = curTab === 'all' ? active : active.filter((t) => sourceOf(t) === curTab)

  const card = (t: DlTask, reorder: boolean) => {
    const qi = reorder ? queuedIds.indexOf(t.id) : -1
    return <TaskCard key={t.id} t={t} onStop={() => stop(t)} onRetry={() => retry(t)} onResume={() => resume(t)}
      onForce={() => forceRetry(t)} onMove={(dir) => move(t, dir)} canUp={qi > 0} canDown={qi >= 0 && qi < queuedIds.length - 1} />
  }

  return (
    <>
      <div className="list-head">
        <span className="list-title">Downloads{data.active > 0 ? ` · ${data.active} active` : ''}</span>
        <div className="dl-head-actions">
          {active.some((t) => t.state === 'interrupted') && <button className="dl-link dl-resume" onClick={resumeAll}>Resume all</button>}
          {data.active > 0 && <button className="dl-link" onClick={stopAll}>Stop all</button>}
        </div>
      </div>
      {(data.totalKbps > 0 || eta > 0) && (
        <div className="update-msg">{data.totalKbps > 0 ? fmtSpeed(data.totalKbps) : ''}{data.totalKbps > 0 && eta > 0 ? ' · ' : ''}{eta > 0 ? fmtEta(eta) : ''}</div>
      )}

      {/* Per-source tabs. Only worth showing when more than one source is active; a single source is just a list. */}
      {sources.length > 1 && (
        <div className="dl-tabs">
          <button className={'dl-tab' + (curTab === 'all' ? ' on' : '')} onClick={() => setTab('all')}>All<span className="dl-tab-n">{active.length}</span></button>
          {sources.map((s) => (
            <button key={s.id} className={'dl-tab' + (curTab === s.id ? ' on' : '')} onClick={() => setTab(s.id)}>{s.name}<span className="dl-tab-n">{s.count}</span></button>
          ))}
        </div>
      )}

      {active.length === 0 && completed.length === 0 ? (
        <div className="center-msg">No downloads. Use the download button on a manga.</div>
      ) : (
        <>
          {shown.length > 0
            ? <div className="dl-list">{shown.map((t) => card(t, true))}</div>
            : active.length === 0 && <div className="dl-empty-active">No active downloads.</div>}

          {completed.length > 0 && (
            <div className="dl-done">
              <div className="dl-done-h">
                <button className="dl-done-toggle" onClick={() => setShowDone((v) => !v)}>
                  {showDone ? '▾' : '▸'} Completed ({completed.length})
                </button>
                <button className="dl-link" onClick={clearFinished}>Clear finished</button>
              </div>
              {showDone && <div className="dl-list">{completed.map((t) => card(t, false))}</div>}
            </div>
          )}
        </>
      )}
    </>
  )
}

function TaskCard({ t, onStop, onRetry, onResume, onForce, onMove, canUp, canDown }: { t: DlTask; onStop: () => void; onRetry: () => void; onResume: () => void; onForce: () => void; onMove: (dir: 'up' | 'down') => void; canUp: boolean; canDown: boolean }) {
  const running = t.state === 'running' || t.state === 'queued'
  const queued = t.state === 'queued'
  const failed = t.state === 'failed'
  const interrupted = t.state === 'interrupted'
  // Blocked on a "verify you're human" captcha: holds (no timer) until the user solves it in the WebView.
  const waitvf = t.state === 'waitvf'
  const [solving, setSolving] = useState(false)
  // Parked after a transient (rate-limit / busy-source) failure: waiting out a cooldown, then re-runs itself.
  const parked = t.state === 'retrywait'
  const retryIn = parked ? (t.retryAt || 0) - Date.now() : 0
  // Queued behind a source that's resting after a rate-limit — show when it'll get its turn, not just "Queued".
  const resting = queued ? (t.sourceRestUntil || 0) - Date.now() : 0
  // A failed task isn't uniformly "bad": the source may have just been busy (amber), the chapters may be
  // covered by another scan (green/amber), or genuinely gone (red). Only the last is a real red problem.
  const fc = t.failClass || (failed ? 'gone' : '')
  // Bar = finished chapters + the fraction of the chapter in progress.
  const cur = t.pagesTotal > 0 ? t.pagesDone / t.pagesTotal : 0
  const pct = t.total > 0 ? Math.round(((t.done + (running ? cur : 0)) / t.total) * 100) : 0
  // Which chapter we're on: finished count + the one in progress.
  const chapterNo = Math.min(t.total, t.done + (t.currentChapter ? 1 : 0))
  // Group failed chapters by what the failure MEANS, so one genuinely-gone chapter doesn't paint every
  // other (covered / retryable) one the same colour. Each group is labelled and coloured on its own.
  const FAIL_GROUPS = [
    { k: 'gone', label: 'No other copy' },
    { k: 'transient', label: 'Source was busy — retryable' },
    { k: 'alternative', label: 'Covered by another scan' },
  ] as const
  return (
    <div className="dlc">
      <div className="dlc-top">
        <div className="dlc-title">{t.tag === 'migration' && <span className="dlc-m" title="Migration download">M</span>}{t.mangaTitle}{running && (t.reArms ?? 0) > 0 && <span className="dlc-retry" title="Re-running chapters the source was too busy for">retry {t.reArms}</span>}</div>
        <div className="dlc-actions">
          {queued && (
            <span className="dlc-reorder">
              <button className="dlc-move" disabled={!canUp} onClick={() => onMove('up')} aria-label="Move up">▲</button>
              <button className="dlc-move" disabled={!canDown} onClick={() => onMove('down')} aria-label="Move down">▼</button>
            </span>
          )}
          {running
            ? <button className="dl-link" onClick={onStop}>Stop</button>
            : waitvf
              ? <button className="dl-link dl-resume" onClick={() => setSolving(true)}>Solve</button>
              : interrupted
              ? <button className="dl-link dl-resume" onClick={onResume}>Resume</button>
              : parked
                ? <button className="dl-link dl-resume" onClick={onForce}>Retry now</button>
                : failed && t.failedChapters.length
                  ? <button className="dl-link" onClick={onRetry}>Retry {t.failed}</button>
                  : <span className={'dl-state ' + (failed || t.state === 'stopped' ? 'failed' : 'done')}>{t.state === 'stopped' ? 'Stopped' : failed ? 'Failed' : 'Done'}</span>}
        </div>
      </div>
      <div className="dlc-sub">
        <span>
          {waitvf ? `🔒 Waiting for verification · solve the human-check for ${t.vfHost || 'the source'}, then it resumes` : interrupted ? `Interrupted · ${t.done}/${t.total} done — tap Resume` : parked
            ? `Source was busy · ${t.failed} chapter${t.failed === 1 ? '' : 's'} to retry · in ${fmtCountdown(retryIn)}`
            : queued ? (resting > 0 ? `Source resting · ready in ${fmtCountdown(resting)}` : 'Queued') : running
            ? `Chapter ${chapterNo} of ${t.total}${t.currentChapter ? ` · ${t.currentChapter}` : ''}`
            : `${t.done}/${t.total} chapter${t.total === 1 ? '' : 's'}${failed ? (t.failed > 0 ? ` · ${t.failed} failed` : ' · failed') : ' done'}`}
        </span>
        {running && t.pagesTotal > 0 && <span className="dlc-count">{t.pagesDone}/{t.pagesTotal}{t.kbps > 0 ? ` · ${fmtSpeed(t.kbps)}` : ''}</span>}
      </div>
      <div className="dlc-bar"><div className={'dlc-fill ' + (failed || parked ? 'fc-' + fc : '')} style={{ width: pct + '%' }} /></div>
      {/* Which chapters failed AND why, grouped by meaning: red = genuinely gone, green = we have it via
          another scan, amber = the source was just busy and it's worth retrying. */}
      {failed && t.failedChapters.length > 0 && (
        <div className="dlc-foot">
          {FAIL_GROUPS.map(({ k, label }) => {
            const names = t.failedChapters.filter((c) => (c.cls || fc) === k).map((c) => c.name).filter(Boolean)
            if (!names.length) return null
            const shown = names.slice(0, 6).join(', ') + (names.length > 6 ? `, +${names.length - 6} more` : '')
            return <div key={k} className={'dlc-fg fc-' + k}><b>{label}:</b> {shown}</div>
          })}
        </div>
      )}
      {solving && (
        <WebviewModal
          url={t.vfHost ? `https://${t.vfHost}/` : undefined}
          source={t.vfHost ? undefined : t.sourceId}
          onClose={() => {
            setSolving(false)
            // Clearing the host fires HumanCheckState.onCleared server-side, which requeues this download.
            if (t.vfHost) fetch(`/api/webview/pending/clear?host=${encodeURIComponent(t.vfHost)}`, { method: 'POST' }).catch(() => {})
          }}
        />
      )}
    </div>
  )
}
