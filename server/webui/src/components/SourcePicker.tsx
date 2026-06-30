import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Source } from '../api'
import { IconChevronDown, IconCloudflare } from './icons'

const GLOBAL = '__global__'

// Stable per-name color for the letter-avatar placeholder (real logos come later).
function avatarColor(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return `hsl(${h % 360} 42% 40%)`
}
const Avatar = ({ name }: { name: string }) => (
  <span className="src-avatar" style={{ background: avatarColor(name) }}>{(name[0] || '?').toUpperCase()}</span>
)

const CF_TITLE: Record<string, string> = {
  green: 'No Cloudflare protection',
  red: 'Behind Cloudflare — no bypass installed',
  orange: 'Behind Cloudflare — bypass active',
}

/** Source dropdown → a bottom sheet (portaled to body so it's immune to ancestor
 *  transforms/stacking that broke the old absolute menu on mobile). Drag down to dismiss. */
export function SourcePicker({ sources, value, onChange, cfBypass = false }: { sources: Source[]; value: string; onChange: (id: string) => void; cfBypass?: boolean }) {
  void cfBypass // colour now comes from each source's cfState
  const [open, setOpen] = useState(false)
  const [dragY, setDragY] = useState(0)
  const startY = useRef<number | null>(null)
  const cur = sources.find((s) => s.id === value)
  const cf = (s: Source) => s.id !== GLOBAL && <IconCloudflare className={'src-cf ' + s.cfState} aria-label={CF_TITLE[s.cfState]} />
  const down = (s: Source) => s.down && <span className="src-down" title="Source unreachable">DOWN</span>

  // Lock background scroll + Esc-to-close while the sheet is open.
  useEffect(() => {
    if (!open) return
    setDragY(0)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('keydown', onKey)
    return () => { document.body.style.overflow = prev; document.removeEventListener('keydown', onKey) }
  }, [open])

  function onGripStart(e: React.TouchEvent) { startY.current = e.touches[0].clientY }
  function onGripMove(e: React.TouchEvent) {
    if (startY.current == null) return
    setDragY(Math.max(0, e.touches[0].clientY - startY.current))
  }
  function onGripEnd() {
    if (dragY > 90) setOpen(false)
    else setDragY(0)
    startY.current = null
  }

  if (!cur) return null
  return (
    <div className="src-picker">
      <button className="src-current" onClick={() => setOpen(true)} aria-haspopup="listbox" aria-expanded={open}>
        <Avatar name={cur.name} />
        <span className="src-name">{cur.name}</span>
        {down(cur)}
        {cf(cur)}
        {cur.nsfw && <span className="src-18">18+</span>}
        <IconChevronDown className="src-caret" />
      </button>

      {open && createPortal(
        <div className="src-sheet-scrim" onClick={() => setOpen(false)}>
          <div className="src-sheet" style={dragY ? { transform: `translateY(${dragY}px)`, transition: 'none' } : undefined} onClick={(e) => e.stopPropagation()}>
            <div className="src-sheet-grip" onTouchStart={onGripStart} onTouchMove={onGripMove} onTouchEnd={onGripEnd}>
              <div className="sheet-handle" />
              <div className="src-sheet-title">Choose source</div>
            </div>
            <div className="src-sheet-list" role="listbox">
              {sources.map((s) => (
                <button key={s.id} role="option" aria-selected={s.id === value} className={'src-item' + (s.id === value ? ' on' : '')} onClick={() => { onChange(s.id); setOpen(false) }}>
                  <Avatar name={s.name} />
                  <span className="src-name">{s.name}</span>
                  {down(s)}
                  {cf(s)}
                  {s.lang && s.id !== GLOBAL && <span className="src-lang">{s.lang.toUpperCase()}</span>}
                  {s.nsfw && <span className="src-18">18+</span>}
                </button>
              ))}
            </div>
          </div>
        </div>,
        document.body,
      )}
    </div>
  )
}
