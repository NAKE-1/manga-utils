import { useEffect, useState } from 'react'
import { api, Source, SettingsInfo, DiagResult } from '../api'
import { SourcePicker } from '../components/SourcePicker'

export function Settings() {
  const [info, setInfo] = useState<SettingsInfo | null>(null)
  const [dir, setDir] = useState('')
  const [savingDir, setSavingDir] = useState(false)
  const [dirMsg, setDirMsg] = useState<{ text: string; err: boolean } | null>(null)
  const [sources, setSources] = useState<Source[]>([])
  const [diagSource, setDiagSource] = useState(localStorage.getItem('browse.source') || '')
  const [diag, setDiag] = useState<DiagResult | null>(null)
  const [diagRunning, setDiagRunning] = useState(false)
  const [languages, setLanguages] = useState<string[]>([])

  useEffect(() => {
    api.getSettings().then((s) => { setInfo(s); setDir(s.downloadDir || '') }).catch(() => {})
    api.sources().then((s) => { setSources(s); setDiagSource((c) => (c && s.some((x) => x.id === c)) || !s.length ? c : s[0].id) }).catch(() => {})
    api.languages().then(setLanguages).catch(() => {})
  }, [])

  async function toggleLang(code: string) {
    if (!info) return
    const set = new Set(info.visibleLanguages)
    set.has(code) ? set.delete(code) : set.add(code)
    const s = await api.saveSettings({ visibleLanguages: [...set] }).catch(() => null)
    if (s) setInfo(s)
  }

  async function saveDir() {
    setSavingDir(true); setDirMsg(null)
    try {
      const s = await api.saveSettings({ downloadDir: dir.trim() || null })
      setInfo(s); setDir(s.downloadDir || ''); setDirMsg({ text: 'Saved', err: false })
    } catch (e) {
      setDirMsg({ text: e instanceof Error ? e.message : 'Save failed', err: true })
    } finally { setSavingDir(false) }
  }

  async function toggleCbz() {
    if (!info) return
    const s = await api.saveSettings({ downloadAsCbz: !info.downloadAsCbz }).catch(() => null)
    if (s) setInfo(s)
  }

  async function runDiag() {
    if (!diagSource) return
    setDiagRunning(true); setDiag(null)
    const r = await api.diag(diagSource).catch(() => null)
    setDiag(r ?? { source: '', baseUrl: '', pingMs: 0, speedMbps: 0, sampleBytes: 0, ok: false, error: 'Test failed' })
    setDiagRunning(false)
  }

  return (
    <div className="settings">
      <h2 className="set-h">Settings</h2>

      <div className="set-card">
        <div className="set-row-label">Download folder</div>
        <div className="set-hint">Where chapters and covers are saved on the server. Leave blank for the default.</div>
        <input className="set-input" value={dir} onChange={(e) => setDir(e.target.value)} placeholder={info?.effectiveDownloadDir || 'default'} spellCheck={false} autoCapitalize="off" autoCorrect="off" />
        <div className="set-actions">
          <button className="btn primary" disabled={savingDir} onClick={saveDir}>{savingDir ? 'Saving…' : 'Save'}</button>
          {dirMsg && <span className={'set-msg' + (dirMsg.err ? ' err' : '')}>{dirMsg.text}</span>}
        </div>
        <div className="set-kv"><span>Saving to</span><code>{info?.effectiveDownloadDir || '…'}</code></div>
        <div className="set-kv"><span>Data folder</span><code>{info?.dataDir || '…'}</code></div>
      </div>

      <div className="set-card">
        <div className="set-row-label">Source languages</div>
        <div className="set-hint">Show sources in these languages. None selected = all.</div>
        <div className="lang-chips">
          {languages.map((code) => (
            <button key={code} className={'chip' + (info?.visibleLanguages.includes(code) ? ' on' : '')} onClick={() => toggleLang(code)}>{code.toUpperCase()}</button>
          ))}
        </div>
      </div>

      <div className="set-card">
        <button className="set-toggle" onClick={toggleCbz}>
          <div>
            <div className="set-row-label">Save as CBZ</div>
            <div className="set-hint">Off = a folder of page images.</div>
          </div>
          <span className={'switch' + (info?.downloadAsCbz ? ' on' : '')}><span className="knob" /></span>
        </button>
      </div>

      <div className="set-card">
        <div className="set-row-label">Connection test</div>
        <div className="set-hint">Ping + download speed to a source, measured from the server.</div>
        <div className="set-diag-row"><SourcePicker sources={sources} value={diagSource} onChange={setDiagSource} /></div>
        <div className="set-actions">
          <button className="btn primary" disabled={diagRunning || !diagSource} onClick={runDiag}>{diagRunning ? 'Testing…' : 'Run test'}</button>
        </div>
        {diag && (diag.ok ? (
          <div className="diag-result">
            <div className="diag-stat"><span className="diag-num">{Math.round(diag.pingMs)}<small>ms</small></span><span className="diag-lbl">Ping</span></div>
            <div className="diag-stat"><span className="diag-num">{diag.speedMbps.toFixed(1)}<small>Mbps</small></span><span className="diag-lbl">Speed</span></div>
            <div className="diag-stat"><span className="diag-num">{Math.round(diag.sampleBytes / 1024)}<small>KB</small></span><span className="diag-lbl">Sample</span></div>
          </div>
        ) : <div className="set-msg err">{diag.error || 'Test failed'}</div>)}
      </div>
    </div>
  )
}
