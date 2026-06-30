import { useEffect, useState } from 'react'
import { api, coverUrl, LibraryEntry, HistoryItem } from '../api'
import { Carousel, GridSection } from '../components/Section'
import { CoverCard } from '../components/CoverCard'
import { SkeletonGrid } from '../components/Skeleton'
import { ErrorPanel } from '../components/ErrorPanel'

export function Home() {
  const [library, setLibrary] = useState<LibraryEntry[] | null>(null)
  const [history, setHistory] = useState<HistoryItem[]>([])
  const [failed, setFailed] = useState(false)

  function load() {
    setFailed(false); setLibrary(null)
    api.library().then(setLibrary).catch(() => setFailed(true))
    api.history().then(setHistory).catch(() => {})
  }
  useEffect(load, [])

  if (failed) return <ErrorPanel onRetry={load} message="Couldn't load your library." />
  if (library === null) return <SkeletonGrid />

  // History stores no cover, so fall back to the library entry's thumbnail.
  const coverByKey = new Map(library.map((e) => [e.sourceId + '|' + e.url, e.thumbnailUrl]))
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
  const updates = library.filter((e) => e.newChapters > 0)
  const libraryAZ = [...library].sort((a, b) => a.title.localeCompare(b.title))

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
            />
          ))}
        </Carousel>
      )}

      {updates.length > 0 && (
        <Carousel title="Updates" to="/list/updates">
          {updates.map((e) => (
            <CoverCard
              key={e.sourceId + e.url}
              sourceId={e.sourceId}
              url={e.url}
              title={e.title}
              cover={coverUrl(e.sourceId, e.thumbnailUrl, e.title)}
              badge={e.newChapters}
              subtitle={`${e.newChapters} new`}
            />
          ))}
        </Carousel>
      )}

      <GridSection title="Library" to="/list/library">
        {libraryAZ.map((e) => (
          <CoverCard key={e.sourceId + e.url} grid sourceId={e.sourceId} url={e.url} title={e.title} cover={coverUrl(e.sourceId, e.thumbnailUrl, e.title)} badge={e.newChapters} />
        ))}
      </GridSection>
    </>
  )
}
