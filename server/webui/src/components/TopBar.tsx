import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { IconSearch, IconDownload, IconMenu } from './icons'
import { Drawer } from './Drawer'
import { api } from '../api'

export function TopBar() {
  const nav = useNavigate()
  const [active, setActive] = useState(0)
  const [menuOpen, setMenuOpen] = useState(false)

  // Poll the queue so the download icon glows accent while anything is downloading.
  useEffect(() => {
    let alive = true
    const tick = () => api.downloads().then((d) => { if (alive) setActive(d.active) }).catch(() => {})
    tick()
    const t = setInterval(tick, 3000)
    return () => { alive = false; clearInterval(t) }
  }, [])

  return (
    <header className="topbar">
      <span className="wordmark" onClick={() => nav('/')}>MANGA-UTILS</span>
      <button className={'iconbtn' + (active > 0 ? ' dl-on' : '')} aria-label={active > 0 ? `Downloads (${active} active)` : 'Downloads'} onClick={() => nav('/downloads')}><IconDownload /></button>
      <button className="iconbtn" aria-label="Search" onClick={() => nav('/search')}><IconSearch /></button>
      <button className="iconbtn" aria-label="Menu" onClick={() => setMenuOpen(true)}><IconMenu /></button>
      <Drawer open={menuOpen} onClose={() => setMenuOpen(false)} />
    </header>
  )
}
