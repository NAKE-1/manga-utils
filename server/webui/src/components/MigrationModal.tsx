import { useEffect, useRef, useState } from 'react'
import { api, MigManifest } from '../api'
import { toast } from './Toast'

const fmtBytes = (b: number) => (b < 1024 ? `${b} B` : b < 1024 ** 2 ? `${Math.round(b / 1024)} KB` : `${(b / 1024 ** 2).toFixed(1)} MB`)

/** The categorised "what will move" table, shared by the create + restore screens. */
function ManifestTable({ m }: { m: MigManifest }) {
  return (
    <div className="mig-list">
      {m.items.map((it) => (
        <div className="mig-item" key={it.key}>
          <span className="mig-item-label">{it.label}</span>
          <span className="mig-item-detail">{it.detail}</span>
          <span className="mig-item-size">{fmtBytes(it.bytes)}</span>
        </div>
      ))}
      <div className="mig-item mig-total">
        <span className="mig-item-label">Total</span>
        <span className="mig-item-detail">{m.files} files</span>
        <span className="mig-item-size">{fmtBytes(m.bytes)}</span>
      </div>
    </div>
  )
}

function ExportPanel() {
  const [m, setM] = useState<MigManifest | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => { api.dataMigrateManifest().then(setM).catch((e) => setErr(String(e?.message || e))) }, [])
  async function download() {
    setBusy(true)
    try {
      const r = await fetch('/api/dev/migrate/export')
      if (!r.ok) throw new Error()
      const blob = await r.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = 'manga-utils-data.mudata.zip'; a.click()
      URL.revokeObjectURL(url)
      toast('Migration package saved', 'success')
    } catch { toast('Export failed', 'error') } finally { setBusy(false) }
  }
  if (err) return <div className="mig-msg mig-err">{err}</div>
  if (!m) return <div className="spinner" />
  return (
    <>
      <div className="mig-desc">
        Everything this instance holds <b>except downloads</b> — library, read state, resume points, history,
        settings, extensions, covers. Move the downloads drive separately, then restore this on the new machine.
      </div>
      <ManifestTable m={m} />
      <button className="btn primary mig-go" disabled={busy} onClick={download}>
        {busy ? 'Packaging…' : `Download package (${fmtBytes(m.bytes)})`}
      </button>
    </>
  )
}

function ImportPanel() {
  const [file, setFile] = useState<File | null>(null)
  const [m, setM] = useState<MigManifest | null>(null)
  const [err, setErr] = useState('')
  const [phase, setPhase] = useState<'pick' | 'preview' | 'importing' | 'done'>('pick')
  const [result, setResult] = useState(0)
  const inp = useRef<HTMLInputElement>(null)

  async function pick(f: File) {
    setFile(f); setErr(''); setM(null); setPhase('pick')
    try { const mm = await api.dataMigratePreview(f); setM(mm); setPhase('preview') }
    catch (e: any) { setErr(e?.error || 'Not a valid migration package') }
  }
  async function doImport() {
    if (!file) return
    setPhase('importing'); setErr('')
    try { const r = await api.dataMigrateImport(file); setResult(r.files); setPhase('done') }
    catch (e: any) { setErr(e?.error || 'Import failed'); setPhase('preview') }
  }

  if (phase === 'done') return (
    <div className="mig-done">
      <div className="mig-done-h">✓ Restored {result} files</div>
      <div className="mig-desc">
        <b>Restart the server now</b> to load the migrated data. Make sure your downloads drive is mounted and
        <code> downloadDir</code> in settings points to it.
      </div>
    </div>
  )

  return (
    <>
      <div className="mig-desc mig-warn">
        ⚠ Restoring <b>overwrites all data on THIS instance</b> (library, read state, settings, extensions…).
        Downloads are left untouched. A restart is required after. Do this on a fresh instance, not a live one.
      </div>
      <input ref={inp} type="file" accept=".zip,.mudata" hidden onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
      <button className="btn" onClick={() => inp.current?.click()}>{file ? `Chosen: ${file.name}` : 'Choose migration package…'}</button>
      {err && <div className="mig-msg mig-err">{err}</div>}
      {m && phase === 'preview' && (
        <>
          <div className="mig-desc">This package will restore:</div>
          <ManifestTable m={m} />
          <button className="btn primary mig-go" onClick={doImport}>Restore this package</button>
        </>
      )}
      {phase === 'importing' && <div className="mig-msg">Restoring…</div>}
    </>
  )
}

export function MigrationModal({ onClose }: { onClose: () => void }) {
  const [mode, setMode] = useState<'export' | 'import'>('export')
  return (
    <div className="mig-overlay" onClick={onClose}>
      <div className="mig" onClick={(e) => e.stopPropagation()}>
        <div className="mig-head">
          <span className="mig-title">Mass data migration</span>
          <button className="mig-x" onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className="mig-modes">
          <button className={'mig-mode' + (mode === 'export' ? ' on' : '')} onClick={() => setMode('export')}>Create package</button>
          <button className={'mig-mode' + (mode === 'import' ? ' on' : '')} onClick={() => setMode('import')}>Restore package</button>
        </div>
        {mode === 'export' ? <ExportPanel /> : <ImportPanel />}
      </div>
    </div>
  )
}
