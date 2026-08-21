import { useEffect, useState } from 'react'
import { api, coverUrl, dlState, pageSize, LibraryEntry, HistoryItem } from '../api'
import { Carousel, GridSection } from '../components/Section'
import { CoverCard } from '../components/CoverCard'
import { SkeletonGrid } from '../components/Skeleton'
import { ErrorPanel } from '../components/ErrorPanel'
import { Footer } from '../components/Footer'

export function Home() {
  const [library, setLibrary] = useState<LibraryEntry[] | null>(null)
  const [history, setHistory] = useState<HistoryItem[]>([])
  const [failed, setFailed] = useState(false)
  const [devRemove] = useState(() => localStorage.getItem('dev.continueRemove') === '1')

  function load() {
    setFailed(false); setLibrary(null)
    api.library().then(setLibrary).catch(() => setFailed(true))
    // Only the recent slice — the carousel doesn't need the whole (now server-deduped) history.
    api.history(0, pageSize()).then((r) => setHistory(r.items)).catch(() => {})
  }
  useEffect(load, [])

  async function removeContinue(h: HistoryItem) {
    setHistory((hs) => hs.filter((x) => !(x.sourceId === h.sourceId && x.mangaUrl === h.mangaUrl)))
    await api.deleteHistory(h.sourceId, h.mangaUrl).catch(() => {})
    api.history(0, pageSize()).then((r) => setHistory(r.items)).catch(() => {})
  }

  if (failed) return <ErrorPanel onRetry={load} message="Couldn't load your library." />
  if (library === null) return <SkeletonGrid />

  // History stores no cover, so fall back to the library entry's thumbnail.
  const coverByKey = new Map(library.map((e) => [e.sourceId + '|' + e.url, e.thumbnailUrl]))
  // New-chapter count per manga, so Continue-reading cards can show the "!" badge too.
  const newByKey = new Map(library.map((e) => [e.sourceId + '|' + e.url, e.newChapters]))
  // Continue reading: most-recently read first (leftmost), one entry per manga.
  const seen = new Set<string>()
  const continueReading = [...history]
    .sort((a, b) => b.readAt - a.readAt)
    .filter((h) => {
      const k = h.sourceId + '|' + h.mangaUrl
      if (seen.has(k)) return false
      seen.add(k)
      return true
    })
  const libraryAZ = [...library].sort((a, b) => a.title.localeCompare(b.title))
  // Cap how many series the Home grid shows (0 = unlimited). Overflow stays reachable via the "Library →"
  // header link to the full list. Configurable in Settings → Appearance.
  const limit = Number(localStorage.getItem('app.homeSeriesLimit')) || 0
  const shownLibrary = limit > 0 ? libraryAZ.slice(0, limit) : libraryAZ
  const libraryTitle = limit > 0 && libraryAZ.length > limit ? `Library · ${limit} of ${libraryAZ.length}` : 'Library'

  if (library.length === 0 && continueReading.length === 0) {
    return <div className="center-msg">Your library is empty.<br />Add manga from Search to see them here.</div>
  }

  return (
    <>
      {continueReading.length > 0 && (
        <Carousel title="Continue reading" to="/list/continue">
          {continueReading.map((h) => (
            <CoverCard
              key={h.sourceId + h.chapterUrl}
              sourceId={h.sourceId}
              url={h.mangaUrl}
              title={h.mangaTitle}
              cover={coverUrl(h.sourceId, h.thumbnailUrl || coverByKey.get(h.sourceId + '|' + h.mangaUrl), h.mangaTitle)}
              subtitle={h.chapterName}
              badge={newByKey.get(h.sourceId + '|' + h.mangaUrl)}
              onRemove={devRemove ? () => removeContinue(h) : undefined}
            />
          ))}
        </Carousel>
      )}

      <GridSection title={libraryTitle} to="/list/library">
        {shownLibrary.map((e) => (
          <CoverCard key={e.sourceId + e.url} grid sourceId={e.sourceId} url={e.url} title={e.title} cover={coverUrl(e.sourceId, e.thumbnailUrl, e.title)} badge={e.newChapters} dl={dlState(e)} />
        ))}
      </GridSection>

      <Footer />
    </>
  )
}
