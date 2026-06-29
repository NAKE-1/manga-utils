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
  const go = () => nav(`/manga/${sourceId}/${encodeURIComponent(url)}`)
  return (
    <div className={'cover-card' + (grid ? ' full' : '')} onClick={go}>
      <div className="cover-frame">
        {cover ? <img src={cover} alt="" loading="lazy" /> : <div className="skeleton" style={{ width: '100%', height: '100%' }} />}
        {type && <span className={'type-badge ' + type}>{type}</span>}
        {!!badge && badge > 0 && <span className="badge-tl">{badge}</span>}
      </div>
      <div className="cover-title">{title}</div>
      {subtitle && <div className="cover-sub">{subtitle}</div>}
    </div>
  )
}
