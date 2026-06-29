import { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { IconChevronRight } from './icons'

export function Carousel({ title, to, children }: { title: string; to?: string; children: ReactNode }) {
  const nav = useNavigate()
  return (
    <section className="section">
      <div className={'section-head' + (to ? ' tappable' : '')} onClick={to ? () => nav(to) : undefined}>
        <span>{title}</span>
        {to && <IconChevronRight className="chev" />}
      </div>
      <div className="carousel">{children}</div>
    </section>
  )
}

export function GridSection({ title, to, children }: { title: string; to?: string; children: ReactNode }) {
  const nav = useNavigate()
  return (
    <section className="section">
      <div className={'section-head' + (to ? ' tappable' : '')} onClick={to ? () => nav(to) : undefined}>
        <span>{title}</span>
        {to && <IconChevronRight className="chev" />}
      </div>
      <div className="grid">{children}</div>
    </section>
  )
}
