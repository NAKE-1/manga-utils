import { useEffect, useState } from 'react'
import { api, coverUrl, LibraryEntry, HistoryItem } from '../api'
import { Carousel, GridSection } from '../components/Section'
import { CoverCard } from '../components/CoverCard'

export function Home() {
  const [library, setLibrary] = useState<LibraryEntry[] | null>(null)
  const [history, setHistory] = useState<HistoryItem[]>([])

  useEffect(() => {
    api.library().then(setLibrary).catch(() => setLibrary([]))
    api.history().then(setHistory).catch(() => setHistory([]))
  }, [])

  if (library === null) return <div className="spinner" />

  // Continue reading: most-recent history entry per manga.
  const seen = new Set<string>()
  const continueReading = history.filter((h) => {
    const k = h.sourceId + '|' + h.mangaUrl
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
  const updates = library.filter((e) => e.newChapters > 0)

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
              cover={coverUrl(h.sourceId, h.thumbnailUrl)}
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
              cover={coverUrl(e.sourceId, e.thumbnailUrl)}
              badge={e.newChapters}
              subtitle={`${e.newChapters} new`}
            />
          ))}
        </Carousel>
      )}

      <GridSection title="Library" to="/list/library">
        {library.map((e) => (
          <CoverCard key={e.sourceId + e.url} grid sourceId={e.sourceId} url={e.url} title={e.title} cover={coverUrl(e.sourceId, e.thumbnailUrl)} badge={e.newChapters} />
        ))}
      </GridSection>
    </>
  )
}
