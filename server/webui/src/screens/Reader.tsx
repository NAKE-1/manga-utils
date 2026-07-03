import React, { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, pageUrl, Chapter } from '../api'
import { IconArrowLeft, IconHome, IconChevronLeft, IconChevronRight, IconArrowUp, IconSettings } from '../components/icons'

type Sizing = 'clamp' | 'natural'
type LoadMode = 'balanced' | 'eager' | 'lazy' | 'blob'
const LOAD_MODES: { id: LoadMode; label: string; desc: string }[] = [
  { id: 'balanced', label: 'Balanced', desc: 'Preloads the next few pages, loads the rest as you scroll. Best all-round.' },
  { id: 'eager', label: 'Eager', desc: 'Loads every page immediately. Fastest, but heaviest on memory & data — can choke weak phones.' },
  { id: 'lazy', label: 'Lazy', desc: 'Loads each page only as it scrolls into view. Lightest on memory & data; pages may pop in.' },
  { id: 'blob', label: 'Memory-safe', desc: 'Downloads pages near the screen and frees off-screen ones. Best for weak devices or very long chapters.' },
]
const lsGet = (k: string, d: string) => localStorage.getItem(k) ?? d

/**
 * Collapse duplicate chapters (same number from different scanlators) to one entry per number,
 * preferring the chapter being read, then the same scanlator, then the latest. Mirrors the desktop
 * dedupChapters so prev/next move one real chapter at a time. Keeps source order (newest-first).
 */
/** One page in the strip — reserves height with a spinner until the image loads, then fades it in. */
function ReaderPage({ src, sizing, loading }: { src: string; sizing: Sizing; loading: 'eager' | 'lazy' }) {
  const [loaded, setLoaded] = useState(false)
  return (
    <div className={'page-slot' + (loaded ? ' loaded' : '')}>
      {!loaded && <div className="spinner sm" />}
      <img
        className={'page ' + sizing + (loaded ? ' loaded' : '')}
        src={src}
        alt=""
        loading={loading}
        draggable={false}
        onLoad={() => setLoaded(true)}
        onError={(e) => { const img = e.currentTarget; if (!img.dataset.retried) { img.dataset.retried = '1'; img.src = src + '&r=1' } }}
      />
    </div>
  )
}

/** Memory-safe page: fetch to an object URL when near the viewport, revoke it once far off-screen. */
function BlobPage({ src, sizing }: { src: string; sizing: Sizing }) {
  const [url, setUrl] = useState('')
  const [loaded, setLoaded] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    let obj = ''
    const io = new IntersectionObserver(([e]) => {
      if (e.isIntersecting) {
        if (!obj) fetch(src).then((r) => r.blob()).then((b) => { obj = URL.createObjectURL(b); setUrl(obj) }).catch(() => {})
      } else if (obj) { URL.revokeObjectURL(obj); obj = ''; setUrl(''); setLoaded(false) }
    }, { rootMargin: '1000px 0px' })
    io.observe(el)
    return () => { io.disconnect(); if (obj) URL.revokeObjectURL(obj) }
  }, [src])
  return (
    <div ref={ref} className={'page-slot' + (loaded ? ' loaded' : '')}>
      {!loaded && <div className="spinner sm" />}
      {url && <img className={'page ' + sizing + (loaded ? ' loaded' : '')} src={url} alt="" draggable={false} onLoad={() => setLoaded(true)} />}
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
  const [preload, setPreload] = useState<number>(Number(lsGet('reader.preload', '3')))
  const [showPill, setShowPill] = useState<boolean>(lsGet('reader.pill', '1') === '1')
  const [loadMode, setLoadMode] = useState<LoadMode>(lsGet('reader.loadmode', 'balanced') as LoadMode)
  const [sheetDrag, setSheetDrag] = useState(0)
  const [dragging, setDragging] = useState(false)
  const dragStartY = useRef(0)
  const scrollRef = useRef<HTMLDivElement>(null)

  function closeSheet() { setShowSettings(false); setSheetDrag(0); setDragging(false) }
  function sheetDown(e: React.PointerEvent) { dragStartY.current = e.clientY; setDragging(true); e.currentTarget.setPointerCapture(e.pointerId) }
  function sheetMove(e: React.PointerEvent) { if (dragging) setSheetDrag(Math.max(0, e.clientY - dragStartY.current)) }
  function sheetUp() {
    if (!dragging) return
    setDragging(false)
    if (sheetDrag > 90) { setSheetDrag(700); setTimeout(closeSheet, 200) } // fling/drag past threshold → slide out & close
    else setSheetDrag(0) // snap back
  }

  useEffect(() => { localStorage.setItem('reader.sizing', sizing) }, [sizing])
  useEffect(() => { localStorage.setItem('reader.gap', String(gap)) }, [gap])
  useEffect(() => { localStorage.setItem('reader.preload', String(preload)) }, [preload])
  useEffect(() => { localStorage.setItem('reader.pill', showPill ? '1' : '0') }, [showPill])
  useEffect(() => { localStorage.setItem('reader.loadmode', loadMode) }, [loadMode])

  useEffect(() => {
    setCount(null); setPage(1); setProgress(0)
    prefetchedNext.current = '' // re-arm next-chapter preload for the new chapter
    scrollRef.current?.scrollTo({ top: 0 })
    api.pages(sourceId, chapter, title, name).then((r) => setCount(r.count)).catch(() => setCount(0))
    // Mark read + record history (with the cover, once detail resolves) for "Continue reading".
    api.setRead(sourceId, manga, chapter, true)
    api.detail(sourceId, manga)
      .then((d) => { setChapters(d.chapters); api.recordHistory(sourceId, manga, chapter, title, name, d.manga.thumbnailUrl) })
      .catch(() => api.recordHistory(sourceId, manga, chapter, title, name))
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

  // Once you pass ~50% of this chapter, warm the NEXT chapter into the browser cache so the
  // chapter change is instant. Eager warms the WHOLE next chapter; other modes just the first few.
  // Uses low-priority fetch (HTTP cache only, no image decode) so it never starves the page you're
  // reading — it fills the leftover bandwidth while you read the back half of this chapter.
  const prefetchedNext = useRef('')
  useEffect(() => {
    if (progress <= 0.5 || !nextCh || prefetchedNext.current === nextCh.url) return
    prefetchedNext.current = nextCh.url
    api.pages(sourceId, nextCh.url, title, nextCh.name).then((r) => {
      const count = loadMode === 'eager' ? r.count : Math.min(5, r.count)
      console.log(`[reader] preloading next chapter "${nextCh.name || nextCh.url}" — ${count}/${r.count} pages`)
      for (let i = 0; i < count; i++) {
        fetch(pageUrl(sourceId, nextCh.url, i, title, nextCh.name), { priority: 'low' } as RequestInit)
          .then((res) => res.blob()).catch(() => {})
      }
    }).catch(() => { prefetchedNext.current = '' })
  }, [progress, nextCh, sourceId, title, loadMode])

  function openChapter(c?: Chapter) {
    if (!c) return
    // replace: don't pile chapter views onto history, so Back always returns to the manga screen.
    nav(`/reader/${sourceId}?manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(c.url)}&name=${encodeURIComponent(c.name)}&title=${encodeURIComponent(title)}`, { replace: true })
  }

  function onScroll() {
    const el = scrollRef.current
    if (!el || !count) return
    const max = el.scrollHeight - el.clientHeight
    const p = max > 0 ? el.scrollTop / max : 0
    setProgress(p)
    setPage(Math.min(count, Math.max(1, Math.round(p * (count - 1)) + 1)))
    // Chrome shows at the start and again at the END of the chapter (so prev/next are there when you
    // finish); in between, scrolling away from the top hides it.
    const nearBottom = max > 0 && max - el.scrollTop < 120
    if (nearBottom) { if (!chrome) setChrome(true) } else if (chrome && el.scrollTop > 40) setChrome(false)
  }

  // Tap does nothing (so reading isn't interrupted); a double-tap toggles the chrome.
  const lastTap = useRef(0)
  function onTap() {
    const now = Date.now()
    if (now - lastTap.current < 300) { setChrome((v) => !v); lastTap.current = 0 } else lastTap.current = now
  }

  return (
    <div className="reader">
      <div className="reader-scroll" ref={scrollRef} onScroll={onScroll} onClick={onTap}>
        {count === null && <div className="spinner" />}
        {count === 0 && <div className="center-msg" style={{ color: '#ccc' }}>Couldn't load this chapter's pages.</div>}
        {count !== null && count > 0 && (
          <div className="strip" style={{ gap: gap + 'px' }}>
            {Array.from({ length: count }, (_, i) => (
              loadMode === 'blob'
                ? <BlobPage key={i} src={pageUrl(sourceId, chapter, i, title, name)} sizing={sizing} />
                : <ReaderPage key={i} src={pageUrl(sourceId, chapter, i, title, name)} sizing={sizing} loading={loadMode === 'eager' ? 'eager' : loadMode === 'lazy' ? 'lazy' : (i < preload ? 'eager' : 'lazy')} />
            ))}
          </div>
        )}
      </div>

      {/* Minimal progress pill when chrome hidden — fades opposite the chrome. */}
      {count && showPill ? (
        <div className={'reader-pill' + (chrome ? ' r-fade-out' : '')}>{Math.round(progress * 100)}%{totalCh > 0 ? ` · ${curNum}/${totalCh}` : ''}</div>
      ) : null}

      {/* Chrome — free-floating, no full-width bars. Kept mounted so it can fade in/out. */}
      <div className={'reader-chrome' + (chrome ? '' : ' r-fade-out')}>
        <div className="reader-left" onClick={(e) => e.stopPropagation()}>
          <button className="r-icon" onClick={() => nav(-1)} aria-label="Back"><IconArrowLeft /></button>
          <button className="r-icon" onClick={() => nav('/')} aria-label="Home"><IconHome /></button>
        </div>

        <div className="reader-titlechip" onClick={(e) => e.stopPropagation()}>{title}</div>

        <div className="reader-nav" onClick={(e) => e.stopPropagation()}>
          {count ? <div className="reader-progress">{Math.round(progress * 100)}%{totalCh > 0 ? ` · ${curNum}/${totalCh}` : ''}</div> : null}
          <div className="reader-navrow">
            <button className="r-icon" disabled={!prevCh} onClick={() => openChapter(prevCh)} aria-label="Previous chapter"><IconChevronLeft /></button>
            <div className="reader-chip">{name || `Chapter ${curNum}`}</div>
            <button className="r-icon" disabled={!nextCh} onClick={() => openChapter(nextCh)} aria-label="Next chapter"><IconChevronRight /></button>
          </div>
        </div>

        <div className="reader-tools" onClick={(e) => e.stopPropagation()}>
          <button className="r-icon" onClick={() => scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' })} aria-label="Top"><IconArrowUp /></button>
          <button className="r-icon" onClick={() => setShowSettings(true)} aria-label="Settings"><IconSettings /></button>
        </div>
      </div>

      {/* Settings bottom-sheet */}
      {showSettings && (
        <div className="sheet-scrim" onClick={closeSheet}>
          <div
            className="sheet"
            style={{ transform: `translateY(${sheetDrag}px)`, transition: dragging ? 'none' : 'transform .2s ease' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sheet-drag" onPointerDown={sheetDown} onPointerMove={sheetMove} onPointerUp={sheetUp} onPointerCancel={sheetUp}>
              <div className="sheet-handle" />
              <div className="sheet-title">Reader settings</div>
            </div>

            <div className="sheet-label">Strip sizing</div>
            <div className="seg">
              <button className={'seg-btn' + (sizing === 'clamp' ? ' on' : '')} onClick={() => setSizing('clamp')}>Clamp</button>
              <button className={'seg-btn' + (sizing === 'natural' ? ' on' : '')} onClick={() => setSizing('natural')}>Natural</button>
            </div>

            <div className="sheet-label">Strip gap · {gap}px</div>
            <input className="slider" type="range" min={0} max={40} value={gap} onChange={(e) => setGap(Number(e.target.value))} />

            <div className="sheet-label">Loading mode</div>
            <div className="load-opts">
              {LOAD_MODES.map((m) => (
                <button key={m.id} className={'load-opt' + (loadMode === m.id ? ' on' : '')} onClick={() => setLoadMode(m.id)}>
                  <div className="load-opt-text">
                    <div className="load-opt-title">{m.label}</div>
                    <div className="load-opt-desc">{m.desc}</div>
                  </div>
                  <span className="radio" />
                </button>
              ))}
            </div>

            {loadMode === 'balanced' && (
              <>
                <div className="sheet-label">Preload · {preload} page{preload === 1 ? '' : 's'}</div>
                <input className="slider" type="range" min={0} max={10} value={preload} onChange={(e) => setPreload(Number(e.target.value))} />
              </>
            )}

            <button className="sheet-toggle" onClick={() => setShowPill((v) => !v)}>
              <span>Progress pill</span>
              <span className={'switch' + (showPill ? ' on' : '')}><span className="knob" /></span>
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
