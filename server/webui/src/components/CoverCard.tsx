import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

type Props = {
  sourceId: string
  url: string
  title: string
  cover: string
  subtitle?: string
  type?: 'manga' | 'manhwa' | 'manhua' | null
  badge?: number
  grid?: boolean
}

export function CoverCard({ sourceId, url, title, cover, subtitle, type, badge, grid }: Props) {
  const nav = useNavigate()
  const [loaded, setLoaded] = useState(false)
  const go = () => nav(`/manga/${sourceId}?url=${encodeURIComponent(url)}`)
  return (
    <div className={'cover-card' + (grid ? ' full' : '')} onClick={go}>
      <div className="cover-frame">
        {!loaded && <div className="cover-skel skeleton" />}
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
