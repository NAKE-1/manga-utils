import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'

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
}

export function CoverCard({ sourceId, url, title, cover, subtitle, type, badge, grid, onRemove }: Props) {
  const nav = useNavigate()
  const [loaded, setLoaded] = useState(false)
  const go = () => nav(`/manga/${sourceId}?url=${encodeURIComponent(url)}`)
  return (
    <div className={'cover-card' + (grid ? ' full' : '')} onClick={go} onPointerEnter={() => prefetchDetail(sourceId, url)} onPointerDown={() => prefetchDetail(sourceId, url)}>
      <div className="cover-frame">
        {!loaded && <div className="cover-skel skeleton" />}
        {onRemove && <button className="cover-remove" aria-label="Remove" onClick={(e) => { e.stopPropagation(); onRemove() }}>✕</button>}
        {cover && (
          <img
            src={cover}
            alt=""
            loading="lazy"
            className={'cover-img' + (loaded ? ' loaded' : '')}
            onLoad={() => setLoaded(true)}
            onError={(e) => { const i = e.currentTarget; if (!i.dataset.r) { i.dataset.r = '1'; i.src = cover + (cover.includes('?') ? '&' : '?') + 'r=1' } }}
          />
        )}
        {type && <span className={'type-badge ' + type}>{type}</span>}
        {!!badge && badge > 0 && <span className="badge-tl">{badge}</span>}
      </div>
      <div className="cover-title">{title}</div>
      {subtitle && <div className="cover-sub">{subtitle}</div>}
    </div>
  )
}
