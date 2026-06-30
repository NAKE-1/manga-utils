import { useEffect, useState } from 'react'
import { api, DownloadJob } from '../api'

const STATE_LABEL: Record<string, string> = { QUEUED: 'Queued', RUNNING: 'Downloading', RETRYING: 'Retrying', DONE: 'Done', FAILED: 'Failed' }

export function Downloads() {
  const [jobs, setJobs] = useState<DownloadJob[]>([])
  const [queued, setQueued] = useState(0)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    let alive = true
    const tick = () => api.downloads().then((d) => { if (alive) { setJobs(d.jobs); setQueued(d.queued); setReady(true) } }).catch(() => setReady(true))
    tick()
    const t = setInterval(tick, 3000) // poll while open
    return () => { alive = false; clearInterval(t) }
  }, [])

  if (!ready) return <div className="spinner" />

  const active = jobs.some((j) => j.state === 'RUNNING' || j.state === 'RETRYING') || queued > 0

  return (
    <>
      <div className="list-head">
        <span className="list-title">Downloads</span>
        {active && <span className="dl-active">{queued > 0 ? `${queued} queued` : 'Working…'}</span>}
      </div>
      {jobs.length === 0 ? (
        <div className="center-msg">No downloads yet. Use the download button on a manga.</div>
      ) : (
        <div className="dl-list">
          {jobs.map((j) => (
            <div className="dl-row" key={j.id}>
              <div className="dl-info">
                <div className="dl-target">{j.target}</div>
                {j.error && <div className="dl-error">{j.error}</div>}
              </div>
              <span className={'dl-state ' + j.state.toLowerCase()}>{STATE_LABEL[j.state] ?? j.state}</span>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
