import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, pageUrl, Chapter } from '../api'
import { IconArrowLeft, IconHome, IconChevronLeft, IconChevronRight, IconArrowUp, IconSettings } from '../components/icons'

type Sizing = 'clamp' | 'natural'
const lsGet = (k: string, d: string) => localStorage.getItem(k) ?? d

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

  // Prev/next in reading order (chapters are newest-first from the source).
  const idx = chapters.findIndex((c) => c.url === chapter)
  const nextCh = idx > 0 ? chapters[idx - 1] : undefined // newer
  const prevCh = idx >= 0 && idx < chapters.length - 1 ? chapters[idx + 1] : undefined // older

  function openChapter(c?: Chapter) {
    if (!c) return
    nav(`/reader/${sourceId}?manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(c.url)}&name=${encodeURIComponent(c.name)}&title=${encodeURIComponent(title)}`)
  }

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
              <img
                key={i}
                className={'page ' + sizing}
                src={pageUrl(sourceId, chapter, i, title, name)}
                alt=""
                loading={i < 2 ? 'eager' : 'lazy'}
                draggable={false}
              />
            ))}
          </div>
        )}
      </div>

      {/* Minimal progress pill when chrome hidden */}
      {!chrome && count ? (
        <div className="reader-pill">{Math.round(progress * 100)}% · {page}/{count}</div>
      ) : null}

      {/* Chrome */}
      {chrome && (
        <>
          <div className="reader-top" onClick={(e) => e.stopPropagation()}>
            <button className="r-icon" onClick={() => nav(-1)} aria-label="Back"><IconArrowLeft /></button>
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
