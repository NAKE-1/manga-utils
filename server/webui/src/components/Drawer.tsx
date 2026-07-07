import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'

// Slide-in navigation drawer opened by the top-bar hamburger. Holds the routes that aren't in the
// bottom tab bar (downloads, extensions, etc.) plus quick access to the primary ones.
const ITEMS: { label: string; to: string }[] = [
  { label: 'Home', to: '/' },
  { label: 'Library', to: '/library' },
  { label: 'Search', to: '/search' },
  { label: 'Downloads', to: '/downloads' },
  { label: 'Manage downloads', to: '/downloads/manage' },
  { label: 'Extensions & repos', to: '/extensions' },
  { label: 'Settings', to: '/settings' },
]

export function Drawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const nav = useNavigate()
  const [ver, setVer] = useState('')
  useEffect(() => {
    if (open && !ver) api.version().then((v) => setVer(`v${v.version} · ${v.commit}`)).catch(() => {})
  }, [open, ver])
  const go = (to: string) => { onClose(); nav(to) }
  return (
    <>
      <div className={'drawer-backdrop' + (open ? ' show' : '')} onClick={onClose} />
      <nav className={'drawer' + (open ? ' open' : '')} aria-hidden={!open}>
        <div className="drawer-head">MANGA-UTILS</div>
        <div className="drawer-items">
          {ITEMS.map((it) => (
            <button key={it.to} className="drawer-item" onClick={() => go(it.to)}>{it.label}</button>
          ))}
        </div>
        {ver && <div className="drawer-foot">{ver}</div>}
      </nav>
    </>
  )
}
