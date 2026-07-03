import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, coverUrl, dlState, LibraryEntry, HistoryItem } from '../api'
import { CoverCard } from '../components/CoverCard'

const TITLES: Record<string, string> = {
  library: 'Library',
  updates: 'Updates',
  continue: 'Continue reading',
}

function relTime(ms: number): string {
  if (!ms) return ''
  const d = Math.floor((Date.now() - ms) / 86400000)
  if (d <= 0) return 'today'
  if (d === 1) return '1d ago'
  if (d < 30) return `${d}d ago`
  const mo = Math.floor(d / 30)
  return mo < 12 ? `${mo}mo ago` : `${Math.floor(d / 365)}y ago`
}
// "Ch 88 · 2d ago" — the latest known chapter for a library entry.
function lastLine(e: LibraryEntry): string {
  const num = e.lastNumber >= 0 ? `Ch ${e.lastNumber % 1 === 0 ? e.lastNumber.toFixed(0) : e.lastNumber}` : e.lastName
  return [num, relTime(e.lastDate)].filter(Boolean).join(' · ')
}

// A full library-style grid for a Home section (opened from a tappable section header).
export function ListPage() {
  const { kind = 'library' } = useParams()
  const [library, setLibrary] = useState<LibraryEntry[]>([])
  const [history, setHistory] = useState<HistoryItem[]>([])
  const [ready, setReady] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [updatePct, setUpdatePct] = useState(0)
  const [updateMsg, setUpdateMsg] = useState('')
  const [updatedTitles, setUpdatedTitles] = useState<{ title: string; count: number }[]>([])

  useEffect(() => {
    Promise.all([
      api.library().then(setLibrary).catch(() => {}),
      api.history().then(setHistory).catch(() => {}),
    ]).finally(() => setReady(true))
  }, [])

  async function checkUpdates() {
    setUpdating(true); setUpdateMsg(''); setUpdatedTitles([]); setUpdatePct(0)
    const poll = setInterval(() => {
      api.updateProgress().then((p) => { if (p.total > 0) setUpdatePct(Math.round((p.done / p.total) * 100)) }).catch(() => {})
    }, 400)
    const r = await api.updateLibrary().catch(() => null)
    clearInterval(poll)
    await api.library().then(setLibrary).catch(() => {})
    setUpdating(false); setUpdatePct(0)
    if (!r) { setUpdateMsg('Update failed'); setUpdatedTitles([]) }
    else if (r.titles.length === 0) { setUpdateMsg('No new chapters found'); setUpdatedTitles([]) }
    else { setUpdateMsg(''); setUpdatedTitles(r.titles) }
  }

  if (!ready) return <div className="spinner" />

  let cards
  if (kind === 'continue') {
    const coverByKey = new Map(library.map((e) => [e.sourceId + '|' + e.url, e.thumbnailUrl]))
    const newByKey = new Map(library.map((e) => [e.sourceId + '|' + e.url, e.newChapters]))
    const seen = new Set<string>()
    cards = [...history]
      .sort((a, b) => b.readAt - a.readAt)
      .filter((h) => {
        const k = h.sourceId + '|' + h.mangaUrl
        if (seen.has(k)) return false
        seen.add(k)
        return true
      })
      .map((h) => (
        <CoverCard key={h.sourceId + h.chapterUrl} grid sourceId={h.sourceId} url={h.mangaUrl} title={h.mangaTitle} cover={coverUrl(h.sourceId, h.thumbnailUrl || coverByKey.get(h.sourceId + '|' + h.mangaUrl), h.mangaTitle)} subtitle={h.chapterName} badge={newByKey.get(h.sourceId + '|' + h.mangaUrl)} />
      ))
  } else {
    const entries = (kind === 'updates' ? library.filter((e) => e.newChapters > 0) : [...library]).sort((a, b) => a.title.localeCompare(b.title))
    cards = entries.map((e) => (
      <CoverCard key={e.sourceId + e.url} grid sourceId={e.sourceId} url={e.url} title={e.title} cover={coverUrl(e.sourceId, e.thumbnailUrl, e.title)} subtitle={lastLine(e)} badge={e.newChapters} dl={dlState(e)} />
    ))
  }

  return (
    <>
      <div className="list-head">
        <span className="list-title">{TITLES[kind] ?? 'List'}</span>
        {kind !== 'continue' && (
          <div className="upd-row">
            {updating && updatePct > 0 && <span className="upd-pct">{updatePct}%</span>}
            <button className="btn" disabled={updating} onClick={checkUpdates}>{updating ? 'Checking…' : 'Check updates'}</button>
          </div>
        )}
      </div>
      {updateMsg && <div className="update-msg">{updateMsg}</div>}
      {updatedTitles.length > 0 && (
        <div className="update-list">
          {updatedTitles.map((t) => (
            <div key={t.title} className="update-line"><span className="ul-name">{t.title}</span><span className="ul-count">{t.count} new</span></div>
          ))}
        </div>
      )}
      {cards.length ? <div className="grid">{cards}</div> : <div className="center-msg">Nothing here yet.</div>}
    </>
  )
}
