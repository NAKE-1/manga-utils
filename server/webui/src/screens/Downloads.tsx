import { useEffect, useState } from 'react'
import { api, DlTask, Downloads as DownloadsT } from '../api'

const STATE_LABEL: Record<string, string> = { queued: 'Queued', running: 'Downloading', done: 'Done', failed: 'Failed', stopped: 'Stopped' }
const fmtSpeed = (kbps: number) => (kbps >= 1024 ? `${(kbps / 1024).toFixed(1)} MB/s` : `${Math.round(kbps)} KB/s`)

export function Downloads() {
  const [data, setData] = useState<DownloadsT | null>(null)

  useEffect(() => {
    let alive = true
    const tick = () => api.downloads().then((d) => { if (alive) setData(d) }).catch(() => {})
    tick()
    const t = setInterval(tick, 1000) // live progress
    return () => { alive = false; clearInterval(t) }
  }, [])

  async function stopAll() { setData(await api.stopAllDownloads().then((r) => r.json()).catch(() => data)) }
  async function clearFinished() { setData(await api.clearDownloads().then((r) => r.json()).catch(() => data)) }
  async function stop(id: string) { setData(await api.stopDownload(id).then((r) => r.json()).catch(() => data)) }

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
      {data.totalKbps > 0 && <div className="update-msg">{fmtSpeed(data.totalKbps)} total</div>}

      {tasks.length === 0 ? (
        <div className="center-msg">No downloads. Use the download button on a manga.</div>
      ) : (
        <div className="dl-list">
          {tasks.map((t) => <Row key={t.id} t={t} onStop={() => stop(t.id)} />)}
        </div>
      )}
    </>
  )
}

function Row({ t, onStop }: { t: DlTask; onStop: () => void }) {
  const pct = t.pagesTotal > 0 ? Math.round((t.pagesDone / t.pagesTotal) * 100) : t.state === 'done' ? 100 : 0
  const running = t.state === 'running' || t.state === 'queued'
  return (
    <div className="dlc">
      <div className="dlc-top">
        <div className="dlc-title">{t.mangaTitle}</div>
        {running ? <button className="dl-link" onClick={onStop}>Stop</button> : <span className={'dl-state ' + t.state}>{STATE_LABEL[t.state] ?? t.state}</span>}
      </div>
      <div className="dlc-sub">{t.chapterName || 'Chapter'}{t.pagesTotal > 0 ? <span className="dlc-count">{t.pagesDone}/{t.pagesTotal}</span> : null}</div>
      <div className="dlc-bar"><div className={'dlc-fill ' + t.state} style={{ width: pct + '%' }} /></div>
      <div className="dlc-foot">{t.state === 'running' ? fmtSpeed(t.kbps) : t.state === 'failed' && t.error ? t.error : t.state === 'queued' ? 'Waiting…' : ''}</div>
    </div>
  )
}
