import { useEffect, useRef, useState } from 'react'
import { api, RelocatePreview, RelocateProgress } from '../api'
import { toast } from '../components/Toast'

const fmtBytes = (b: number) => {
  if (!b || b < 1024) return `${b || 0} B`
  const u = ['KB', 'MB', 'GB', 'TB']
  let v = b, i = -1
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${u[i]}`
}

type Mode = 'move' | 'copy' | 'point'
const MODES: { id: Mode; label: string; blurb: string }[] = [
  { id: 'move', label: 'Move', blurb: 'Copy to the new drive, verify, then delete the old copy.' },
  { id: 'copy', label: 'Copy', blurb: 'Duplicate to the new drive; keep the old copy as a backup.' },
  { id: 'point', label: 'Point only', blurb: 'Just save future downloads there; leave existing ones where they are.' },
]

export default function Relocate() {
  const [current, setCurrent] = useState('')
  const [root, setRoot] = useState('')
  const [mode, setMode] = useState<Mode>('move')
  const [preview, setPreview] = useState<RelocatePreview | null>(null)
  const [checking, setChecking] = useState(false)
  const [prog, setProg] = useState<RelocateProgress | null>(null)
  const logRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.getSettings().then((s) => setCurrent(s.effectiveDownloadDir)).catch(() => {})
    // If a relocate is already running (e.g. you reloaded), pick the live view back up.
    api.relocateProgress().then((p) => { if (p.running || p.finished || p.error) setProg(p) }).catch(() => {})
  }, [])

  // Poll while a job is active.
  useEffect(() => {
    if (!prog || (!prog.running && (prog.finished || prog.error))) return
    const t = setInterval(async () => {
      const p = await api.relocateProgress().catch(() => null)
      if (p) setProg(p)
    }, 1000)
    return () => clearInterval(t)
  }, [prog?.running, prog?.finished, prog?.error])

  // Keep the log scrolled to the newest line.
  useEffect(() => { logRef.current?.scrollTo({ top: logRef.current.scrollHeight }) }, [prog?.steps.length])

  async function check() {
    if (!root.trim()) return
    setChecking(true); setPreview(null)
    try { setPreview(await api.relocatePlan(root)) }
    catch (e) { toast(e instanceof Error ? e.message : 'Check failed', 'error') }
    finally { setChecking(false) }
  }

  async function start() {
    if (!root.trim()) return
    if (mode !== 'point' && preview && preview.activeDownloads > 0) { toast('Stop the download queue first.', 'error'); return }
    if (mode !== 'point' && preview && !preview.fits) { toast('Not enough free space on the target.', 'error'); return }
    if (!confirm(`${mode === 'move' ? 'MOVE' : mode === 'copy' ? 'COPY' : 'POINT'} the download library to:\n${preview?.targetLayout || root}\n\n${mode === 'move' ? 'The old copy is deleted only after the copy is verified.' : mode === 'copy' ? 'The old copy is kept as a backup.' : 'Existing downloads stay where they are.'}\n\nContinue?`)) return
    try {
      await api.relocateStart(root, mode)
      setProg({ running: true, phase: 'starting', finished: false, error: '', mode, target: root, filesTotal: 0, filesDone: 0, bytesTotal: 0, bytesDone: 0, steps: [] })
    } catch (e) { toast(e instanceof Error ? e.message : 'Could not start', 'error') }
  }

  const active = !!prog && (prog.running || (!prog.finished && !prog.error))
  const pct = prog && prog.bytesTotal > 0 ? Math.min(100, Math.round((prog.bytesDone / prog.bytesTotal) * 100)) : 0

  return (
    <div className="relo">
      <div className="list-head"><span className="list-title">Relocate library</span></div>
      <p className="relo-lede">Move your downloads to another drive (e.g. an external SSD). A canonical <code>manga-utils/</code> tree is created at the new location. Cross-drive is copy → verify → delete — the old copy is never removed until the new one is verified.</p>
      <div className="relo-cur">Currently saving to: <code>{current || '…'}</code></div>

      {!active && (
        <>
          <label className="relo-label">New root folder (the drive/mount)</label>
          <div className="relo-row">
            <input className="relo-input" placeholder="e.g. E:\  or  /mnt/ssd" value={root} onChange={(e) => setRoot(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && check()} />
            <button className="btn sm" disabled={!root.trim() || checking} onClick={check}>{checking ? '…' : 'Check'}</button>
          </div>

          {preview && (
            <div className="relo-preview">
              <div className="relo-stat"><span>Will create</span><code>{preview.targetLayout}</code></div>
              <div className="relo-stat"><span>To transfer</span><b>{fmtBytes(preview.sourceBytes)}</b> · {preview.sourceFiles.toLocaleString()} files</div>
              <div className="relo-stat"><span>Free on target</span><b className={preview.fits ? 'ok' : 'bad'}>{fmtBytes(preview.targetFreeBytes)}</b>{!preview.fits && ' — not enough space'}</div>
              {preview.warning && <div className="relo-warn">⚠ {preview.warning}</div>}
            </div>
          )}

          <label className="relo-label">Transfer mode</label>
          <div className="relo-modes">
            {MODES.map((m) => (
              <button key={m.id} className={'relo-mode' + (mode === m.id ? ' on' : '')} onClick={() => setMode(m.id)}>
                <b>{m.label}</b><span>{m.blurb}</span>
              </button>
            ))}
          </div>

          <button className="btn primary relo-go" disabled={!root.trim()} onClick={start}>
            {mode === 'move' ? 'Move library' : mode === 'copy' ? 'Copy library' : 'Point downloads here'}
          </button>
        </>
      )}

      {prog && (
        <div className="relo-run">
          <div className="relo-run-head">
            <span className={'relo-phase ' + (prog.error ? 'bad' : prog.finished ? 'ok' : '')}>
              {prog.error ? 'Failed' : prog.finished ? 'Done ✓' : `${prog.phase}…`}
            </span>
            {prog.bytesTotal > 0 && <span className="relo-count">{fmtBytes(prog.bytesDone)} / {fmtBytes(prog.bytesTotal)} · {prog.filesDone.toLocaleString()}/{prog.filesTotal.toLocaleString()} files</span>}
          </div>
          <div className="relo-bar"><div className={'relo-fill ' + (prog.error ? 'bad' : prog.finished ? 'ok' : '')} style={{ width: (prog.finished ? 100 : pct) + '%' }} /></div>
          {prog.error && <div className="relo-err">{prog.error}</div>}
          <div className="relo-log" ref={logRef}>
            {prog.steps.length === 0 ? <div className="relo-log-empty">Starting…</div> : prog.steps.map((s, i) => <div key={i} className="relo-log-line">{s}</div>)}
          </div>
          {(prog.finished || prog.error) && <button className="btn sm relo-again" onClick={() => { setProg(null); setPreview(null) }}>Back</button>}
        </div>
      )}
    </div>
  )
}
