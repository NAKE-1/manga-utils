import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, coverUrl, LibraryEntry, HistoryItem } from '../api'
import { CoverCard } from '../components/CoverCard'

const TITLES: Record<string, string> = {
  library: 'Library',
  updates: 'Updates',
  continue: 'Continue reading',
}

// A full library-style grid for a Home section (opened from a tappable section header).
export function ListPage() {
  const { kind = 'library' } = useParams()
  const [library, setLibrary] = useState<LibraryEntry[]>([])
  const [history, setHistory] = useState<HistoryItem[]>([])
  const [ready, setReady] = useState(false)

  useEffect(() => {
    Promise.all([
      api.library().then(setLibrary).catch(() => {}),
      api.history().then(setHistory).catch(() => {}),
    ]).finally(() => setReady(true))
  }, [])

  if (!ready) return <div className="spinner" />

  let cards
  if (kind === 'continue') {
    const coverByKey = new Map(library.map((e) => [e.sourceId + '|' + e.url, e.thumbnailUrl]))
    const seen = new Set<string>()
    cards = history
      .filter((h) => {
        const k = h.sourceId + '|' + h.mangaUrl
        if (seen.has(k)) return false
        seen.add(k)
        return true
      })
      .map((h) => (
        <CoverCard key={h.sourceId + h.chapterUrl} grid sourceId={h.sourceId} url={h.mangaUrl} title={h.mangaTitle} cover={coverUrl(h.sourceId, h.thumbnailUrl || coverByKey.get(h.sourceId + '|' + h.mangaUrl))} subtitle={h.chapterName} />
      ))
  } else {
    const entries = kind === 'updates' ? library.filter((e) => e.newChapters > 0) : library
    cards = entries.map((e) => (
      <CoverCard key={e.sourceId + e.url} grid sourceId={e.sourceId} url={e.url} title={e.title} cover={coverUrl(e.sourceId, e.thumbnailUrl)} badge={e.newChapters} />
    ))
  }

  return (
    <>
      <div className="section-head" style={{ paddingTop: 12 }}>{TITLES[kind] ?? 'List'}</div>
      {cards.length ? <div className="grid">{cards}</div> : <div className="center-msg">Nothing here yet.</div>}
    </>
  )
}
