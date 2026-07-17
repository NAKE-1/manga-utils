import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, coverUrl, type Source, type Manga, type MigratePreview, type MigrateProgress, type MigrateSide } from '../api'
import { IconArrowLeft } from '../components/icons'

export default function Migrate() {
  const nav = useNavigate()
  const [sp] = useSearchParams()
  const fromSource = sp.get('source') || ''
  const fromUrl = sp.get('url') || ''
  const fromTitle = sp.get('title') || 'this series'

  const [sources, setSources] = useState<Source[]>([])
  const [toSource, setToSource] = useState('')
  const [q, setQ] = useState(fromTitle)
  const [results, setResults] = useState<Manga[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [urlInput, setUrlInput] = useState('')
  const [target, setTarget] = useState<{ source: string; url: string } | null>(null)
  const [preview, setPreview] = useState<MigratePreview | null>(null)
  const [loadingPrev, setLoadingPrev] = useState(false)
  const [delDl, setDelDl] = useState(false)
  const [redl, setRedl] = useState(false)
  const [prog, setProg] = useState<MigrateProgress | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.sources().then((s) => { const o = s.filter((x) => x.id !== fromSource); setSources(o); if (o[0]) setToSource(o[0].id) }).catch(() => {})
  }, [fromSource])

  async function search() {
    if (!toSource || !q.trim()) return
    setSearching(true); setResults(null); setErr('')
    const r = await api.search(toSource, q.trim()).catch((e) => { setErr(e instanceof Error ? e.message : 'search failed'); return null })
    setSearching(false); setResults(r?.mangas ?? [])
  }
  async function loadPreview(ts: string, tu: string) {
    setErr(''); setTarget({ source: ts, url: tu }); setLoadingPrev(true); setPreview(null)
    const p = await api.migratePreview(fromSource, fromUrl, ts, tu).catch((e) => { setErr(e instanceof Error ? e.message : 'Preview failed'); return null })
    setLoadingPrev(false); setPreview(p)
  }
  async function resolveUrl() {
    if (!urlInput.trim()) return
    setErr('')
    const r = await api.resolve(urlInput.trim()).catch((e) => { setErr(e instanceof Error ? e.message : "Couldn't resolve that URL"); return null })
    if (r) loadPreview(r.sourceId, r.mangaUrl)
  }
  async function start() {
    if (!target) return
    const p = await api.migrateStart({ fromSource, fromUrl, toSource: target.source, toUrl: target.url, deleteOldDownloads: delDl, reDownload: delDl && redl }).catch(() => null)
    setProg(p)
    const poll = setInterval(async () => {
      const s = await api.migrateProgress().catch(() => null)
      if (s) setProg(s)
      if (s && !s.running && s.finished) clearInterval(poll)
    }, 500)
  }

  return (
    <div className="mig">
      <div className="mig-border" aria-hidden />
      <div className="mig-badge">MIGRATION</div>

      <div className="mig-inner">
        <div className="mig-head">
          <button className="iconbtn" onClick={() => nav(-1)} aria-label="Cancel"><IconArrowLeft /></button>
          <span className="mig-title">Migrate “{fromTitle}”</span>
        </div>

        {/* ---- Stage 3: running / done ---- */}
        {prog ? (
          <div className="mig-status">
            <div className="mig-status-head">{prog.error ? 'Migration failed' : prog.finished ? 'Migration complete' : 'Migrating…'}</div>
            <div className="mig-log">
              {prog.steps.map((s, i) => <div key={i} className={'mig-log-line' + (s.startsWith('✕') ? ' err' : s.startsWith('✓') || s.includes('complete') ? ' ok' : '')}>{s}</div>)}
              {prog.running && <div className="mig-log-line running">…</div>}
            </div>
            {prog.finished && !prog.error && target && (
              <button className="btn primary block" onClick={() => nav(`/manga/${target.source}?url=${encodeURIComponent(target.url)}`)}>Open on new source</button>
            )}
            {prog.finished && prog.error && <button className="btn block" onClick={() => { setProg(null) }}>Back</button>}
          </div>
        ) : preview ? (
          /* ---- Stage 2: comparison ---- */
          <div className="mig-compare-wrap">
            <div className="mig-compare">
              <Side label="FROM" side={preview.from} tone="old" />
              <div className="mig-arrow">→</div>
              <Side label="TO" side={preview.to} tone="new" />
            </div>
            <div className="mig-diffs">
              <Diff label="Chapters" v={diffStr(preview.chapterDiff)} tone={preview.chapterDiff < 0 ? 'warn' : 'good'} />
              <Diff label="Read carried" v={`${preview.willCarryRead}${preview.unmatchedRead ? ` (${preview.unmatchedRead} unmatched)` : ''}`} tone={preview.unmatchedRead ? 'warn' : 'good'} />
              <Diff label="Bookmarks carried" v={`${preview.willCarryBookmarks}${preview.unmatchedBookmarks ? ` (${preview.unmatchedBookmarks} unmatched)` : ''}`} tone={preview.unmatchedBookmarks ? 'warn' : 'good'} />
              {preview.unnumbered > 0 && <Diff label="Unnumbered (skipped)" v={String(preview.unnumbered)} tone="warn" />}
            </div>

            <div className="mig-options">
              <label className="mig-opt">
                <input type="checkbox" checked={delDl} onChange={(e) => setDelDl(e.target.checked)} />
                <span><b>Delete the old source's downloads</b><br /><span className="mig-opt-sub">{delDl ? 'Removed from disk.' : `Keep them (${preview.from.downloaded} chapter(s)) — ⚠ they were downloaded from the OLD source.`}</span></span>
              </label>
              {delDl && (
                <label className="mig-opt sub">
                  <input type="checkbox" checked={redl} onChange={(e) => setRedl(e.target.checked)} />
                  <span>Re-download them from the new source</span>
                </label>
              )}
            </div>

            <div className="mig-actions">
              <button className="btn" onClick={() => { setPreview(null); setTarget(null) }}>Change target</button>
              <button className="btn danger" onClick={start}>Migrate</button>
            </div>
          </div>
        ) : (
          /* ---- Stage 1: pick target ---- */
          <div className="mig-pick">
            <div className="mig-sec">
              <div className="mig-sec-h">Search a source</div>
              <div className="mig-searchrow">
                <select className="set-select" value={toSource} onChange={(e) => setToSource(e.target.value)}>
                  {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </div>
              <div className="mig-searchrow">
                <input className="set-input" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && search()} placeholder="Title…" />
                <button className="btn primary" disabled={searching} onClick={search}>{searching ? '…' : 'Search'}</button>
              </div>
              {results && (results.length === 0 ? <div className="mig-empty">No results.</div> : (
                <div className="mig-results">
                  {results.map((m) => (
                    <button key={m.url} className="mig-result" onClick={() => loadPreview(toSource, m.url)}>
                      <img className="mig-result-cover" src={coverUrl(toSource, m.thumbnailUrl, m.title)} alt="" loading="lazy" />
                      <span className="mig-result-title">{m.title}</span>
                    </button>
                  ))}
                </div>
              ))}
            </div>
            <div className="mig-sec">
              <div className="mig-sec-h">…or paste a URL</div>
              <div className="mig-searchrow">
                <input className="set-input" value={urlInput} onChange={(e) => setUrlInput(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && resolveUrl()} placeholder="https://…/manga/…" spellCheck={false} />
                <button className="btn primary" onClick={resolveUrl}>Go</button>
              </div>
            </div>
            {loadingPrev && <div className="spinner" />}
          </div>
        )}

        {err && <div className="mig-err">{err}</div>}
      </div>
    </div>
  )
}

function Side({ label, side, tone }: { label: string; side: MigrateSide; tone: 'old' | 'new' }) {
  return (
    <div className={'mig-side ' + tone}>
      <div className="mig-side-lbl">{label}</div>
      <div className="mig-side-src">{side.sourceName}</div>
      <div className="mig-side-title">{side.title}</div>
      <div className="mig-side-rows">
        <div><span>Chapters</span><b>{side.total}</b></div>
        <div><span>Read</span><b>{side.readCount}{side.readUpTo !== '—' ? ` · to ${side.readUpTo}` : ''}</b></div>
        <div><span>Bookmarks</span><b>{side.bookmarks}</b></div>
        <div><span>Downloaded</span><b>{side.downloaded}</b></div>
      </div>
    </div>
  )
}
function Diff({ label, v, tone }: { label: string; v: string; tone: 'good' | 'warn' }) {
  return <div className={'mig-diff ' + tone}><span>{label}</span><b>{v}</b></div>
}
function diffStr(n: number) { return n > 0 ? `+${n}` : String(n) }
