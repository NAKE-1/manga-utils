import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, coverUrl, mediaType, STATUS_LABELS, Detail as DetailT, MangaState } from '../api'
import { IconArrowLeft, IconHeart, IconBookmarkSm, IconClock, IconBook, IconPen } from '../components/icons'

const dateFmt = (ms: number) => (ms > 0 ? new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit' }) : '')

export function Detail() {
  const { sourceId = '' } = useParams()
  const [sp] = useSearchParams()
  const url = sp.get('url') || ''
  const nav = useNavigate()

  const [data, setData] = useState<DetailT | null>(null)
  const [state, setState] = useState<MangaState>({ inLibrary: false, read: [], bookmarks: [] })
  const [error, setError] = useState(false)
  const [descOpen, setDescOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setData(null); setError(false)
    api.detail(sourceId, url).then(setData).catch(() => setError(true))
    api.mangaState(sourceId, url).then(setState).catch(() => {})
  }, [sourceId, url])

  const chapterTotal = useMemo(() => {
    if (!data) return 0
    const max = Math.max(0, ...data.chapters.map((c) => c.number).filter((n) => n > 0))
    return max > 0 ? max : data.chapters.length
  }, [data])

  async function toggleLibrary() {
    if (busy) return
    setBusy(true)
    const next = !state.inLibrary
    try {
      if (next) await api.addLibrary(sourceId, url)
      else await api.removeLibrary(sourceId, url)
      setState((s) => ({ ...s, inLibrary: next }))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <BackWrap nav={nav}><div className="center-msg">Couldn't load this manga.</div></BackWrap>
  if (!data) return <BackWrap nav={nav}><div className="spinner" /></BackWrap>

  const m = data.manga
  const type = mediaType(m.genre)
  const genres = (m.genre || '').split(',').map((g) => g.trim()).filter(Boolean)
  const readSet = new Set(state.read)
  const bmSet = new Set(state.bookmarks)
  const status = STATUS_LABELS[m.status] || ''

  return (
    <BackWrap nav={nav}>
      <div className="detail-head">
        <div className="detail-cover">
          {m.thumbnailUrl ? <img src={coverUrl(sourceId, m.thumbnailUrl)} alt="" /> : <div className="skeleton" style={{ width: '100%', height: '100%' }} />}
        </div>
        <h1 className="detail-title">{m.title}</h1>
      </div>

      {genres.length > 0 && (
        <div className="chips scroll">
          {genres.map((g) => <span className="chip" key={g}>{g}</span>)}
        </div>
      )}

      <div className="pills">
        {type && <span className={'pill ' + type}>{type[0].toUpperCase() + type.slice(1)}</span>}
        {status && <span className="pill accent"><IconClock className="pi" />{status}</span>}
        <span className="pill"><IconBook className="pi" />{chapterTotal}</span>
        {m.author && <span className="pill"><IconPen className="pi" />{m.author}</span>}
      </div>

      <div className="actions">
        <button className={'btn' + (state.inLibrary ? ' primary' : '')} onClick={toggleLibrary} disabled={busy}>
          <IconHeart filled={state.inLibrary} className="ico" />
          {state.inLibrary ? 'In library' : 'Add to library'}
        </button>
      </div>

      {m.description && (
        <div className={'synopsis' + (descOpen ? '' : ' clamp')} onClick={() => setDescOpen((v) => !v)}>
          {m.description}
        </div>
      )}

      <div className="section-head" style={{ paddingTop: 14 }}>{chapterTotal} chapters</div>
      <div className="chapters">
        {data.chapters.map((c) => {
          const read = readSet.has(c.url)
          const meta = [c.scanlator, dateFmt(c.dateUpload)].filter(Boolean).join('  ·  ')
          return (
            <div
              key={c.url}
              className={'chapter-row' + (read ? ' read' : '')}
              onClick={() => nav(`/reader/${sourceId}?manga=${encodeURIComponent(url)}&chapter=${encodeURIComponent(c.url)}&name=${encodeURIComponent(c.name)}&title=${encodeURIComponent(m.title)}`)}
            >
              <div className="chapter-text">
                <div className="chapter-name">{c.name}</div>
                {meta && <div className="chapter-meta">{meta}</div>}
              </div>
              {bmSet.has(c.url) && <IconBookmarkSm filled className="chapter-bm" />}
            </div>
          )
        })}
      </div>
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
