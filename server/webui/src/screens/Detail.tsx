import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, coverUrl, pageUrl, mediaType, STATUS_LABELS, Detail as DetailT, MangaState, Source } from '../api'
import { IconArrowLeft, IconBookmarkSm, IconClock, IconBook, IconPen, IconCalendar, IconBookOpen, IconSort, IconDownload, IconDots, IconJetBrains } from '../components/icons'
import { ProgressRing } from '../components/ProgressRing'
import { ConfirmDialog, ConfirmSpec } from '../components/ConfirmDialog'
import { DetailSkeleton } from '../components/Skeleton'
import { ErrorPanel } from '../components/ErrorPanel'
import noPoster from '../assets/no-poster.png'

// Chapters oldest-first, for finding the "next to read". Sorting by chapter number only works when
// the source actually parses numbers; some (e.g. aquamanga) leave them at -1, which turns the sort
// into a no-op and makes "first unread" the NEWEST chapter. So fall back to reversing the source
// order (Tachiyomi sources list newest-first) when numbers aren't usable.
function readingOrder(chapters: DetailT['chapters']): DetailT['chapters'] {
  const positives = chapters.filter((c) => c.number > 0)
  const usable = positives.length >= chapters.length * 0.6 && new Set(positives.map((c) => c.number)).size > 1
  return usable
    ? [...chapters].sort((a, b) => (a.number || 0) - (b.number || 0))
    : [...chapters].slice().reverse()
}

// Read-state is tracked per chapter URL, but a chapter can exist under several scanlators (each a
// distinct URL). You read ONE scanlator's Ch.1, so Ch.1 is read — regardless of which scanlator row
// you look at. Collapse read-state to a per-chapter KEY: the parsed number if the source gives one,
// else the number pulled out of the chapter name (many sources leave number at -1 but name it
// "Chapter 1"), else the raw name. A key is read if any of its variants is.
function chapterKey(c: DetailT['chapters'][number]): string {
  if (c.number > 0) return 'n' + c.number
  const m = c.name.match(/(\d+(?:\.\d+)?)/)
  return m ? 'n' + parseFloat(m[1]) : 't' + c.name.trim().toLowerCase()
}
function readKeySet(chapters: DetailT['chapters'], readSet: Set<string>): Set<string> {
  const s = new Set<string>()
  for (const c of chapters) if (readSet.has(c.url)) s.add(chapterKey(c))
  return s
}
function chapterRead(c: DetailT['chapters'][number], readSet: Set<string>, keys: Set<string>): boolean {
  return readSet.has(c.url) || keys.has(chapterKey(c))
}

function relative(ms: number): string {
  if (!ms) return ''
  const d = Math.floor((Date.now() - ms) / 86400000)
  if (d <= 0) return 'today'
  if (d === 1) return 'yesterday'
  if (d < 30) return `${d} days ago`
  const mo = Math.floor(d / 30)
  if (mo < 12) return `${mo} mo ago`
  return `${Math.floor(d / 365)} yr ago`
}
const dateFmt = (ms: number) => (ms > 0 ? new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit' }) : '')

type Tab = 'all' | 'unread' | 'read'

export function Detail() {
  const { sourceId = '' } = useParams()
  const [sp] = useSearchParams()
  const url = sp.get('url') || ''
  const nav = useNavigate()

  const [data, setData] = useState<DetailT | null>(null)
  const [state, setState] = useState<MangaState>({ inLibrary: false, bookmarked: false, read: [], bookmarks: [] })
  const [readSet, setReadSet] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [descOpen, setDescOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [checking, setChecking] = useState(false)
  const [tab, setTab] = useState<Tab>('all')
  const [asc, setAsc] = useState(false)
  const [confirm, setConfirm] = useState<ConfirmSpec | null>(null)
  const [tries, setTries] = useState(0)
  const [stateLoaded, setStateLoaded] = useState(false)
  const [source, setSource] = useState<Source | null>(null)
  const [srcKnown, setSrcKnown] = useState<boolean | null>(null) // is this source installed? null = still loading
  const [dlMsg, setDlMsg] = useState('')
  const [dlProg, setDlProg] = useState<Record<string, { done: number; total: number; state: string }>>({})
  const [selecting, setSelecting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [menuOpen, setMenuOpen] = useState(false)
  const [dlFilter, setDlFilter] = useState<'all' | 'dl' | 'undl'>('all')

  useEffect(() => {
    // Abort the detail fetch if you navigate away — the backend cancels the (possibly slow/failing)
    // source call instead of letting it hang ~20s and tie up a thread after you've left.
    const ac = new AbortController()
    setData(null); setError(null); setStateLoaded(false); setSrcKnown(null)
    api.detail(sourceId, url, false, ac.signal).then(setData).catch((e) => { if (!ac.signal.aborted) setError(e instanceof Error ? e.message : "Couldn't load this manga.") })
    api.mangaState(sourceId, url).then((s) => { setState(s); setReadSet(new Set(s.read)) }).catch(() => {}).finally(() => setStateLoaded(true))
    api.sources().then((all) => { const s = all.find((x) => x.id === sourceId) ?? null; setSource(s); setSrcKnown(!!s) }).catch(() => {})
    return () => ac.abort()
  }, [sourceId, url, tries])

  // Poll the download queue for live per-chapter progress; refetch detail when downloads finish.
  useEffect(() => {
    let alive = true
    let timer: ReturnType<typeof setTimeout>
    let prevActive = 0
    const tick = async () => {
      const d = await api.downloads().catch(() => null)
      if (!alive) return
      if (d) {
        const map: Record<string, { done: number; total: number; state: string }> = {}
        for (const t of d.tasks) if (t.state === 'running' && t.currentChapterUrl) map[t.currentChapterUrl] = { done: t.pagesDone, total: t.pagesTotal, state: 'running' }
        setDlProg(map)
        if (prevActive > 0 && d.active === 0) api.detail(sourceId, url).then(setData).catch(() => {})
        prevActive = d.active
      }
      timer = setTimeout(tick, d && d.active > 0 ? 1200 : 5000)
    }
    tick()
    return () => { alive = false; clearTimeout(timer) }
  }, [sourceId, url])

  // Preload the chapter you'd continue from (first unread, or ch.1) so opening it is instant.
  const prefetchedCont = useRef('')
  useEffect(() => {
    if (!data || !stateLoaded) return
    const key = sourceId + '|' + url
    if (prefetchedCont.current === key) return
    prefetchedCont.current = key
    const ordered = readingOrder(data.chapters)
    const cont = ordered.find((c) => !chapterRead(c, readSet, readKeySet(data.chapters, readSet))) || ordered[ordered.length - 1] || data.chapters[0]
    if (cont) {
      api.pages(sourceId, cont.url, data.manga.title, cont.name)
        .then((r) => { for (let i = 0; i < Math.min(4, r.count); i++) { const im = new Image(); im.src = pageUrl(sourceId, cont.url, i, data.manga.title, cont.name) } })
        .catch(() => {})
    }
  }, [data, stateLoaded, readSet, sourceId, url])

  const chapterTotal = useMemo(() => {
    if (!data) return 0
    const max = Math.max(0, ...data.chapters.map((c) => c.number).filter((n) => n > 0))
    return max > 0 ? max : data.chapters.length
  }, [data])
  const dates = useMemo(() => (data?.chapters.map((c) => c.dateUpload).filter((d) => d > 0)) || [], [data])
  const year = dates.length ? new Date(Math.min(...dates)).getFullYear() : null
  const lastUpdate = dates.length ? Math.max(...dates) : 0

  const title = () => data?.manga.title || url
  const close = () => setConfirm(null)
  async function doAdd() {
    close(); setBusy(true)
    try { await api.addLibrary(sourceId, url); setState((s) => ({ ...s, inLibrary: true })) } finally { setBusy(false) }
  }
  async function doRemove() {
    close(); setBusy(true)
    try { await api.removeLibrary(sourceId, url); setState((s) => ({ ...s, inLibrary: false })) } finally { setBusy(false) }
  }
  function askRemove() {
    setConfirm({ title: 'Remove from library?', message: `Remove “${title()}” from your library?`, confirmLabel: 'Remove', danger: true, onConfirm: doRemove, onCancel: close })
  }
  function toggleLibrary() {
    if (busy) return
    if (!state.inLibrary) {
      setConfirm({ title: 'Add to library?', message: `Add “${title()}” to your library?`, confirmLabel: 'Add', onConfirm: doAdd, onCancel: close })
      return
    }
    // Removing: if there are downloads, offer to delete them first, then confirm removal.
    api.downloadCount(title())
      .then(({ count }) => {
        if (count > 0) {
          setConfirm({
            title: 'Delete downloads?',
            message: `“${title()}” has ${count} downloaded chapter(s). Delete the downloaded files too?`,
            confirmLabel: 'Delete downloads', cancelLabel: 'Keep downloads', danger: true,
            onConfirm: () => { api.deleteDownloads(title()); askRemove() },
            onCancel: askRemove, // "Keep downloads" → still remove from library
            onDismiss: close, // tapping outside → abort removal entirely
          })
        } else askRemove()
      })
      .catch(askRemove)
  }

  async function scanUpdates() {
    if (checking) return
    setChecking(true)
    try { setData(await api.detail(sourceId, url, true)) } finally { setChecking(false) }
  }

  function migrate() {
    setConfirm({
      title: 'Enter migration mode?',
      message: `Move “${title()}” to another source, carrying your read progress, bookmarks and continue-reading?`,
      confirmLabel: 'Enter migration', danger: true, onCancel: close,
      onConfirm: () => { close(); nav(`/migrate?source=${sourceId}&url=${encodeURIComponent(url)}&title=${encodeURIComponent(title())}`) },
    })
  }

  function openContinue() {
    if (!data) return
    const ordered = readingOrder(data.chapters)
    const c = ordered.find((c) => !chapterRead(c, readSet, readKeySet(data.chapters, readSet))) || ordered[ordered.length - 1] || data.chapters[0]
    if (c) openChapter(c.url, c.name)
  }
  function openChapter(chUrl: string, name: string) {
    nav(`/reader/${sourceId}?manga=${encodeURIComponent(url)}&chapter=${encodeURIComponent(chUrl)}&name=${encodeURIComponent(name)}&title=${encodeURIComponent(data!.manga.title)}`)
  }
  let dlTimer: ReturnType<typeof setTimeout>
  function toast(msg: string) { setDlMsg(msg); clearTimeout(dlTimer); dlTimer = setTimeout(() => setDlMsg(''), 2500) }
  function downloadChapters(chs: { url: string; name: string }[]) {
    if (chs.length === 0) return
    api.enqueueDownload(sourceId, url, data!.manga.title, chs).catch(() => {})
    toast(chs.length === 1 ? 'Chapter queued for download' : `${chs.length} chapters queued — see Downloads`)
  }
  function downloadMissing() {
    const missing = data!.chapters.filter((c) => !c.downloaded).map((c) => ({ url: c.url, name: c.name }))
    if (missing.length === 0) { toast('All chapters already downloaded'); return }
    downloadChapters(missing)
  }
  function bulkSetRead(urls: string[], read: boolean) {
    const next = new Set(readSet)
    urls.forEach((u) => { if (read) next.add(u); else next.delete(u); api.setRead(sourceId, url, u, read) })
    setReadSet(next)
  }
  function toggleSelect(chUrl: string) {
    setSelected((s) => { const n = new Set(s); if (n.has(chUrl)) n.delete(chUrl); else n.add(chUrl); return n })
  }
  function exitSelect() { setSelecting(false); setSelected(new Set()) }

  // A shared link may point at a source this server doesn't have installed → a clear, actionable
  // card beats a generic "couldn't load" error.
  if (error && srcKnown === false) return (
    <BackWrap nav={nav}>
      <div className="center-msg">
        <div style={{ fontWeight: 600, color: 'var(--text)', marginBottom: 8 }}>Source not installed</div>
        <div className="set-hint" style={{ marginBottom: 16 }}>This link is from an extension that isn’t installed on this server, so its pages can’t be loaded here.</div>
        <button className="btn primary" onClick={() => nav('/extensions')}>Open Extensions</button>
      </div>
    </BackWrap>
  )
  if (error) return <BackWrap nav={nav}><ErrorPanel onRetry={() => setTries((t) => t + 1)} message={error} /></BackWrap>
  if (!data) return <BackWrap nav={nav}><DetailSkeleton /></BackWrap>

  const m = data.manga
  const type = mediaType(m.genre)
  const genres = (m.genre || '').split(',').map((g) => g.trim()).filter(Boolean)
  // Some sources don't expose a status — show "Unknown" rather than nothing (still tappable to scan).
  const status = STATUS_LABELS[m.status] || 'Unknown'
  const newSet = new Set(data.newChapters)
  // The chapter you'd resume on (first unread ascending, else the latest) — gets a "Resume" marker.
  const readNums = readKeySet(data.chapters, readSet)
  const resumeUrl = (() => {
    const ordered = readingOrder(data.chapters)
    return (ordered.find((c) => !chapterRead(c, readSet, readNums)) || ordered[ordered.length - 1])?.url
  })()

  let chaps = data.chapters
  if (tab === 'unread') chaps = chaps.filter((c) => !chapterRead(c, readSet, readNums))
  else if (tab === 'read') chaps = chaps.filter((c) => chapterRead(c, readSet, readNums))
  if (dlFilter === 'dl') chaps = chaps.filter((c) => c.downloaded)
  else if (dlFilter === 'undl') chaps = chaps.filter((c) => !c.downloaded)
  chaps = [...chaps].sort((a, b) => (asc ? a.number - b.number : b.number - a.number))

  // Group adjacent same-number chapters (duplicate chapters from different scanlators) → "CH. N" card.
  const groups: { number: number; items: typeof chaps }[] = []
  for (const c of chaps) {
    const last = groups[groups.length - 1]
    if (last && last.number === c.number && c.number > 0) last.items.push(c)
    else groups.push({ number: c.number, items: [c] })
  }
  const renderRow = (c: (typeof chaps)[number]) => {
    const read = chapterRead(c, readSet, readNums)
    const bm = state.bookmarks.includes(c.url)
    const meta = [c.scanlator, dateFmt(c.dateUpload)].filter(Boolean).join('  ·  ')
    const prog = dlProg[c.url]
    const downloading = prog && (prog.state === 'running' || prog.state === 'queued')
    const sel = selected.has(c.url)
    const onRow = () => { if (selecting) toggleSelect(c.url); else openChapter(c.url, c.name) }
    return (
      <div key={c.url} className={'chapter-row' + (read ? ' read' : '') + (c.url === resumeUrl ? ' resume' : '') + (sel ? ' sel' : '')} onClick={onRow}>
        {selecting && <span className={'ch-check' + (sel ? ' on' : '')} />}
        <div className="chapter-text">
          <div className="chapter-name">{newSet.has(c.url) && <span className="chapter-new">NEW</span>}{c.url === resumeUrl && !read && <span className="chapter-resume">RESUME</span>}{c.name}</div>
          {meta && <div className="chapter-meta">{meta}</div>}
        </div>
        {!selecting && (downloading
          ? <span className="chapter-dlbtn"><ProgressRing pct={prog.total > 0 ? (prog.done / prog.total) * 100 : 0} size={26} /></span>
          : <button className={'chapter-dlbtn' + (c.downloaded ? ' done' : '')} onClick={(e) => { e.stopPropagation(); if (!c.downloaded) downloadChapters([{ url: c.url, name: c.name }]) }} title={c.downloaded ? 'Downloaded' : 'Download'} aria-label="Download"><IconDownload /></button>)}
        {bm && <IconBookmarkSm filled className="chapter-bm" />}
      </div>
    )
  }

  return (
    <BackWrap nav={nav}>
      <div className="detail-head">
        <div className="detail-cover">
          {m.thumbnailUrl
            ? <img src={coverUrl(sourceId, m.thumbnailUrl, m.title)} alt="" onError={(e) => { if (e.currentTarget.src !== noPoster) e.currentTarget.src = noPoster }} />
            : <img src={noPoster} alt="" />}
        </div>
        <div className="detail-head-info">
          <h1 className="detail-title">{m.title}</h1>
          {source && (
            <div className="meta-tag">
              <span className="meta-tag-k">Source</span>
              <span className="meta-tag-v">{source.name}{source.lang ? ` (${source.lang.toUpperCase()})` : ''}</span>
              {source.usesWebView && <IconJetBrains className="src-wv" />}
            </div>
          )}
        </div>
      </div>

      {genres.length > 0 && (
        <div className="chips-2">
          <div className="chip-row">{genres.filter((_, i) => i % 2 === 0).map((g) => <span className="chip" key={g}>{g}</span>)}</div>
          {genres.length > 1 && <div className="chip-row">{genres.filter((_, i) => i % 2 === 1).map((g) => <span className="chip" key={g}>{g}</span>)}</div>}
        </div>
      )}

      <div className="pills">
        {type && <span className={'pill ' + type}>{type[0].toUpperCase() + type.slice(1)}</span>}
        {status && (
          <button className="pill accent" onClick={scanUpdates} title="Tap to check for updates">
            <IconClock className="pi" />{checking ? 'Checking…' : status}
          </button>
        )}
        <span className="pill"><IconBook className="pi" />{chapterTotal}</span>
        <span className="pill" title="Downloaded chapters"><IconDownload className="pi" />{data.chapters.filter((c) => c.downloaded).length}/{data.chapters.length}</span>
        {year && <span className="pill"><IconCalendar className="pi" />{year}</span>}
        {m.author && <span className="pill"><IconPen className="pi" />{m.author}</span>}
      </div>
      {lastUpdate > 0 && <div className="last-update" onClick={scanUpdates}>Updated {relative(lastUpdate)} · tap “{status || 'status'}” to check</div>}

      {m.description && (
        <div className={'synopsis' + (descOpen ? '' : ' clamp')} onClick={() => setDescOpen((v) => !v)}>
          {m.description}
        </div>
      )}

      {selecting ? (
        <div className="sel-bar">
          <button className="sel-link" onClick={exitSelect}>Cancel</button>
          <span className="sel-count">{selected.size} selected</span>
          <div className="sel-actions">
            <button className="sel-link" disabled={selected.size === 0} onClick={() => { downloadChapters(data.chapters.filter((c) => selected.has(c.url) && !c.downloaded).map((c) => ({ url: c.url, name: c.name }))); exitSelect() }}>Download</button>
            <button className="sel-link" disabled={selected.size === 0} onClick={() => { bulkSetRead([...selected], true); exitSelect() }}>Read</button>
            <button className="sel-link" disabled={selected.size === 0} onClick={() => { bulkSetRead([...selected], false); exitSelect() }}>Unread</button>
          </div>
        </div>
      ) : (
        <div className="ch-toolbar">
          <div className="ch-tabs">
            {(['all', 'unread', 'read'] as Tab[]).map((t) => (
              <button key={t} className={'ch-tab' + (tab === t ? ' active' : '')} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>
            ))}
          </div>
          <button className="ch-sort" onClick={() => setAsc((v) => !v)} title={asc ? 'Oldest first' : 'Newest first'}>
            <IconSort className="pi" />{asc ? 'Asc' : 'Desc'}
          </button>
          <button className="ch-sort ico" onClick={() => setMenuOpen((v) => !v)} aria-label="More"><IconDots className="pi" /></button>
        </div>
      )}
      {menuOpen && (
        <div className="ch-menu-scrim" onClick={() => setMenuOpen(false)}>
          <div className="ch-menu" onClick={(e) => e.stopPropagation()}>
            <button onClick={() => { setSelecting(true); setSelected(new Set()); setMenuOpen(false) }}>Select chapters</button>
            <button onClick={() => { downloadMissing(); setMenuOpen(false) }}>Download missing</button>
            <div className="ch-menu-sep" />
            <button className={dlFilter === 'all' ? 'on' : ''} onClick={() => { setDlFilter('all'); setMenuOpen(false) }}>All chapters</button>
            <button className={dlFilter === 'dl' ? 'on' : ''} onClick={() => { setDlFilter('dl'); setMenuOpen(false) }}>Downloaded only</button>
            <button className={dlFilter === 'undl' ? 'on' : ''} onClick={() => { setDlFilter('undl'); setMenuOpen(false) }}>Not downloaded only</button>
            <div className="ch-menu-sep" />
            <button onClick={() => { bulkSetRead(data.chapters.map((c) => c.url), true); setMenuOpen(false) }}>Mark all read</button>
            <button onClick={() => { bulkSetRead(data.chapters.map((c) => c.url), false); setMenuOpen(false) }}>Mark all unread</button>
            {state.inLibrary && <>
              <div className="ch-menu-sep" />
              <button className="ch-menu-mig" onClick={() => { setMenuOpen(false); migrate() }}>Migrate to another source</button>
            </>}
          </div>
        </div>
      )}
      {dlMsg && <div className="dl-toast">{dlMsg}</div>}

      <div className="chapters">
        {groups.map((g, i) =>
          g.items.length > 1 ? (
            <div className="ch-group" key={'g' + i}>
              <div className="ch-group-head">CH. {Number.isInteger(g.number) ? g.number : g.number}</div>
              {g.items.map(renderRow)}
            </div>
          ) : (
            renderRow(g.items[0])
          ),
        )}
        {chaps.length === 0 && <div className="center-msg">No chapters here.</div>}
      </div>

      <div style={{ height: 86 }} />

      <div className="detail-bottombar">
        <button className={'bb-icon' + (state.inLibrary ? ' on' : '')} onClick={toggleLibrary} disabled={busy} aria-label="Bookmark / library">
          <IconBookmarkSm filled={state.inLibrary} />
        </button>
        <div style={{ flex: 1 }} />
        <button className="bb-book" onClick={openContinue} aria-label="Read"><IconBookOpen /></button>
      </div>

      {confirm && <ConfirmDialog spec={confirm} />}
    </BackWrap>
  )
}

function BackWrap({ nav, children }: { nav: ReturnType<typeof useNavigate>; children: React.ReactNode }) {
  return (
    <>
      <button className="detail-back" onClick={() => nav(-1)} aria-label="Back"><IconArrowLeft /></button>
      {children}
    </>
  )
}
