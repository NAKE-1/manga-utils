import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, coverUrl, mediaType, STATUS_LABELS, Detail as DetailT, MangaState } from '../api'
import { IconArrowLeft, IconBookmarkSm, IconClock, IconBook, IconPen, IconCalendar, IconBookOpen, IconSort, IconDownload } from '../components/icons'
import { ConfirmDialog, ConfirmSpec } from '../components/ConfirmDialog'
import { DetailSkeleton } from '../components/Skeleton'

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
  const [error, setError] = useState(false)
  const [descOpen, setDescOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [checking, setChecking] = useState(false)
  const [tab, setTab] = useState<Tab>('all')
  const [asc, setAsc] = useState(false)
  const [confirm, setConfirm] = useState<ConfirmSpec | null>(null)

  useEffect(() => {
    setData(null); setError(false)
    api.detail(sourceId, url).then(setData).catch(() => setError(true))
    api.mangaState(sourceId, url).then((s) => { setState(s); setReadSet(new Set(s.read)) }).catch(() => {})
  }, [sourceId, url])

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

  function toggleRead(e: React.MouseEvent, chUrl: string) {
    e.stopPropagation()
    const now = !readSet.has(chUrl)
    const next = new Set(readSet)
    now ? next.add(chUrl) : next.delete(chUrl)
    setReadSet(next)
    api.setRead(sourceId, url, chUrl, now)
  }

  function openContinue() {
    if (!data) return
    const ordered = [...data.chapters].sort((a, b) => (a.number || 0) - (b.number || 0))
    const c = ordered.find((c) => !readSet.has(c.url)) || ordered[ordered.length - 1] || data.chapters[0]
    if (c) openChapter(c.url, c.name)
  }
  function openChapter(chUrl: string, name: string) {
    nav(`/reader/${sourceId}?manga=${encodeURIComponent(url)}&chapter=${encodeURIComponent(chUrl)}&name=${encodeURIComponent(name)}&title=${encodeURIComponent(data!.manga.title)}`)
  }

  if (error) return <BackWrap nav={nav}><div className="center-msg">Couldn't load this manga.</div></BackWrap>
  if (!data) return <BackWrap nav={nav}><DetailSkeleton /></BackWrap>

  const m = data.manga
  const type = mediaType(m.genre)
  const genres = (m.genre || '').split(',').map((g) => g.trim()).filter(Boolean)
  const status = STATUS_LABELS[m.status] || ''

  let chaps = data.chapters
  if (tab === 'unread') chaps = chaps.filter((c) => !readSet.has(c.url))
  else if (tab === 'read') chaps = chaps.filter((c) => readSet.has(c.url))
  chaps = [...chaps].sort((a, b) => (asc ? a.number - b.number : b.number - a.number))

  // Group adjacent same-number chapters (duplicate chapters from different scanlators) → "CH. N" card.
  const groups: { number: number; items: typeof chaps }[] = []
  for (const c of chaps) {
    const last = groups[groups.length - 1]
    if (last && last.number === c.number && c.number > 0) last.items.push(c)
    else groups.push({ number: c.number, items: [c] })
  }
  const renderRow = (c: (typeof chaps)[number]) => {
    const read = readSet.has(c.url)
    const bm = state.bookmarks.includes(c.url)
    const meta = [c.scanlator, dateFmt(c.dateUpload)].filter(Boolean).join('  ·  ')
    return (
      <div key={c.url} className={'chapter-row' + (read ? ' read' : '')} onClick={() => openChapter(c.url, c.name)}>
        <div className="chapter-text">
          <div className="chapter-name">{c.name}</div>
          {meta && <div className="chapter-meta">{meta}</div>}
        </div>
        {c.downloaded && <IconDownload className="chapter-dl" />}
        {bm && <IconBookmarkSm filled className="chapter-bm" />}
        <button className={'ch-check' + (read ? ' on' : '')} onClick={(e) => toggleRead(e, c.url)} aria-label={read ? 'Mark unread' : 'Mark read'} />
      </div>
    )
  }

  return (
    <BackWrap nav={nav}>
      <div className="detail-head">
        <div className="detail-cover">
          {m.thumbnailUrl ? <img src={coverUrl(sourceId, m.thumbnailUrl)} alt="" /> : <div className="skeleton" style={{ width: '100%', height: '100%' }} />}
        </div>
        <h1 className="detail-title">{m.title}</h1>
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
        {year && <span className="pill"><IconCalendar className="pi" />{year}</span>}
        {m.author && <span className="pill"><IconPen className="pi" />{m.author}</span>}
      </div>
      {lastUpdate > 0 && <div className="last-update" onClick={scanUpdates}>Updated {relative(lastUpdate)} · tap “{status || 'status'}” to check</div>}

      {m.description && (
        <div className={'synopsis' + (descOpen ? '' : ' clamp')} onClick={() => setDescOpen((v) => !v)}>
          {m.description}
        </div>
      )}

      <div className="ch-toolbar">
        <div className="ch-tabs">
          {(['all', 'unread', 'read'] as Tab[]).map((t) => (
            <button key={t} className={'ch-tab' + (tab === t ? ' active' : '')} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>
          ))}
        </div>
        <button className="ch-sort" onClick={() => setAsc((v) => !v)} title={asc ? 'Oldest first' : 'Newest first'}>
          <IconSort className="pi" />{asc ? 'Asc' : 'Desc'}
        </button>
      </div>

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
