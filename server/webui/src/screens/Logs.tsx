import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type LogEntry } from '../api'
import { IconArrowLeft } from '../components/icons'

function clock(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export default function Logs() {
  const nav = useNavigate()
  const [level, setLevel] = useState<'warn' | 'error'>('warn')
  const [live, setLive] = useState(false)
  const [logs, setLogs] = useState<LogEntry[] | null>(null)
  const levelRef = useRef(level)
  levelRef.current = level

  async function fetchLogs() { setLogs(await api.logs(levelRef.current, 300).catch(() => [])) }
  useEffect(() => { fetchLogs() }, [level])
  useEffect(() => {
    if (!live) return
    const t = setInterval(fetchLogs, 4000)
    return () => clearInterval(t)
  }, [live])

  function copyAll() {
    const text = (logs ?? []).map((l) => `${clock(l.ts)} ${l.level} ${l.logger}: ${l.msg}`).join('\n')
    navigator.clipboard?.writeText(text).catch(() => {})
  }

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Server logs</span>
      </div>

      <div className="logs-bar">
        <div className="logs-tabs">
          <button className={'logs-tab' + (level === 'warn' ? ' on' : '')} onClick={() => setLevel('warn')}>Warnings +</button>
          <button className={'logs-tab' + (level === 'error' ? ' on' : '')} onClick={() => setLevel('error')}>Errors</button>
        </div>
        <div className="logs-actions">
          <button className={'chip' + (live ? ' on' : '')} onClick={() => setLive((v) => !v)}>{live ? 'Live' : 'Paused'}</button>
          <button className="dl-link" onClick={fetchLogs}>Refresh</button>
          <button className="dl-link" onClick={copyAll}>Copy</button>
        </div>
      </div>

      {logs === null ? <div className="spinner" /> : logs.length === 0 ? (
        <div className="center-msg">No {level === 'error' ? 'errors' : 'warnings'} recorded. 🎉</div>
      ) : (
        <div className="logs-list">
          {logs.map((l, i) => (
            <div key={i} className={'log-row ' + l.level.toLowerCase()}>
              <span className="log-time">{clock(l.ts)}</span>
              <span className={'log-lvl ' + l.level.toLowerCase()}>{l.level}</span>
              <div className="log-body">
                <span className="log-logger">{l.logger}</span>
                <span className="log-msg">{l.msg}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
