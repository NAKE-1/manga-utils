import { useEffect, useMemo, useRef, useState } from 'react'
import { api, coverUrl, mediaType, Source, Manga } from '../api'
import { CoverCard } from '../components/CoverCard'
import { SkeletonGrid } from '../components/Skeleton'
import { ErrorPanel } from '../components/ErrorPanel'
import { SourcePicker } from '../components/SourcePicker'
import { SourcePrefsSheet } from '../components/SourcePrefsSheet'
import { IconSearch, IconSettings } from '../components/icons'

type Mode = 'popular' | 'latest' | 'search'
const GLOBAL = '__global__'

const RECENT_KEY = 'recent.searches'
const recents = (): string[] => { try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]') } catch { return [] } }
function pushRecent(q: string) {
  localStorage.setItem(RECENT_KEY, JSON.stringify([q, ...recents().filter((x) => x !== q)].slice(0, 8)))
}

type GlobalRow = { src: Source; items: Manga[]; error: string | null; loading: boolean }

// Snapshot of the Search screen, kept across navigation so tapping into a manga and hitting Back
// returns to the same query + results + scroll instead of a blank Popular view. Module-scoped =
// survives unmount within the SPA session.
type SearchCache = {
  sourceId: string; mode: Mode; input: string; query: string
  items: Manga[]; page: number; hasNext: boolean; globalRows: GlobalRow[]; scrollTop: number
}
let searchCache: SearchCache | null = null

export function Search() {
  const [sources, setSources] = useState<Source[]>([])
  const [configuring, setConfiguring] = useState(false)
  const [sourceId, setSourceId] = useState<string>(searchCache?.sourceId || localStorage.getItem('browse.source') || '')
  const [mode, setMode] = useState<Mode>(searchCache?.mode ?? 'popular')
  const [input, setInput] = useState(searchCache?.input ?? '')
  const [query, setQuery] = useState(searchCache?.query ?? '')
  const [items, setItems] = useState<Manga[]>(searchCache?.items ?? [])
  const [page, setPage] = useState(searchCache?.page ?? 1)
  const [hasNext, setHasNext] = useState(searchCache?.hasNext ?? false)
  const [loading, setLoading] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [recent, setRecent] = useState<string[]>(recents())
  const [globalRows, setGlobalRows] = useState<GlobalRow[]>(searchCache?.globalRows ?? [])
  const sentinel = useRef<HTMLDivElement>(null)
  const busy = useRef(false)
  const cfTimer = useRef<number | undefined>(undefined)
  const didHydrate = useRef(!!searchCache) // skip the initial auto-fetch when restoring cached results

  // A source that just revealed Cloudflare gets re-coloured in the picker (debounced refetch).
  function refreshSourcesSoon() {
    if (cfTimer.current) clearTimeout(cfTimer.current)
    cfTimer.current = window.setTimeout(() => { api.sources().then(setSources).catch(() => {}) }, 1500)
  }

  const isGlobal = sourceId === GLOBAL
  const pickerSources = useMemo(() => [{ id: GLOBAL, name: 'Global', lang: '', nsfw: false, cfState: 'green', down: false } as Source, ...sources], [sources])
  // Stable signature of the source *set* — so refreshing cloud colors (which replaces the sources
  // array with the same ids) doesn't re-trigger the global search in a loop.
  const sourceKey = useMemo(() => sources.map((s) => s.id).join(','), [sources])

  useEffect(() => {
    api.sources().then((s) => {
      setSources(s)
      setSourceId((cur) => (cur && (cur === GLOBAL || s.some((x) => x.id === cur))) || !s.length ? cur : s[0].id)
    }).catch(() => {})
  }, [])
  useEffect(() => { if (sourceId) localStorage.setItem('browse.source', sourceId) }, [sourceId])
  // Remember the last-used source immediately on pick, so reopening Search restores it.
  function pickSource(id: string) { localStorage.setItem('browse.source', id); setSourceId(id) }

  // Keep a live snapshot; save it (with scroll) on unmount and restore scroll on mount.
  const snapRef = useRef<SearchCache | null>(null)
  snapRef.current = { sourceId, mode, input, query, items, page, hasNext, globalRows, scrollTop: 0 }
  useEffect(() => {
    const scroller = document.querySelector('main')
    if (searchCache && scroller) { const y = searchCache.scrollTop; requestAnimationFrame(() => scroller.scrollTo(0, y)) }
    return () => { if (snapRef.current) { snapRef.current.scrollTop = scroller?.scrollTop ?? 0; searchCache = snapRef.current } }
  }, [])

  // ---- Single-source browse/search ----
  function fetchPage(p: number) {
    if (!sourceId || isGlobal || busy.current) return
    busy.current = true; setLoading(true); setErrorMsg(null)
    const req = mode === 'search' ? api.search(sourceId, query, p) : mode === 'latest' ? api.latest(sourceId, p) : api.popular(sourceId, p)
    req.then((r) => {
      setItems((prev) => (p === 1 ? r.mangas : [...prev, ...r.mangas]))
      setHasNext(r.hasNextPage); setPage(p)
    }).catch((e) => {
      if (p === 1) setErrorMsg(e instanceof Error ? e.message : "Couldn't load results.")
    }).finally(() => { busy.current = false; setLoading(false); refreshSourcesSoon() })
  }

  useEffect(() => {
    if (!sourceId || isGlobal) return
    if (didHydrate.current) { didHydrate.current = false; return } // restored from cache — keep results
    setItems([]); setHasNext(false); setPage(1); fetchPage(1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceId, mode, query])

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
    if (didHydrate.current) { didHydrate.current = false; return } // restored — keep cached global rows
    const q = query.trim()
    if (!q || sources.length === 0) { setGlobalRows([]); return }
    setGlobalRows(sources.map((s) => ({ src: s, items: [], error: null, loading: true })))
    sources.forEach((s) => {
      api.search(s.id, q, 1)
        .then((r) => setGlobalRows((prev) => prev.map((g) => (g.src.id === s.id ? { ...g, items: r.mangas, loading: false } : g))))
        .catch((e) => {
          const msg = e instanceof Error ? e.message : 'Unavailable'
          setGlobalRows((prev) => prev.map((g) => (g.src.id === s.id ? { ...g, error: msg, loading: false } : g)))
        })
        .finally(refreshSourcesSoon)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the source SET, not its array identity
  }, [isGlobal, query, sourceKey])

  function submit(q: string) {
    const t = q.trim()
    if (!t) { if (!isGlobal) setMode('popular'); setQuery(''); return }
    pushRecent(t); setRecent(recents()); setInput(t); if (!isGlobal) setMode('search'); setQuery(t)
  }

  return (
    <>
      <div className="search-head">
        <div className="search-bar-wrap">
          <div className="search-bar">
            <IconSearch className="sb-ic" />
            <input value={input} onChange={(e) => setInput(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { submit(input); e.currentTarget.blur() } }} placeholder={isGlobal ? 'Search all sources…' : 'Search manga…'} inputMode="search" enterKeyHint="search" />
            {input && <button className="sb-clear" onClick={() => { setInput(''); submit('') }} aria-label="Clear">✕</button>}
          </div>
        </div>

        <div className="src-picker-wrap">
          <SourcePicker sources={pickerSources} value={sourceId} onChange={pickSource} />
          {!isGlobal && sourceId && (
            <button className="src-config" onClick={() => setConfiguring(true)} aria-label="Source settings" title="Source settings"><IconSettings /></button>
          )}
        </div>
      </div>
      {configuring && !isGlobal && (
        <SourcePrefsSheet sourceId={sourceId} sourceName={pickerSources.find((s) => s.id === sourceId)?.name || 'Source'} onClose={() => setConfiguring(false)} />
      )}

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
