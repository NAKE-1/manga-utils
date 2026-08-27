import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, coverUrl, dlState, pageSize, LibraryEntry, HistoryItem } from '../api'
import { CoverCard } from '../components/CoverCard'
import { Pager } from '../components/Pager'
import { useNet } from '../components/NetStatus'

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
  const { online } = useNet()
  const [library, setLibrary] = useState<LibraryEntry[]>([])
  const [history, setHistory] = useState<HistoryItem[]>([])
  const [ready, setReady] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [updatePct, setUpdatePct] = useState(0)
  const [updateMsg, setUpdateMsg] = useState('')
  const [updatedTitles, setUpdatedTitles] = useState<{ title: string; count: number }[]>([])
  const [updatedFailed, setUpdatedFailed] = useState<{ source: string; title: string }[]>([])
  const [q, setQ] = useState('')
  const [sort, setSort] = useState<'title' | 'updated' | 'new' | 'number'>('title')
  const [filter, setFilter] = useState<'all' | 'new' | 'downloaded' | 'notdl'>('all')
  const [contPage, setContPage] = useState(0)
  const [historyTotal, setHistoryTotal] = useState(0)
  const [libPage, setLibPage] = useState(0)
  const PS = pageSize()

  // Reset to the first page whenever the visible library set changes.
  useEffect(() => { setLibPage(0) }, [q, sort, filter, kind])

  // Library loads once. Library/Updates paint as soon as it arrives — never gated on the 1.5 MB history.
  useEffect(() => {
    api.library().then(setLibrary).catch(() => {}).finally(() => { if (kind !== 'continue') setReady(true) })
  }, [kind])

  // Continue: fetch the current history page server-side (deduped there), one page at a time.
  useEffect(() => {
    if (kind !== 'continue') return
    api.history(contPage * PS, PS).then((r) => { setHistory(r.items); setHistoryTotal(r.total) }).catch(() => {}).finally(() => setReady(true))
  }, [kind, contPage, PS])

  async function checkUpdates() {
    if (!online) { setUpdateMsg('You appear to be offline — connect to check for updates'); return }
    setUpdating(true); setUpdateMsg(''); setUpdatedTitles([]); setUpdatedFailed([]); setUpdatePct(0)
    // Starts the update and polls to completion — no long-held request to drop → no false "Update failed".
    const r = await api.runLibraryUpdate((pct) => setUpdatePct(pct)).catch(() => null)
    await api.library().then(setLibrary).catch(() => {})
    setUpdating(false); setUpdatePct(0)
    if (!r) { setUpdateMsg('Update failed'); return } // only a genuine server error / unreachable now
    const checked = r.checked ?? 0
    const failed = r.failed ?? 0
    // e.g. "Checked 270/272 series · no new chapters · 2 failed to check"
    const parts: string[] = []
    if (checked > 0) parts.push(`Checked ${checked - failed}/${checked} series`)
    if (r.titles.length === 0) parts.push('no new chapters')
    if (failed > 0) parts.push(`${failed} failed to check`)
    setUpdateMsg(parts.join(' · ') || 'No new chapters found')
    setUpdatedTitles(r.titles)
    setUpdatedFailed(r.failedTitles ?? [])
  }

  if (!ready) return <div className="spinner" />

  let cards
  let libTotal = 0
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
    let entries = kind === 'updates' ? library.filter((e) => e.newChapters > 0) : [...library]
    if (kind === 'library') {
      const needle = q.trim().toLowerCase()
      if (needle) entries = entries.filter((e) => e.title.toLowerCase().includes(needle))
      if (filter === 'new') entries = entries.filter((e) => e.newChapters > 0)
      else if (filter === 'downloaded') entries = entries.filter((e) => !!dlState(e))
      else if (filter === 'notdl') entries = entries.filter((e) => !dlState(e))
      entries.sort((a, b) =>
        sort === 'updated' ? b.lastDate - a.lastDate
          : sort === 'new' ? (b.newChapters - a.newChapters) || a.title.localeCompare(b.title)
            : sort === 'number' ? b.lastNumber - a.lastNumber
              : a.title.localeCompare(b.title))
    } else {
      entries.sort((a, b) => a.title.localeCompare(b.title))
    }
    libTotal = entries.length
    cards = entries.slice(libPage * PS, libPage * PS + PS).map((e) => (
      <CoverCard key={e.sourceId + e.url} grid sourceId={e.sourceId} url={e.url} title={e.title} cover={coverUrl(e.sourceId, e.thumbnailUrl, e.title)} subtitle={lastLine(e)} badge={e.newChapters} dl={dlState(e)} dimmed={!online && !dlState(e)} />
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
      {kind === 'library' && (
        <div className="lib-toolbar">
          <input className="lib-search" placeholder="Search library…" value={q} onChange={(e) => setQ(e.target.value)} />
          <select className="lib-sel" value={sort} onChange={(e) => setSort(e.target.value as typeof sort)}>
            <option value="title">A–Z</option>
            <option value="updated">Recently updated</option>
            <option value="new">Unread first</option>
            <option value="number">Latest chapter</option>
          </select>
          <select className="lib-sel" value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
            <option value="all">All</option>
            <option value="new">Has new</option>
            <option value="downloaded">Downloaded</option>
            <option value="notdl">Not downloaded</option>
          </select>
        </div>
      )}
      {updateMsg && <div className="update-msg">{updateMsg}</div>}
      {updatedTitles.length > 0 && (
        <div className="update-list">
          {updatedTitles.map((t) => (
            <div key={t.title} className="update-line"><span className="ul-name">{t.title}</span><span className="ul-count">{t.count} new</span></div>
          ))}
        </div>
      )}
      {updatedFailed.length > 0 && (
        <div className="update-list update-failed">
          {updatedFailed.map((t) => (
            <div key={t.source + '|' + t.title} className="update-line"><span className="ul-name">{t.title}</span><span className="ul-count ul-fail">{t.source} failed</span></div>
          ))}
        </div>
      )}
      {cards.length ? <div className="grid">{cards}</div> : <div className="center-msg">Nothing here yet.</div>}
      {kind === 'continue'
        ? <Pager page={contPage} total={historyTotal} size={PS} onPage={(p) => { setContPage(p); window.scrollTo(0, 0) }} />
        : <Pager page={libPage} total={libTotal} size={PS} onPage={(p) => { setLibPage(p); window.scrollTo(0, 0) }} />}
    </>
  )
}
