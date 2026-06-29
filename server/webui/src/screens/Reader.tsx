import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, pageUrl, Chapter } from '../api'
import { IconArrowLeft, IconHome, IconChevronLeft, IconChevronRight, IconArrowUp, IconSettings } from '../components/icons'

type Sizing = 'clamp' | 'natural'
const lsGet = (k: string, d: string) => localStorage.getItem(k) ?? d

/**
 * Collapse duplicate chapters (same number from different scanlators) to one entry per number,
 * preferring the chapter being read, then the same scanlator, then the latest. Mirrors the desktop
 * dedupChapters so prev/next move one real chapter at a time. Keeps source order (newest-first).
 */
/** One page in the strip — reserves height with a spinner until the image loads, then fades it in. */
function ReaderPage({ src, sizing, eager }: { src: string; sizing: Sizing; eager: boolean }) {
  const [loaded, setLoaded] = useState(false)
  return (
    <div className={'page-slot' + (loaded ? ' loaded' : '')}>
      {!loaded && <div className="spinner sm" />}
      <img
        className={'page ' + sizing + (loaded ? ' loaded' : '')}
        src={src}
        alt=""
        loading={eager ? 'eager' : 'lazy'}
        draggable={false}
        onLoad={() => setLoaded(true)}
        onError={(e) => { const img = e.currentTarget; if (!img.dataset.retried) { img.dataset.retried = '1'; img.src = src + '&r=1' } }}
      />
    </div>
  )
}

function dedupChapters(current: Chapter | undefined, all: Chapter[]): Chapter[] {
  if (!current) return all
  const byNum = new Map<number, Chapter[]>()
  for (const c of all) {
    const g = byNum.get(c.number) ?? []
    g.push(c)
    byNum.set(c.number, g)
  }
  const keep = new Set<string>()
  for (const [num, g] of byNum) {
    if (num < 0) { g.forEach((c) => keep.add(c.url)); continue } // unnumbered: don't collapse
    const pick = g.find((c) => c.url === current.url) || [...g].reverse().find((c) => c.scanlator === current.scanlator) || g[g.length - 1]
    keep.add(pick.url)
  }
  return all.filter((c) => keep.has(c.url))
}

export function Reader() {
  const { sourceId = '' } = useParams()
  const [sp] = useSearchParams()
  const manga = sp.get('manga') || ''
  const chapter = sp.get('chapter') || ''
  const name = sp.get('name') || ''
  const title = sp.get('title') || ''
  const nav = useNavigate()

  const [count, setCount] = useState<number | null>(null)
  const [chapters, setChapters] = useState<Chapter[]>([])
  const [page, setPage] = useState(1)
  const [progress, setProgress] = useState(0)
  const [chrome, setChrome] = useState(true)
  const [showSettings, setShowSettings] = useState(false)
  const [sizing, setSizing] = useState<Sizing>(lsGet('reader.sizing', 'clamp') as Sizing)
  const [gap, setGap] = useState<number>(Number(lsGet('reader.gap', '0')))
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => { localStorage.setItem('reader.sizing', sizing) }, [sizing])
  useEffect(() => { localStorage.setItem('reader.gap', String(gap)) }, [gap])

  useEffect(() => {
    setCount(null); setPage(1); setProgress(0)
    scrollRef.current?.scrollTo({ top: 0 })
    api.pages(sourceId, chapter, title, name).then((r) => setCount(r.count)).catch(() => setCount(0))
    api.detail(sourceId, manga).then((d) => setChapters(d.chapters)).catch(() => {})
    // Mark read + record history for "Continue reading".
    api.setRead(sourceId, manga, chapter, true)
    api.recordHistory(sourceId, manga, chapter, title, name)
  }, [sourceId, chapter])

  // Navigate the de-duplicated list (scanlator-aware) so prev/next skip duplicate chapters.
  const cur = chapters.find((c) => c.url === chapter)
  const navList = dedupChapters(cur, chapters)
  const idx = navList.findIndex((c) => c.url === chapter)
  const nextCh = idx > 0 ? navList[idx - 1] : undefined // newer
  const prevCh = idx >= 0 && idx < navList.length - 1 ? navList[idx + 1] : undefined // older

  // Pill shows current-chapter / total-chapters (highest chapter number), like Atsumaru.
  const curNum = cur && cur.number > 0 ? cur.number : idx >= 0 ? navList.length - idx : 0
  const totalCh = (() => { const m = Math.max(0, ...navList.map((c) => c.number).filter((n) => n > 0)); return m > 0 ? m : navList.length })()

  // Prefetch the next chapter's page list + first images once you pass ~70% of this chapter.
  const prefetchedNext = useRef('')
  useEffect(() => {
    if (progress > 0.7 && nextCh && prefetchedNext.current !== nextCh.url) {
      prefetchedNext.current = nextCh.url
      api.pages(sourceId, nextCh.url, title, nextCh.name)
        .then((r) => { for (let i = 0; i < Math.min(2, r.count); i++) { const im = new Image(); im.src = pageUrl(sourceId, nextCh.url, i, title, nextCh.name) } })
        .catch(() => {})
    }
  }, [progress, nextCh, sourceId, title])

  function openChapter(c?: Chapter) {
    if (!c) return
    // replace: don't pile chapter views onto history, so Back always returns to the manga screen.
    nav(`/reader/${sourceId}?manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(c.url)}&name=${encodeURIComponent(c.name)}&title=${encodeURIComponent(title)}`, { replace: true })
  }
  const toManga = () => nav(`/manga/${sourceId}?url=${encodeURIComponent(manga)}`)

  function onScroll() {
    const el = scrollRef.current
    if (!el || !count) return
    const max = el.scrollHeight - el.clientHeight
    const p = max > 0 ? el.scrollTop / max : 0
    setProgress(p)
    setPage(Math.min(count, Math.max(1, Math.round(p * (count - 1)) + 1)))
  }

  return (
    <div className="reader">
      <div className="reader-scroll" ref={scrollRef} onScroll={onScroll} onClick={() => setChrome((v) => !v)}>
        {count === null && <div className="spinner" />}
        {count === 0 && <div className="center-msg" style={{ color: '#ccc' }}>Couldn't load this chapter's pages.</div>}
        {count !== null && count > 0 && (
          <div className="strip" style={{ gap: gap + 'px' }}>
            {Array.from({ length: count }, (_, i) => (
              <ReaderPage key={i} src={pageUrl(sourceId, chapter, i, title, name)} sizing={sizing} eager={i < 3} />
            ))}
          </div>
        )}
      </div>

      {/* Minimal progress pill when chrome hidden */}
      {!chrome && count ? (
        <div className="reader-pill">{Math.round(progress * 100)}%{totalCh > 0 ? ` · ${curNum}/${totalCh}` : ''}</div>
      ) : null}

      {/* Chrome */}
      {chrome && (
        <>
          <div className="reader-top" onClick={(e) => e.stopPropagation()}>
            <button className="r-icon" onClick={toManga} aria-label="Back to manga"><IconArrowLeft /></button>
            <button className="r-icon" onClick={() => nav('/')} aria-label="Home"><IconHome /></button>
            <div className="reader-title">{title}{name ? ` · ${name}` : ''}</div>
          </div>

          <div className="reader-bottom" onClick={(e) => e.stopPropagation()}>
            <button className="r-icon" disabled={!prevCh} onClick={() => openChapter(prevCh)} aria-label="Previous chapter"><IconChevronLeft /></button>
            <div className="reader-chiplabel">{name || 'Chapter'}{count ? ` · ${page}/${count}` : ''}</div>
            <button className="r-icon" disabled={!nextCh} onClick={() => openChapter(nextCh)} aria-label="Next chapter"><IconChevronRight /></button>
            <button className="r-icon" onClick={() => scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' })} aria-label="Top"><IconArrowUp /></button>
            <button className="r-icon" onClick={() => setShowSettings(true)} aria-label="Settings"><IconSettings /></button>
          </div>
        </>
      )}

      {/* Settings bottom-sheet */}
      {showSettings && (
        <div className="sheet-scrim" onClick={() => setShowSettings(false)}>
          <div className="sheet" onClick={(e) => e.stopPropagation()}>
            <div className="sheet-handle" />
            <div className="sheet-title">Reader settings</div>

            <div className="sheet-label">Strip sizing</div>
            <div className="seg">
              <button className={'seg-btn' + (sizing === 'clamp' ? ' on' : '')} onClick={() => setSizing('clamp')}>Clamp</button>
              <button className={'seg-btn' + (sizing === 'natural' ? ' on' : '')} onClick={() => setSizing('natural')}>Natural</button>
            </div>

            <div className="sheet-label">Strip gap · {gap}px</div>
            <input className="slider" type="range" min={0} max={40} value={gap} onChange={(e) => setGap(Number(e.target.value))} />
          </div>
        </div>
      )}
    </div>
  )
}
