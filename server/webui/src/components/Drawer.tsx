import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { api, Mullvad } from '../api'

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
  const [mv, setMv] = useState<Mullvad | null>(null)
  const [mvErr, setMvErr] = useState(false)
  const [mvOpen, setMvOpen] = useState(false)
  useEffect(() => {
    if (open && !ver) api.version().then((v) => setVer(`v${v.version} · ${v.commit}`)).catch(() => {})
  }, [open, ver])
  // Mullvad status (server egress) — re-fetch each open so it reflects a VPN drop/reconnect.
  // Returns egress data (IP/country/city/org) whether or not Mullvad is on, so the popup is useful
  // either way; only a fetch failure leaves mv null.
  const loadMv = () => { setMvErr(false); api.mullvad().then(setMv).catch(() => setMvErr(true)) }
  useEffect(() => { if (open) loadMv() }, [open])
  const go = (to: string) => { onClose(); nav(to) }
  // Close on Escape (desktop).
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  // Swipe right (toward the edge) to close.
  const startX = useRef(0)
  // Portal to <body> so `position: fixed` isn't trapped by the top-bar's backdrop-filter stacking context.
  return createPortal(
    <>
      <div className={'drawer-backdrop' + (open ? ' show' : '')} onClick={onClose} />
      <nav
        className={'drawer' + (open ? ' open' : '')}
        aria-hidden={!open}
        onTouchStart={(e) => { startX.current = e.touches[0].clientX }}
        onTouchEnd={(e) => { if (e.changedTouches[0].clientX - startX.current > 55) onClose() }}
      >
        <div className="drawer-head">MANGA-UTILS</div>
        <div className="drawer-items">
          {ITEMS.map((it) => (
            <button key={it.to} className="drawer-item" onClick={() => go(it.to)}>{it.label}</button>
          ))}
        </div>
        {(ver || mv || mvErr) && (
          <div className="drawer-foot">
            {(mv || mvErr) && (
              <div className="drawer-mv-wrap">
                {mvOpen && mv && (
                  <div className="drawer-mv-pop">
                    <div className={'mv-state ' + (mv.mullvad_exit_ip ? 'on' : 'off')}>
                      {mv.mullvad_exit_ip ? 'Connected via Mullvad' : 'Not on Mullvad'}
                    </div>
                    {mv.mullvad_exit_ip_hostname && <div>Server · {mv.mullvad_exit_ip_hostname}</div>}
                    <div>{[mv.city, mv.country].filter(Boolean).join(', ') || '—'}</div>
                    <div>IP · {mv.ip || '—'}</div>
                    {mv.organization && <div>{mv.organization}</div>}
                  </div>
                )}
                <button
                  className={'drawer-mv ' + (mv?.mullvad_exit_ip ? 'on' : 'off')}
                  onClick={() => (mv ? setMvOpen((o) => !o) : loadMv())}
                  title={mv ? 'Server VPN egress' : 'Tap to retry'}
                >
                  {mv ? (mv.mullvad_exit_ip ? '🔒 Mullvad ▴' : '⚠ No VPN ▴') : '⚠ VPN? · retry'}
                </button>
              </div>
            )}
            {ver && <div className="drawer-ver">{ver}</div>}
          </div>
        )}
      </nav>
    </>,
    document.body,
  )
}
