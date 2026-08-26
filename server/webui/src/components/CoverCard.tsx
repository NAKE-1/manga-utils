import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { IconDownload } from './icons'
import noPoster from '../assets/no-poster.png'

// Warm the detail (server + browser cache) on hover/press so the tap opens instantly.
// Disabled: on a big grid a mouse sweep fired a live source fetch per card, hammering
// Cloudflare sources (JCEF/FlareSolverr). Flip to true to re-enable hover prefetch.
const PREFETCH_ON_HOVER = false
const prefetched = new Set<string>()
function prefetchDetail(sourceId: string, url: string) {
  if (!PREFETCH_ON_HOVER) return
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
  /** Offline + nothing downloaded → dim it and mark it unreadable-while-offline. */
  dimmed?: boolean
}

export function CoverCard({ sourceId, url, title, cover, subtitle, type, badge, grid, onRemove, dl, dimmed }: Props) {
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
      className={'cover-card' + (grid ? ' full' : '') + (dimmed ? ' dimmed' : '')}
      onClick={go}
      // Prefetch only on hover-capable (mouse) devices: on touch, pointerenter fires at tap time so
      // it gives no head start — it just double-fetches the detail and slows the info page. Phone-first.
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') prefetchDetail(sourceId, url) }}
      onPointerDown={(e) => { if (e.pointerType === 'mouse') prefetchDetail(sourceId, url) }}
    >
      <div className="cover-frame">
        {cover && !loaded && !failed && <div className="cover-skel skeleton" />}
        {(failed || !cover) && !loaded && <img className="cover-fail-img" src={noPoster} alt="" aria-hidden />}
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
        {dimmed && <span className="offline-tag" title="Not downloaded — unavailable while offline">Offline</span>}
      </div>
      <div className="cover-title">{title}</div>
      {subtitle && <div className="cover-sub">{subtitle}</div>}
    </div>
  )
}
