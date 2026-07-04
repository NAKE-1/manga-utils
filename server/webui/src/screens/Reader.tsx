import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, pageUrl, Chapter } from '../api'
import { IconArrowLeft, IconHome, IconChevronLeft, IconChevronRight, IconArrowUp, IconSettings } from '../components/icons'

type Sizing = 'clamp' | 'natural'
type LoadMode = 'hybrid' | 'eager' | 'balanced' | 'lazy'
const LOAD_MODES: { id: LoadMode; label: string; desc: string }[] = [
  { id: 'hybrid', label: 'Eager-Hybrid', desc: 'Loads the first pages at high priority and the rest in the background — feels instant even on a slow or relayed link. Recommended.' },
  { id: 'eager', label: 'Eager', desc: 'Loads every page at once, all equal priority. Great on a strong connection; can choke a slow one.' },
  { id: 'balanced', label: 'Balanced', desc: 'Loads the first few pages, then the rest as you scroll.' },
  { id: 'lazy', label: 'Lazy', desc: 'Loads each page only as it scrolls into view. Lightest on data; pages may pop in.' },
]
const lsGet = (k: string, d: string) => localStorage.getItem(k) ?? d

/**
 * Collapse duplicate chapters (same number from different scanlators) to one entry per number,
 * preferring the chapter being read, then the same scanlator, then the latest. Mirrors the desktop
 * dedupChapters so prev/next move one real chapter at a time. Keeps source order (newest-first).
 */
/** One page in the strip — reserves height with a spinner until the image loads, then fades it in. */
function ReaderPage({ src, sizing, loading, priority, index, onStatus }: { src: string; sizing: Sizing; loading: 'eager' | 'lazy'; priority?: 'high' | 'low'; index: number; onStatus?: (i: number, failed: boolean) => void }) {
  const [status, setStatus] = useState<'load' | 'ok' | 'err'>('load')
  const [bust, setBust] = useState(0)
  const url = bust ? src + '&retry=' + bust : src
  // Safety net: if a page never fires load OR error (stalled/truncated connection), don't spin forever —
  // fail it after 25s so the reload button appears instead of an image that reloads endlessly.
  useEffect(() => {
    if (status !== 'load') return
    const t = setTimeout(() => { setStatus('err'); onStatus?.(index, true) }, 25000)
    return () => clearTimeout(t)
  }, [status, url, index, onStatus])
  // No silent auto-retry: a page that fails shows a reload button; only a tap re-fetches it.
  function retry(e: React.MouseEvent) { e.stopPropagation(); setStatus('load'); onStatus?.(index, false); setBust((b) => b + 1) }
  return (
    <div className={'page-slot' + (status === 'ok' ? ' loaded' : '')}>
      {status === 'load' && <div className="spinner sm" />}
      {status === 'err' ? (
        <button type="button" className="page-fail" onClick={retry}>
          <span className="page-fail-icon">↻</span>
          <span>⚠ Page {index + 1} didn't load</span>
          <span className="page-fail-sub">Tap to reload</span>
        </button>
      ) : (
        <img
          className={'page ' + sizing + (status === 'ok' ? ' loaded' : '')}
          src={url}
          alt=""
          loading={loading}
          fetchPriority={priority}
          draggable={false}
          onLoad={() => { setStatus('ok'); onStatus?.(index, false) }}
          onError={() => { setStatus('err'); onStatus?.(index, true) }}
        />
      )}
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
  const [failedPages, setFailedPages] = useState<Set<number>>(new Set())
  const [warnAck, setWarnAck] = useState(0) // banner dismissed at this failure count; reappears if more fail
  const [chapters, setChapters] = useState<Chapter[]>([])
  const [page, setPage] = useState(1)
  const [progress, setProgress] = useState(0)
  const [chrome, setChrome] = useState(true)
  const [showSettings, setShowSettings] = useState(false)
  const [showChapters, setShowChapters] = useState(false)
  const [readUrls, setReadUrls] = useState<Set<string>>(new Set())
  const currentChapRef = useRef<HTMLButtonElement>(null)
  const [sizing, setSizing] = useState<Sizing>(lsGet('reader.sizing', 'clamp') as Sizing)
  const [gap, setGap] = useState<number>(Number(lsGet('reader.gap', '0')))
  const [preload, setPreload] = useState<number>(Number(lsGet('reader.preload', '3')))
  const [showPill, setShowPill] = useState<boolean>(lsGet('reader.pill', '1') === '1')
  const [loadMode, setLoadMode] = useState<LoadMode>((() => { const m = lsGet('reader.loadmode', 'hybrid'); return (m === 'blob' ? 'hybrid' : m) as LoadMode })())
  const [sheetDrag, setSheetDrag] = useState(0)
  const [dragging, setDragging] = useState(false)
  const dragStartY = useRef(0)
  const scrollRef = useRef<HTMLDivElement>(null)

  function closeSheet() { setShowSettings(false); setShowChapters(false); setSheetDrag(0); setDragging(false) }
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
  // Center the current chapter when the chapter list opens.
  useEffect(() => { if (showChapters) requestAnimationFrame(() => currentChapRef.current?.scrollIntoView({ block: 'center' })) }, [showChapters])

  // Fullscreen toggle — the no-big-changes way to hide the browser's address/nav bars while reading.
  // Works on Android (Opera GX / Chrome via the Fullscreen API); unsupported on iOS Safari, so the
  // button is hidden there (iOS users can Add to Home Screen for the same effect).
  const [isFs, setIsFs] = useState(false)
  const fsSupported = typeof document !== 'undefined' && !!document.documentElement.requestFullscreen
  useEffect(() => {
    const onFs = () => setIsFs(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFs)
    return () => document.removeEventListener('fullscreenchange', onFs)
  }, [])
  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen?.()
    else document.documentElement.requestFullscreen?.().catch(() => {})
  }

  // Track which pages are currently in a failed state (idempotent via the Set), for the trouble banner.
  // useCallback keeps its identity stable so the per-page load timeout isn't reset every render.
  const reportStatus = useCallback((i: number, failed: boolean) => {
    setFailedPages((prev) => {
      if (failed === prev.has(i)) return prev
      const next = new Set(prev)
      if (failed) next.add(i); else next.delete(i)
      return next
    })
  }, [])

  useEffect(() => {
    setCount(null); setPage(1); setProgress(0); setFailedPages(new Set()); setWarnAck(0); setShowChapters(false)
    scrollRef.current?.scrollTo({ top: 0 })
    api.pages(sourceId, chapter, title, name).then((r) => setCount(r.count)).catch(() => setCount(0))
    api.mangaState(sourceId, manga).then((s) => setReadUrls(new Set(s.read))).catch(() => {}) // read markers for the chapter list
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

  // Warm the next chapter's first pages once you're halfway, so advancing feels instant. Fires as soon
  // as BOTH hold (progress>0.5 AND the chapter list has loaded); the effect re-runs when nextCh appears,
  // so a fast scroll that reaches 50% before the chapter list finishes still triggers it once ready.
  // Requests carry X-Preload so the server logs them even for cached/downloaded pages.
  const prefetchedNext = useRef('')
  useEffect(() => {
    if (progress <= 0.4) return // fire earlier than mid-chapter for more lead time before you flip
    if (!nextCh) { console.log('[reader] past preload point — waiting for chapter list to preload next'); return }
    if (prefetchedNext.current === nextCh.url) return
    prefetchedNext.current = nextCh.url
    api.pages(sourceId, nextCh.url, title, nextCh.name).then((r) => {
      const n = Math.min(6, r.count)
      console.log(`[reader] preloading next chapter "${nextCh.name || nextCh.url}" — ${n}/${r.count} pages`)
      // One header-tagged request so the preload stays visible in the server log.
      fetch(pageUrl(sourceId, nextCh.url, 0, title, nextCh.name), { headers: { 'X-Preload': '1' } }).catch(() => {})
      // Warm the browser IMAGE cache via new Image() — the same method the info page uses for its
      // instant first-chapter open — so the pages are ready to paint on arrival, not just cached bytes.
      for (let i = 0; i < n; i++) { const im = new Image(); im.src = pageUrl(sourceId, nextCh.url, i, title, nextCh.name) }
    }).catch(() => { prefetchedNext.current = '' })
  }, [progress, nextCh, sourceId, title])

  function openChapter(c?: Chapter) {
    if (!c) return
    // replace: don't pile chapter views onto history, so Back always returns to the manga screen.
    nav(`/reader/${sourceId}?manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(c.url)}&name=${encodeURIComponent(c.name)}&title=${encodeURIComponent(title)}`, { replace: true })
  }

  // Group the full chapter list by number so multiple scanlator versions (Alpha/Gamma/Delta…) of the
  // same chapter appear together; unnumbered chapters stay separate.
  const chapterGroups = useMemo(() => {
    const groups: { key: string; number: number; variants: Chapter[] }[] = []
    const byNum = new Map<number, number>()
    for (const c of chapters) {
      if (c.number > 0 && byNum.has(c.number)) { groups[byNum.get(c.number)!].variants.push(c); continue }
      if (c.number > 0) byNum.set(c.number, groups.length)
      groups.push({ key: c.number > 0 ? 'n' + c.number : c.url, number: c.number, variants: [c] })
    }
    return groups
  }, [chapters])

  // One chapter row — mirrors the detail page's list (name + scanlator·date meta). `variant` = a
  // scanlator alternative shown under a grouped chapter header (its name is the scanlator).
  function chapterRow(c: Chapter, variant = false) {
    const read = readUrls.has(c.url)
    const current = c.url === chapter
    const date = c.dateUpload > 0 ? new Date(c.dateUpload).toLocaleDateString() : null
    const meta = [variant ? null : c.scanlator, date].filter(Boolean).join('  ·  ')
    return (
      <button
        key={c.url}
        ref={current ? currentChapRef : undefined}
        className={'chap-item' + (current ? ' current' : '') + (read ? ' read' : '') + (variant ? ' variant' : '')}
        onClick={() => { setShowChapters(false); if (!current) openChapter(c) }}
      >
        <span className="chap-text">
          <span className="chap-name">{variant ? (c.scanlator || 'Unknown group') : (c.name || `Chapter ${c.number}`)}</span>
          {meta && <span className="chap-meta">{meta}</span>}
        </span>
        <span className="chap-tags">
          {c.downloaded && <span className="chap-dl" title="Downloaded">↓</span>}
          {read && !current && <span className="chap-check" title="Read">✓</span>}
          {current && <span className="chap-cur">Reading</span>}
        </span>
      </button>
    )
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
            {Array.from({ length: count }, (_, i) => {
              // hybrid: all eager, first `preload` at HIGH priority + the rest LOW (visible pages win the pipe).
              // eager: all eager, equal. balanced: first `preload` eager then lazy. lazy: all lazy.
              const eager = loadMode === 'hybrid' || loadMode === 'eager' || (loadMode === 'balanced' && i < preload)
              const priority = loadMode === 'hybrid' ? (i < preload ? 'high' : 'low') : undefined
              return <ReaderPage key={i} index={i} src={pageUrl(sourceId, chapter, i, title, name)} sizing={sizing} loading={eager ? 'eager' : 'lazy'} priority={priority} onStatus={reportStatus} />
            })}
          </div>
        )}
      </div>

      {/* Source-trouble banner: shown when pages are failing, so it's never a silent infinite spinner.
          Dismissable (acknowledge) — reappears only if more pages fail after you dismiss it. */}
      {failedPages.size > warnAck && (
        <div className="reader-warn">
          <span className="reader-warn-txt">⚠ {failedPages.size} page{failedPages.size > 1 ? 's' : ''} failed to load - the source may be having trouble. Tap a failed page to retry.</span>
          <button className="reader-warn-x" onClick={() => setWarnAck(failedPages.size)}>Dismiss</button>
        </div>
      )}

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
            <button className="reader-chip" onClick={() => setShowChapters(true)} title="Chapter list">{name || `Chapter ${curNum}`}</button>
            <button className="r-icon" disabled={!nextCh} onClick={() => openChapter(nextCh)} aria-label="Next chapter"><IconChevronRight /></button>
          </div>
        </div>

        <div className="reader-tools" onClick={(e) => e.stopPropagation()}>
          {fsSupported && (
            <button className="r-icon" onClick={toggleFullscreen} aria-label={isFs ? 'Exit fullscreen' : 'Fullscreen'}>
              {isFs ? (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 8h3a2 2 0 0 0 2-2V3M21 8h-3a2 2 0 0 1-2-2V3M3 16h3a2 2 0 0 1 2 2v3M21 16h-3a2 2 0 0 0-2 2v3" /></svg>
              ) : (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3" /></svg>
              )}
            </button>
          )}
          <button className="r-icon" onClick={() => scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' })} aria-label="Top"><IconArrowUp /></button>
          <button className="r-icon" onClick={() => setShowSettings(true)} aria-label="Settings"><IconSettings /></button>
        </div>
      </div>

      {/* Settings bottom-sheet */}
      {showChapters && (
        <div className="sheet-scrim" onClick={closeSheet}>
          <div
            className="sheet chapters-sheet"
            style={{ transform: `translateY(${sheetDrag}px)`, transition: dragging ? 'none' : 'transform .2s ease' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sheet-drag" onPointerDown={sheetDown} onPointerMove={sheetMove} onPointerUp={sheetUp} onPointerCancel={sheetUp}>
              <div className="sheet-handle" />
              <div className="sheet-headrow">
                <span className="sheet-title">Chapters · {chapters.length}</span>
                <button className="sheet-close" onClick={closeSheet} aria-label="Close">✕</button>
              </div>
            </div>
            <div className="chap-list">
              {chapterGroups.map((g) => g.variants.length === 1
                ? chapterRow(g.variants[0])
                : (
                  <div className="chap-group" key={g.key}>
                    <div className="chap-group-h">CH. {g.number > 0 ? g.number : '?'} · {g.variants.length} versions</div>
                    {g.variants.map((c) => chapterRow(c, true))}
                  </div>
                ))}
            </div>
          </div>
        </div>
      )}

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
