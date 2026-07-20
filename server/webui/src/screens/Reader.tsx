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

// Resume mid-chapter: remember how far through each chapter you were (as a 0-1 fraction), keyed by
// source|chapter, in one capped localStorage map. Near-start/near-end positions aren't kept so a
// fresh open (or a finished chapter) starts at the top.
const POS_KEY = 'reader.positions'
function loadPositions(): Record<string, number> {
  try { return JSON.parse(localStorage.getItem(POS_KEY) || '{}') } catch { return {} }
}
function savePosition(key: string, frac: number) {
  if (!key) return
  const m = loadPositions()
  if (frac <= 0.02 || frac >= 0.98) delete m[key]
  else m[key] = Math.round(frac * 1000) / 1000
  const keys = Object.keys(m)
  if (keys.length > 300) delete m[keys[0]] // cap growth
  try { localStorage.setItem(POS_KEY, JSON.stringify(m)) } catch { /* quota */ }
}

/**
 * Collapse duplicate chapters (same number from different scanlators) to one entry per number,
 * preferring the chapter being read, then the same scanlator, then the latest. Mirrors the desktop
 * dedupChapters so prev/next move one real chapter at a time. Keeps source order (newest-first).
 */
// Bounded concurrency for page-image fetches: the reader never uses more than IMG_MAX of the
// browser's ~6 connections-per-origin, so API/cover requests to the same origin are never starved
// while reading a slow/down source. Fetches go through this gate and are abortable.
const IMG_MAX = 4
let imgActive = 0
const imgWaiters: Array<() => void> = []
function imgAcquire(): Promise<void> {
  if (imgActive < IMG_MAX) { imgActive++; return Promise.resolve() }
  return new Promise((res) => imgWaiters.push(() => { imgActive++; res() }))
}
function imgRelease() { imgActive = Math.max(0, imgActive - 1); imgWaiters.shift()?.() }

/** One page in the strip — fetches its image through the concurrency gate (abortable), reserving
 *  height with a spinner until it decodes, then fades it in. */
function ReaderPage({ src, sizing, index, onStatus }: { src: string; sizing: Sizing; index: number; onStatus?: (i: number, failed: boolean) => void }) {
  const [status, setStatus] = useState<'load' | 'ok' | 'err'>('load')
  const [objUrl, setObjUrl] = useState<string | null>(null)
  const [bust, setBust] = useState(0)
  useEffect(() => {
    const ac = new AbortController()
    // Client-side ceiling in case a connection stalls with no response (server already caps at ~7s).
    const kill = window.setTimeout(() => ac.abort(), 15000)
    let created: string | null = null
    setStatus('load'); setObjUrl(null)
    ;(async () => {
      await imgAcquire()
      if (ac.signal.aborted) { imgRelease(); return }
      try {
        const r = await fetch(bust ? src + '&retry=' + bust : src, { signal: ac.signal })
        if (!r.ok) throw new Error('HTTP ' + r.status)
        created = URL.createObjectURL(await r.blob())
        setObjUrl(created); setStatus('ok'); onStatus?.(index, false)
      } catch {
        if (!ac.signal.aborted) { setStatus('err'); onStatus?.(index, true) }
      } finally {
        imgRelease()
      }
    })()
    return () => { window.clearTimeout(kill); ac.abort(); if (created) URL.revokeObjectURL(created) }
  }, [src, bust, index, onStatus])
  function retry(e: React.MouseEvent) { e.stopPropagation(); onStatus?.(index, false); setBust((b) => b + 1) }
  return (
    <div className={'page-slot' + (status === 'ok' ? ' loaded' : '')}>
      {status === 'load' && <div className="spinner sm" />}
      {status === 'err' ? (
        <button type="button" className="page-fail" onClick={retry}>
          <span className="page-fail-icon">↻</span>
          <span>⚠ Page {index + 1} didn't load</span>
          <span className="page-fail-sub">Tap to reload</span>
        </button>
      ) : objUrl ? (
        <img className={'page ' + sizing + ' loaded'} src={objUrl} alt="" draggable={false} />
      ) : null}
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
  const [force, setForce] = useState(false) // you chose to try a known-broken chapter anyway
  // Preload stays off for the first 3s of a chapter. Right after a switch the container has no real
  // height yet, so scroll/height reads as a large fraction and the 40% gate trips instantly - which is
  // why preloads were firing in the same millisecond as the READ they were supposed to follow.
  const [settled, setSettled] = useState(false)
  // Windowing: only mount an <img> for pages up to here (0-based). Grows as you scroll, so opening a
  // chapter fires ~preload requests — not all 68 at once (which would pin every browser connection
  // and freeze the app, esp. on a down source). Reset per chapter.
  const [renderMax, setRenderMax] = useState(0)
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
  const [keepAwake, setKeepAwake] = useState<boolean>(lsGet('reader.awake', '0') === '1')
  const [loadMode, setLoadMode] = useState<LoadMode>((() => { const m = lsGet('reader.loadmode', 'hybrid'); return (m === 'blob' ? 'hybrid' : m) as LoadMode })())
  const [sheetDrag, setSheetDrag] = useState(0)
  const [dragging, setDragging] = useState(false)
  const dragStartY = useRef(0)
  // The reader scrolls the document (window) so mobile browsers auto-hide their address/nav bars.
  // The window scroll listener reads these live refs to avoid re-attaching on every render.
  const countRef = useRef(count)
  const chromeRef = useRef(chrome)
  countRef.current = count
  chromeRef.current = chrome
  // Resume mid-chapter state: fraction to restore to (null once you take over), the current chapter's
  // storage key, the latest progress (for save-on-leave), and a save throttle.
  const pendingRestore = useRef<number | null>(null)
  const posKeyRef = useRef('')
  const progressRef = useRef(0)
  const lastSave = useRef(0)
  posKeyRef.current = sourceId + '|' + chapter
  progressRef.current = progress

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
  useEffect(() => { localStorage.setItem('reader.awake', keepAwake ? '1' : '0') }, [keepAwake])
  // Keep the screen awake while reading (Wake Lock). The lock drops when the tab is backgrounded, so
  // re-acquire when it returns to the foreground.
  useEffect(() => {
    if (!keepAwake) return
    let lock: WakeLockSentinel | null = null
    let released = false
    const acquire = async () => {
      try { lock = (await navigator.wakeLock?.request('screen')) ?? null } catch { /* denied / unsupported */ }
    }
    acquire()
    const onVis = () => { if (document.visibilityState === 'visible' && !released) acquire() }
    document.addEventListener('visibilitychange', onVis)
    return () => { released = true; document.removeEventListener('visibilitychange', onVis); lock?.release().catch(() => {}) }
  }, [keepAwake])
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
    if (pendingRestore.current != null) applyRestore() // page loaded → doc taller → re-pin resume point
  }, [])

  // Take over the window scroll while the reader is mounted: save the app-shell scroll position and
  // reset to top, restore it on exit, and disable the browser's own restoration so our per-chapter
  // reset wins. Runs before the chapter effect below so it captures the position before that resets it.
  useEffect(() => {
    const prevRestore = history.scrollRestoration
    try { history.scrollRestoration = 'manual' } catch { /* older browsers */ }
    const savedAppScroll = window.scrollY
    window.scrollTo(0, 0)
    return () => {
      try { history.scrollRestoration = prevRestore } catch { /* */ }
      window.scrollTo(0, savedAppScroll)
    }
  }, [])

  useEffect(() => {
    const key = sourceId + '|' + chapter
    pendingRestore.current = loadPositions()[key] ?? null // resume point for this chapter, if any
    setCount(null); setPage(1); setProgress(0); setRenderMax(0); setFailedPages(new Set()); setWarnAck(0); setShowChapters(false); setForce(false); setSettled(false)
    window.scrollTo(0, 0)
    const settle = setTimeout(() => setSettled(true), 3000)
    api.pages(sourceId, chapter, title, name).then((r) => setCount(r.count)).catch(() => setCount(0))
    api.mangaState(sourceId, manga).then((s) => setReadUrls(new Set(s.read))).catch(() => {}) // read markers for the chapter list
    // Mark read + record history (with the cover, once detail resolves) for "Continue reading".
    api.setRead(sourceId, manga, chapter, true)
    api.detail(sourceId, manga)
      .then((d) => { setChapters(d.chapters); api.recordHistory(sourceId, manga, chapter, title, name, d.manga.thumbnailUrl) })
      .catch(() => api.recordHistory(sourceId, manga, chapter, title, name))
    return () => {
      clearTimeout(settle)
      savePosition(key, progressRef.current) // persist the resume point when leaving this chapter
    }
  }, [sourceId, chapter])

  // Navigate the de-duplicated list (scanlator-aware) so prev/next skip duplicate chapters.
  const cur = chapters.find((c) => c.url === chapter)
  // We already recorded that this chapter's images are gone (UnavailableChapters). Rendering the pages
  // would fire a burst of requests we know will 404 - which is also what trips the source-wide image
  // breaker and drags down the chapter you switch to. So ask first, and don't touch the network unless
  // you say to.
  const gated = !!cur?.unavailable && !force
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
    if (!settled) return // see `settled`: the first 3s of a chapter give a meaningless progress reading
    if (progress <= 0.4) return // fire earlier than mid-chapter for more lead time before you flip
    if (!nextCh) { console.log('[reader] past preload point — waiting for chapter list to preload next'); return }
    // Never warm a chapter we already know is broken: it is exactly the burst of doomed image requests
    // the gate exists to avoid, and the reader would gate it anyway the moment you flipped to it.
    if (nextCh.unavailable) {
      const scan = nextCh.scanlator || 'unknown scan'
      console.log(`[reader] not preloading "${nextCh.name}" (${scan}) — known broken: ${nextCh.unavailable}`)
      // One no-op ping so the skip is visible in the server log next to the real PRELOAD lines. The
      // server answers 204 without touching the source, so this costs nothing it was going to cost anyway.
      fetch(pageUrl(sourceId, nextCh.url, 0, title, nextCh.name) + `&scan=${encodeURIComponent(scan)}`,
        { headers: { 'X-Preload-Skip': nextCh.unavailable } }).catch(() => {})
      return
    }
    if (prefetchedNext.current === nextCh.url) return
    prefetchedNext.current = nextCh.url
    api.pages(sourceId, nextCh.url, title, nextCh.name).then((r) => {
      const n = Math.min(6, r.count)
      console.log(`[reader] preloading next chapter "${nextCh.name || nextCh.url}" — ${n}/${r.count} pages`)
      // One header-tagged request so the preload stays visible in the server log.
      fetch(pageUrl(sourceId, nextCh.url, 0, title, nextCh.name), { headers: { 'X-Preload': '1' } }).catch(() => {})
      // Warm the browser IMAGE cache via new Image() — the same method the info page uses for its
      // instant first-chapter open — so the pages are ready to paint on arrival, not just cached bytes.
      // ?pre=1 rather than a header: new Image() cannot set headers, so this is the only way the server
      // can tell a warm-up from a real read - and it must be able to, to refuse warming a broken chapter.
      for (let i = 0; i < n; i++) { const im = new Image(); im.src = pageUrl(sourceId, nextCh.url, i, title, nextCh.name) + '&pre=1' }
    }).catch(() => { prefetchedNext.current = '' })
  }, [progress, nextCh, sourceId, title, settled])

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

  // Document-scroll handler: reads window scroll so the browser's chrome auto-hides as you scroll.
  const onScroll = useCallback(() => {
    const c = countRef.current
    if (!c) return
    const doc = document.scrollingElement || document.documentElement
    const max = doc.scrollHeight - window.innerHeight
    const y = window.scrollY
    const p = max > 0 ? y / max : 0
    setProgress(p)
    setPage(Math.min(c, Math.max(1, Math.round(p * (c - 1)) + 1)))
    // Mount page <img>s a few ahead of where you're reading; monotonic so it never unmounts.
    const cur0 = Math.max(0, Math.round(p * (c - 1)))
    setRenderMax((m) => Math.max(m, cur0 + 5))
    // Chrome shows at the start and again at the END of the chapter (so prev/next are there when you
    // finish); in between, scrolling away from the top hides it.
    const nearBottom = max > 0 && max - y < 120
    if (nearBottom) { if (!chromeRef.current) setChrome(true) } else if (chromeRef.current && y > 40) setChrome(false)
    // Remember the resume position while the user is in control (skip during a programmatic restore).
    if (pendingRestore.current == null) {
      const now = Date.now()
      if (now - lastSave.current > 400) { lastSave.current = now; savePosition(posKeyRef.current, p) }
    }
  }, [])
  useEffect(() => {
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [onScroll])

  // Resume mid-chapter: scroll to the saved fraction of the document. Re-applied as pages load (doc
  // grows) until you take over with a real gesture.
  const applyRestore = useCallback(() => {
    const frac = pendingRestore.current
    if (frac == null) return
    const doc = document.scrollingElement || document.documentElement
    const max = doc.scrollHeight - window.innerHeight
    if (max > 0) window.scrollTo(0, frac * max)
  }, [])
  useEffect(() => {
    if (count && count > 0 && pendingRestore.current != null) requestAnimationFrame(applyRestore)
  }, [count, applyRestore])
  // Orientation / window resize changes the document height — re-pin the resume point if we haven't
  // handed control back to the reader yet (otherwise a rotate right after opening loses your place).
  useEffect(() => {
    const onResize = () => { if (pendingRestore.current != null) applyRestore() }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [applyRestore])
  useEffect(() => {
    const stop = () => { pendingRestore.current = null } // a real scroll/keypress means you've taken over
    window.addEventListener('touchmove', stop, { passive: true })
    window.addEventListener('wheel', stop, { passive: true })
    window.addEventListener('keydown', stop)
    return () => { window.removeEventListener('touchmove', stop); window.removeEventListener('wheel', stop); window.removeEventListener('keydown', stop) }
  }, [])

  // Tap does nothing (so reading isn't interrupted); a double-tap toggles the chrome.
  const lastTap = useRef(0)
  function onTap() {
    const now = Date.now()
    if (now - lastTap.current < 300) { setChrome((v) => !v); lastTap.current = 0 } else lastTap.current = now
  }

  // Grow the render window from the ACTUAL load boundary, not a scroll-fraction estimate. A sentinel is
  // rendered just past the last mounted page; when it comes within ~2 screens of the viewport the window
  // extends. This is immune to the loaded-vs-reserved height mismatch (loaded pages ~130vh vs 60vh slots)
  // that made the old estimate stall mid-chapter, so ~2 screens of pages stay mounted ahead of you.
  const sentinelObs = useRef<IntersectionObserver | null>(null)
  const setSentinel = useCallback((el: HTMLDivElement | null) => {
    sentinelObs.current?.disconnect()
    if (!el) return
    const io = new IntersectionObserver(
      (entries) => { if (entries.some((e) => e.isIntersecting)) setRenderMax((m) => m + 4) },
      { rootMargin: '0px 0px 200% 0px' },
    )
    io.observe(el)
    sentinelObs.current = io
  }, [])
  useEffect(() => () => sentinelObs.current?.disconnect(), [])

  return (
    <div className="reader">
      <div className="reader-scroll" onClick={onTap}>
        {count === null && !gated && <div className="spinner" />}
        {(gated || count === 0) && (() => {
          // This chapter won't load — but another scanlation of the same number might, and we very
          // likely have one. Offering them here beats sending you back to the chapter list to work
          // out which of "Ch. 90" is the one that works.
          const others = cur
            ? chapters.filter((c) => c.url !== cur.url && c.number > 0 && c.number === cur.number)
            : []
          return (
            <div className="r-failed-ov" role="dialog" aria-modal="true">
              <div className="r-failed">
                <div className="r-failed-title">{gated ? 'This chapter is known to be broken' : "Couldn't load this chapter"}</div>
                <div className="r-failed-why">
                {cur?.unavailable
                  ? cur.unavailable
                  : `${cur?.scanlator ? `${cur.scanlator}'s ` : ''}copy of this chapter didn't load.`}
              </div>
                {others.length > 0 && (
                <>
                  <div className="r-failed-alt">Other scanlation{others.length === 1 ? '' : 's'} of this chapter:</div>
                  {others.map((o) => (
                    <button key={o.url} className="btn primary r-failed-btn" onClick={() => openChapter(o)}>
                      Read {o.scanlator || 'another version'}
                      {o.unavailable ? ' (also reported broken)' : ''}
                    </button>
                  ))}
                </>
              )}
                {gated && (
                <button className="btn r-failed-btn" onClick={() => setForce(true)}>Try loading it anyway</button>
              )}
                <button className="btn r-failed-btn" onClick={() => nav(-1)}>Back to chapters</button>
              </div>
            </div>
          )
        })()}
        {count !== null && count > 0 && !gated && (() => {
          // Windowing: mount an <img> for pages up to renderCeil; pages beyond are empty height-reserved
          // slots with NO request. EAGER honors its promise ("loads every page at once") and mounts the
          // whole chapter — they queue through the IMG_MAX gate in order, so it's a sequential full-chapter
          // prefetch (ideal for a downloaded manga). Other modes stay windowed, but the window now grows
          // from the sentinel observer (see setSentinel) instead of a scroll-fraction estimate, so a
          // slow/down source still can't pin every connection AND the next pages are always mounted ahead.
          const renderCeil = loadMode === 'eager' ? count - 1 : Math.max(renderMax, Math.max(preload, 3))
          return (
            <div className="strip" style={{ gap: gap + 'px' }}>
              {Array.from({ length: count }, (_, i) => {
                if (i > renderCeil) return <div key={i} className="page-slot" aria-hidden />
                return (
                  <React.Fragment key={i}>
                    <ReaderPage index={i} src={pageUrl(sourceId, chapter, i, title, name)} sizing={sizing} onStatus={reportStatus} />
                    {i === renderCeil && renderCeil < count - 1 && <div ref={setSentinel} className="reader-sentinel" aria-hidden />}
                  </React.Fragment>
                )
              })}
            </div>
          )
        })()}
      </div>

      {/* Source-trouble banner: shown when pages are failing, so it's never a silent infinite spinner.
          Dismissable (acknowledge) — reappears only if more pages fail after you dismiss it. */}
      {failedPages.size > warnAck && (() => {
        // Pages 404 individually while the page LIST loads fine, so a broken chapter shows up here
        // rather than as an empty chapter. If we hold another scanlation of this same chapter, offer
        // it — retrying pages that are gone from the host will never work.
        const alts = cur ? chapters.filter((c) => c.url !== cur.url && c.number > 0 && c.number === cur.number) : []
        return (
          <div className="reader-warn">
            <span className="reader-warn-txt">
              ⚠ {failedPages.size} page{failedPages.size > 1 ? 's' : ''} failed to load - the source may be having trouble.
              {alts.length > 0 ? ' Try another scanlation, or tap a failed page to retry.' : ' Tap a failed page to retry.'}
            </span>
            {alts.map((o) => (
              <button key={o.url} className="reader-warn-alt" onClick={() => openChapter(o)}>
                Read {o.scanlator || 'another version'}
              </button>
            ))}
            <button className="reader-warn-x" onClick={() => setWarnAck(failedPages.size)}>Dismiss</button>
          </div>
        )
      })()}

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
          <button className="r-icon" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })} aria-label="Top"><IconArrowUp /></button>
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

            {(loadMode === 'balanced' || loadMode === 'hybrid') && (
              <>
                <div className="sheet-label">Preload · {preload} page{preload === 1 ? '' : 's'}</div>
                <input className="slider" type="range" min={0} max={10} value={preload} onChange={(e) => setPreload(Number(e.target.value))} />
              </>
            )}

            <button className="sheet-toggle" onClick={() => setShowPill((v) => !v)}>
              <span>Progress pill</span>
              <span className={'switch' + (showPill ? ' on' : '')}><span className="knob" /></span>
            </button>

            <button className="sheet-toggle" onClick={() => setKeepAwake((v) => !v)}>
              <span>Keep screen on</span>
              <span className={'switch' + (keepAwake ? ' on' : '')}><span className="knob" /></span>
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
