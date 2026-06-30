import { useEffect, useMemo, useRef, useState } from 'react'
import { api, coverUrl, mediaType, Source, Manga } from '../api'
import { CoverCard } from '../components/CoverCard'
import { SkeletonGrid } from '../components/Skeleton'
import { ErrorPanel } from '../components/ErrorPanel'
import { SourcePicker } from '../components/SourcePicker'
import { IconSearch } from '../components/icons'

type Mode = 'popular' | 'latest' | 'search'
const GLOBAL = '__global__'

const RECENT_KEY = 'recent.searches'
const recents = (): string[] => { try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]') } catch { return [] } }
function pushRecent(q: string) {
  localStorage.setItem(RECENT_KEY, JSON.stringify([q, ...recents().filter((x) => x !== q)].slice(0, 8)))
}

type GlobalRow = { src: Source; items: Manga[]; error: string | null; loading: boolean }

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
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [recent, setRecent] = useState<string[]>(recents())
  const [globalRows, setGlobalRows] = useState<GlobalRow[]>([])
  const sentinel = useRef<HTMLDivElement>(null)
  const busy = useRef(false)

  const isGlobal = sourceId === GLOBAL
  const pickerSources = useMemo(() => [{ id: GLOBAL, name: 'Global', lang: '', nsfw: false } as Source, ...sources], [sources])

  useEffect(() => {
    api.sources().then((s) => {
      setSources(s)
      setSourceId((cur) => (cur && (cur === GLOBAL || s.some((x) => x.id === cur))) || !s.length ? cur : s[0].id)
    }).catch(() => {})
  }, [])
  useEffect(() => { if (sourceId) localStorage.setItem('browse.source', sourceId) }, [sourceId])

  // ---- Single-source browse/search ----
  function fetchPage(p: number) {
    if (!sourceId || isGlobal || busy.current) return
    busy.current = true; setLoading(true); setErrorMsg(null)
    const req = mode === 'search' ? api.search(sourceId, query, p) : mode === 'latest' ? api.latest(sourceId, p) : api.popular(sourceId, p)
    req.then((r) => {
      setItems((prev) => (p === 1 ? r.mangas : [...prev, ...r.mangas]))
      setHasNext(r.hasNextPage); setPage(p)
    }).catch((e) => { if (p === 1) setErrorMsg(e instanceof Error ? e.message : "Couldn't load results.") }).finally(() => { busy.current = false; setLoading(false) })
  }

  useEffect(() => { if (sourceId && !isGlobal) { setItems([]); setHasNext(false); setPage(1); fetchPage(1) } }, [sourceId, mode, query])

  useEffect(() => {
    const el = sentinel.current
    if (!el || isGlobal) return
    const io = new IntersectionObserver((e) => { if (e[0].isIntersecting && hasNext && !busy.current) fetchPage(page + 1) }, { rootMargin: '700px' })
    io.observe(el)
    return () => io.disconnect()
  }, [hasNext, page, sourceId, mode, query, isGlobal])

  // ---- Global search: fan out to every source, one section each ----
  useEffect(() => {
    if (!isGlobal) return
    const q = query.trim()
    if (!q || sources.length === 0) { setGlobalRows([]); return }
    setGlobalRows(sources.map((s) => ({ src: s, items: [], error: null, loading: true })))
    sources.forEach((s) => {
      api.search(s.id, q, 1)
        .then((r) => setGlobalRows((prev) => prev.map((g) => (g.src.id === s.id ? { ...g, items: r.mangas, loading: false } : g))))
        .catch((e) => setGlobalRows((prev) => prev.map((g) => (g.src.id === s.id ? { ...g, error: e instanceof Error ? e.message : 'Unavailable', loading: false } : g))))
    })
  }, [isGlobal, query, sources])

  function submit(q: string) {
    const t = q.trim()
    if (!t) { if (!isGlobal) setMode('popular'); setQuery(''); return }
    pushRecent(t); setRecent(recents()); setInput(t); if (!isGlobal) setMode('search'); setQuery(t)
  }

  return (
    <>
      <div className="search-bar-wrap">
        <div className="search-bar">
          <IconSearch className="sb-ic" />
          <input value={input} onChange={(e) => setInput(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') submit(input) }} placeholder={isGlobal ? 'Search all sources…' : 'Search manga…'} inputMode="search" />
          {input && <button className="sb-clear" onClick={() => { setInput(''); submit('') }} aria-label="Clear">✕</button>}
        </div>
      </div>

      <div className="src-picker-wrap">
        <SourcePicker sources={pickerSources} value={sourceId} onChange={setSourceId} />
      </div>

      {!isGlobal && mode !== 'search' && (
        <div className="seg" style={{ margin: '8px 16px 0' }}>
          <button className={'seg-btn' + (mode === 'popular' ? ' on' : '')} onClick={() => setMode('popular')}>Popular</button>
          <button className={'seg-btn' + (mode === 'latest' ? ' on' : '')} onClick={() => setMode('latest')}>Latest</button>
        </div>
      )}

      {!isGlobal && mode !== 'search' && recent.length > 0 && (
        <div className="recents">
          <div className="recents-h">Recent searches</div>
          {recent.map((q) => <span key={q} className="chip" onClick={() => submit(q)}>{q}</span>)}
        </div>
      )}

      {isGlobal ? (
        !query.trim() ? <div className="center-msg">Type to search across all sources.</div>
          : (
            <div className="gs">
              {globalRows.map((g) => (
                <div className="gs-section" key={g.src.id}>
                  <div className="gs-head">
                    <span className="gs-name">{g.src.name}</span>
                    {g.src.nsfw && <span className="src-18">18+</span>}
                    {!g.loading && !g.error && <span className="gs-count">{g.items.length}</span>}
                  </div>
                  {g.loading ? <div className="gs-empty">Searching…</div>
                    : g.error ? <div className="gs-empty">{g.error}</div>
                    : g.items.length === 0 ? <div className="gs-empty">No results</div>
                    : (
                      <div className="row-scroll">
                        {g.items.slice(0, 18).map((m, i) => (
                          <CoverCard key={m.url + i} sourceId={m.sourceId} url={m.url} title={m.title} cover={coverUrl(m.sourceId, m.thumbnailUrl, m.title)} type={mediaType(m.genre)} />
                        ))}
                      </div>
                    )}
                </div>
              ))}
            </div>
          )
      ) : errorMsg ? <ErrorPanel onRetry={() => fetchPage(1)} message={errorMsg} />
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
