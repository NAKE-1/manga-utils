import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { IconDownload } from './icons'

// Warm the detail (server + browser cache) on hover/press so the tap opens instantly.
const prefetched = new Set<string>()
function prefetchDetail(sourceId: string, url: string) {
  const k = sourceId + '|' + url
  if (prefetched.has(k)) return
  prefetched.add(k)
  api.detail(sourceId, url).catch(() => prefetched.delete(k))
}

type Props = {
  sourceId: string
  url: string
  title: string
  cover: string
  subtitle?: string
  type?: 'manga' | 'manhwa' | 'manhua' | null
  badge?: number
  grid?: boolean
  onRemove?: () => void
  dl?: 'all' | 'some'
}

export function CoverCard({ sourceId, url, title, cover, subtitle, type, badge, grid, onRemove, dl }: Props) {
  const nav = useNavigate()
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  // Don't shimmer forever when a source's image host is down (e.g. atsu.moe): after ~9s of no load,
  // show a placeholder instead of an eternal skeleton, so the grid reads as "no cover" not "loading".
  useEffect(() => {
    if (loaded || failed || !cover) return
    const t = window.setTimeout(() => setFailed(true), 9000)
    return () => window.clearTimeout(t)
  }, [loaded, failed, cover])
  const go = () => nav(`/manga/${sourceId}?url=${encodeURIComponent(url)}`)
  return (
    <div
      className={'cover-card' + (grid ? ' full' : '')}
      onClick={go}
      // Prefetch only on hover-capable (mouse) devices: on touch, pointerenter fires at tap time so
      // it gives no head start — it just double-fetches the detail and slows the info page. Phone-first.
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') prefetchDetail(sourceId, url) }}
      onPointerDown={(e) => { if (e.pointerType === 'mouse') prefetchDetail(sourceId, url) }}
    >
      <div className="cover-frame">
        {!loaded && !failed && <div className="cover-skel skeleton" />}
        {failed && !loaded && <div className="cover-fail" aria-hidden>{title.slice(0, 1).toUpperCase()}</div>}
        {onRemove && <button className="cover-remove" aria-label="Remove" onClick={(e) => { e.stopPropagation(); onRemove() }}>✕</button>}
        {cover && (
          <img
            src={cover}
            alt=""
            loading="lazy"
            className={'cover-img' + (loaded ? ' loaded' : '')}
            onLoad={() => { setLoaded(true); setFailed(false) }}
            onError={(e) => { const i = e.currentTarget; if (!i.dataset.r) { i.dataset.r = '1'; i.src = cover + (cover.includes('?') ? '&' : '?') + 'r=1' } else setFailed(true) }}
          />
        )}
        {type && <span className={'type-badge ' + type}>{type}</span>}
        {!!badge && badge > 0 && <span className="badge-tl" title={`${badge} new chapter${badge === 1 ? '' : 's'}`}>!</span>}
        {dl && <span className={'dl-badge ' + dl} title={dl === 'all' ? 'All chapters downloaded' : 'Some chapters downloaded'}><IconDownload /></span>}
      </div>
      <div className="cover-title">{title}</div>
      {subtitle && <div className="cover-sub">{subtitle}</div>}
    </div>
  )
}
