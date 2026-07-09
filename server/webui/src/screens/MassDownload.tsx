import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type MassPlan } from '../api'
import { toast } from '../components/Toast'

const keyOf = (i: { sourceId: string; mangaUrl: string }) => i.sourceId + '|' + i.mangaUrl

export default function MassDownload() {
  const nav = useNavigate()
  const [plan, setPlan] = useState<MassPlan | null>(null)
  const [sel, setSel] = useState<Set<string>>(new Set())
  const [showAll, setShowAll] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [queuing, setQueuing] = useState(false)

  async function load() {
    const p = await api.massPlan().catch(() => null)
    if (!p) return
    setPlan(p)
    // Default-select every series that has something missing.
    setSel(new Set(p.items.filter((i) => i.missing > 0).map(keyOf)))
  }
  useEffect(() => { load() }, [])

  async function scanFirst() {
    setScanning(true)
    try { await api.libraryUpdate() } catch { /* ignore */ }
    await load()
    setScanning(false)
    toast('Library rescanned for new chapters', 'success')
  }

  const rows = useMemo(() => (plan?.items ?? []).filter((i) => showAll || i.missing > 0), [plan, showAll])
  const selectedTotal = useMemo(
    () => (plan?.items ?? []).filter((i) => sel.has(keyOf(i))).reduce((a, i) => a + i.missing, 0),
    [plan, sel],
  )
  const selectedSeries = useMemo(
    () => (plan?.items ?? []).filter((i) => sel.has(keyOf(i)) && i.missing > 0).length,
    [plan, sel],
  )

  function toggle(k: string) {
    setSel((s) => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n })
  }
  function selectAll() { setSel(new Set(rows.filter((i) => i.missing > 0).map(keyOf))) }
  function selectNone() { setSel(new Set()) }

  async function start() {
    const items = (plan?.items ?? []).filter((i) => sel.has(keyOf(i)) && i.missing > 0).map((i) => ({ sourceId: i.sourceId, mangaUrl: i.mangaUrl }))
    if (!items.length) return
    setQueuing(true)
    const r = await api.massStart(items).catch(() => null)
    setQueuing(false)
    if (r) { toast(`Queued ${r.count} chapter${r.count === 1 ? '' : 's'} across ${selectedSeries} series`, 'success'); nav('/downloads') }
    else toast('Failed to queue downloads', 'error')
  }

  if (!plan) return <div className="spinner" />

  return (
    <>
      <div className="list-head">
        <span className="list-title">Mass download</span>
        <div className="dl-head-actions">
          <button className="dl-link" disabled={scanning} onClick={scanFirst}>{scanning ? 'Scanning…' : 'Scan for new'}</button>
        </div>
      </div>

      <div className="mass-summary">
        <div className="mass-summary-big">{plan.totalMissing.toLocaleString()}</div>
        <div className="mass-summary-sub">chapter{plan.totalMissing === 1 ? '' : 's'} missing across {plan.seriesWithMissing} series</div>
      </div>

      <div className="mass-controls">
        <div className="mass-sel-actions">
          <button className="dl-link" onClick={selectAll}>Select all</button>
          <button className="dl-link" onClick={selectNone}>Clear</button>
        </div>
        <button className={'chip' + (showAll ? ' on' : '')} onClick={() => setShowAll((v) => !v)}>Show complete</button>
      </div>

      <div className="mass-list">
        {rows.length === 0 && <div className="mass-empty">Everything in your library is downloaded 🎉</div>}
        {rows.map((i) => {
          const k = keyOf(i)
          const done = i.missing === 0
          return (
            <label key={k} className={'mass-row' + (done ? ' done' : '')}>
              <input type="checkbox" checked={sel.has(k)} disabled={done} onChange={() => toggle(k)} />
              <div className="mass-row-main">
                <div className="mass-row-title">{i.title}</div>
                <div className="mass-row-sub">{i.source}</div>
              </div>
              <div className="mass-row-count">
                <span className={'mass-frac' + (done ? ' full' : '')}>{i.downloaded}/{i.total}</span>
                {i.missing > 0 && <span className="mass-miss">+{i.missing}</span>}
              </div>
            </label>
          )
        })}
      </div>

      <div className="mass-foot">
        <button className="btn primary block" disabled={selectedTotal === 0 || queuing} onClick={start}>
          {queuing ? 'Queuing…' : selectedTotal === 0 ? 'Nothing selected' : `Download ${selectedTotal.toLocaleString()} chapter${selectedTotal === 1 ? '' : 's'}`}
        </button>
      </div>
    </>
  )
}
