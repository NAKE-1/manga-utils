import { useEffect, useRef, useState } from 'react'
import { Source } from '../api'
import { IconChevronDown } from './icons'

// Stable per-name color for the letter-avatar placeholder (real logos come later).
function avatarColor(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return `hsl(${h % 360} 42% 40%)`
}
const Avatar = ({ name }: { name: string }) => (
  <span className="src-avatar" style={{ background: avatarColor(name) }}>{(name[0] || '?').toUpperCase()}</span>
)

/** Search-engine dropdown: logo (letter for now) · 18+ badge · title. */
export function SourcePicker({ sources, value, onChange }: { sources: Source[]; value: string; onChange: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const cur = sources.find((s) => s.id === value)

  useEffect(() => {
    if (!open) return
    const h = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', h)
    return () => document.removeEventListener('mousedown', h)
  }, [open])

  if (!cur) return null
  return (
    <div className="src-picker" ref={ref}>
      <button className="src-current" onClick={() => setOpen((v) => !v)} aria-haspopup="listbox" aria-expanded={open}>
        <Avatar name={cur.name} />
        <span className="src-name">{cur.name}</span>
        {cur.nsfw && <span className="src-18">18+</span>}
        <IconChevronDown className={'src-caret' + (open ? ' up' : '')} />
      </button>
      {open && (
        <div className="src-menu" role="listbox">
          {sources.map((s) => (
            <button key={s.id} role="option" aria-selected={s.id === value} className={'src-item' + (s.id === value ? ' on' : '')} onClick={() => { onChange(s.id); setOpen(false) }}>
              <Avatar name={s.name} />
              <span className="src-name">{s.name}</span>
              {s.lang && <span className="src-lang">{s.lang.toUpperCase()}</span>}
              {s.nsfw && <span className="src-18">18+</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
