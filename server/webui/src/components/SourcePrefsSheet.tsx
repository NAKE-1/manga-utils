import { useCallback, useEffect, useState } from 'react'
import { api, SourcePref } from '../api'
import { toast } from './Toast'

/**
 * A source's own settings (the extension's ConfigurableSource preference screen) — login, mirror,
 * image quality, content/language filters, etc. Rendered as a bottom sheet. Each change saves
 * immediately and re-reads (values or dependent prefs can change server-side).
 */
export function SourcePrefsSheet({ sourceId, sourceName, onClose }: { sourceId: string; sourceName: string; onClose: () => void }) {
  const [prefs, setPrefs] = useState<SourcePref[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [drafts, setDrafts] = useState<Record<number, string>>({})

  const load = useCallback(() => {
    setError(null)
    api.sourcePrefs(sourceId)
      .then((p) => { setPrefs(p); setDrafts({}) })
      .catch((e) => { setPrefs([]); setError(e?.message || 'Could not load this source’s settings') })
  }, [sourceId])
  useEffect(() => { load() }, [load])

  // Reflect the change locally right away so toggles/selects respond instantly, then persist. A silent
  // re-read afterwards picks up any server-side dependent-pref changes; on error we revert to truth.
  function patchLocal(index: number, value: string) {
    setPrefs((ps) => (ps ? ps.map((p) => (p.index === index ? { ...p, value } : p)) : ps))
  }

  async function save(index: number, value: string) {
    patchLocal(index, value)
    try {
      await api.setSourcePref(sourceId, index, value)
      const fresh = await api.sourcePrefs(sourceId)
      setPrefs(fresh)
    } catch (e) {
      toast(`Couldn’t save: ${e instanceof Error ? e.message : 'error'}`, 'error')
      load()
    }
  }

  // The comma-joined value for a Set<String> pref after toggling one entry (kept in entryValues order).
  function toggledSet(p: SourcePref, entryVal: string, on: boolean): string {
    const cur = new Set(p.value.split(',').map((s) => s.trim()).filter(Boolean))
    if (on) cur.add(entryVal); else cur.delete(entryVal)
    return (p.entryValues || []).filter((v) => cur.has(v)).join(',')
  }

  return (
    <div className="sheet-scrim" onClick={onClose}>
      <div className="sheet prefs-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-handle" />
        <div className="sheet-headrow">
          <span className="sheet-title">{sourceName} · settings</span>
          <button className="sheet-close" onClick={onClose} aria-label="Close">✕</button>
        </div>
        {prefs === null ? <div className="spinner" /> : (
          <div className="prefs-list">
            {error && <div className="prefs-error">{error}</div>}
            {prefs.length === 0 && !error && <div className="set-hint">This source has no configurable settings.</div>}
            {prefs.map((p) => {
              const hasList = !!p.entries && p.entries.length > 0
              const isList = hasList && p.type !== 'Set<String>'
              const isSet = hasList && p.type === 'Set<String>'
              return (
                <div key={p.index} className={'pref-row' + (p.enabled ? '' : ' disabled')}>
                  <div className="pref-head">
                    <div className="pref-title">{p.title}</div>
                    {p.summary && <div className="pref-summary">{p.summary}</div>}
                  </div>
                  {p.type === 'Boolean' ? (
                    <button className="pref-toggle" disabled={!p.enabled} onClick={() => save(p.index, String(p.value !== 'true'))} aria-label={p.title}>
                      <span className={'switch' + (p.value === 'true' ? ' on' : '')}><span className="knob" /></span>
                    </button>
                  ) : isList ? (
                    <select className="set-select" disabled={!p.enabled} value={p.value} onChange={(e) => save(p.index, e.target.value)}>
                      {(p.entries || []).map((label, i) => <option key={i} value={p.entryValues?.[i] ?? label}>{label}</option>)}
                    </select>
                  ) : isSet ? (
                    <div className="pref-checks">
                      {(p.entries || []).map((label, i) => {
                        const v = p.entryValues?.[i] ?? label
                        const on = p.value.split(',').map((s) => s.trim()).includes(v)
                        return (
                          <label key={i} className="pref-check">
                            <input type="checkbox" checked={on} disabled={!p.enabled} onChange={(e) => save(p.index, toggledSet(p, v, e.target.checked))} />
                            <span>{label}</span>
                          </label>
                        )
                      })}
                    </div>
                  ) : (
                    <div className="pref-text">
                      <input className="set-input" value={drafts[p.index] ?? p.value} disabled={!p.enabled}
                        onChange={(e) => setDrafts((d) => ({ ...d, [p.index]: e.target.value }))}
                        spellCheck={false} autoCapitalize="off" autoCorrect="off" />
                      <button className="btn sm" disabled={!p.enabled} onClick={() => save(p.index, drafts[p.index] ?? p.value)}>Save</button>
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
