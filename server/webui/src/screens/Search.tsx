import { useEffect, useRef, useState } from 'react'
import { api, coverUrl, mediaType, Source, Manga } from '../api'
import { CoverCard } from '../components/CoverCard'
import { SkeletonGrid } from '../components/Skeleton'
import { ErrorPanel } from '../components/ErrorPanel'
import { IconSearch } from '../components/icons'

type Mode = 'popular' | 'latest' | 'search'

const RECENT_KEY = 'recent.searches'
const recents = (): string[] => { try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]') } catch { return [] } }
function pushRecent(q: string) {
  localStorage.setItem(RECENT_KEY, JSON.stringify([q, ...recents().filter((x) => x !== q)].slice(0, 8)))
}

export function Search() {
  const [sources, setSources] = useState<Source[]>([])
  const [sourceId, setSourceId] = useState<string>(localStorage.getItem('browse.source') || '')
  const [mode, setMode] = useState<Mode>('popular')
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<Manga[]>([])
  const [page, setPage] = useState(1)
  const [hasNext, setHasNext] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [recent, setRecent] = useState<string[]>(recents())
  const sentinel = useRef<HTMLDivElement>(null)
  const busy = useRef(false)

  useEffect(() => {
    api.sources().then((s) => {
      setSources(s)
      setSourceId((cur) => (cur && s.some((x) => x.id === cur)) || !s.length ? cur : s[0].id)
    }).catch(() => {})
  }, [])
  useEffect(() => { if (sourceId) localStorage.setItem('browse.source', sourceId) }, [sourceId])

  function fetchPage(p: number) {
    if (!sourceId || busy.current) return
    busy.current = true; setLoading(true); setError(false)
    const req = mode === 'search' ? api.search(sourceId, query, p) : mode === 'latest' ? api.latest(sourceId, p) : api.popular(sourceId, p)
    req.then((r) => {
      setItems((prev) => (p === 1 ? r.mangas : [...prev, ...r.mangas]))
      setHasNext(r.hasNextPage); setPage(p)
    }).catch(() => { if (p === 1) setError(true) }).finally(() => { busy.current = false; setLoading(false) })
  }

  // Reset + load when the source / mode / query changes.
  useEffect(() => { if (sourceId) { setItems([]); setHasNext(false); setPage(1); fetchPage(1) } }, [sourceId, mode, query])

  // Infinite scroll.
  useEffect(() => {
    const el = sentinel.current
    if (!el) return
    const io = new IntersectionObserver((e) => { if (e[0].isIntersecting && hasNext && !busy.current) fetchPage(page + 1) }, { rootMargin: '700px' })
    io.observe(el)
    return () => io.disconnect()
  }, [hasNext, page, sourceId, mode, query])

  function submit(q: string) {
    const t = q.trim()
    if (!t) { setMode('popular'); setQuery(''); return }
    pushRecent(t); setRecent(recents()); setInput(t); setMode('search'); setQuery(t)
  }

  return (
    <>
      <div className="search-bar-wrap">
        <div className="search-bar">
          <IconSearch className="sb-ic" />
          <input value={input} onChange={(e) => setInput(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') submit(input) }} placeholder="Search manga…" inputMode="search" />
          {input && <button className="sb-clear" onClick={() => { setInput(''); submit('') }} aria-label="Clear">✕</button>}
        </div>
      </div>

      {sources.length > 1 && (
        <div className="chip-row src-chips">
          {sources.map((s) => <span key={s.id} className={'chip' + (s.id === sourceId ? ' on' : '')} onClick={() => setSourceId(s.id)}>{s.name}</span>)}
        </div>
      )}

      {mode !== 'search' && (
        <div className="seg" style={{ margin: '8px 16px 0' }}>
          <button className={'seg-btn' + (mode === 'popular' ? ' on' : '')} onClick={() => setMode('popular')}>Popular</button>
          <button className={'seg-btn' + (mode === 'latest' ? ' on' : '')} onClick={() => setMode('latest')}>Latest</button>
        </div>
      )}

      {mode !== 'search' && recent.length > 0 && (
        <div className="recents">
          <div className="recents-h">Recent searches</div>
          {recent.map((q) => <span key={q} className="chip" onClick={() => submit(q)}>{q}</span>)}
        </div>
      )}

      {error ? <ErrorPanel onRetry={() => fetchPage(1)} />
        : items.length === 0 && loading ? <SkeletonGrid />
        : items.length === 0 ? <div className="center-msg">{mode === 'search' ? 'No results.' : 'Nothing here.'}</div>
        : (
          <>
            <div className="grid" style={{ paddingTop: 12 }}>
              {items.map((m, i) => (
                <CoverCard key={m.sourceId + m.url + i} grid sourceId={m.sourceId} url={m.url} title={m.title} cover={coverUrl(m.sourceId, m.thumbnailUrl, m.title)} type={mediaType(m.genre)} />
              ))}
            </div>
            <div ref={sentinel} style={{ height: 1 }} />
            {loading && <div className="spinner" />}
          </>
        )}
      <div style={{ height: 16 }} />
    </>
  )
}
