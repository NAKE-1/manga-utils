import { useEffect, useRef, useState } from 'react'
import { Source } from '../api'
import { IconChevronDown, IconCloudflare } from './icons'

// Stable per-name color for the letter-avatar placeholder (real logos come later).
function avatarColor(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return `hsl(${h % 360} 42% 40%)`
}
const Avatar = ({ name }: { name: string }) => (
  <span className="src-avatar" style={{ background: avatarColor(name) }}>{(name[0] || '?').toUpperCase()}</span>
)

const GLOBAL = '__global__'

/** Search-engine dropdown: logo (letter for now) · Cloudflare mark · 18+ badge · title. */
export function SourcePicker({ sources, value, onChange, cfBypass = false }: { sources: Source[]; value: string; onChange: (id: string) => void; cfBypass?: boolean }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const cur = sources.find((s) => s.id === value)
  // CF mark is hidden for the synthetic "Global" entry.
  const cf = (s: Source) => s.id !== GLOBAL && <IconCloudflare className={'src-cf' + (cfBypass ? ' on' : '')} />


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
        {cf(cur)}
        {cur.nsfw && <span className="src-18">18+</span>}
        <IconChevronDown className={'src-caret' + (open ? ' up' : '')} />
      </button>
      {open && (
        <div className="src-menu" role="listbox">
          {sources.map((s) => (
            <button key={s.id} role="option" aria-selected={s.id === value} className={'src-item' + (s.id === value ? ' on' : '')} onClick={() => { onChange(s.id); setOpen(false) }}>
              <Avatar name={s.name} />
              <span className="src-name">{s.name}</span>
              {cf(s)}
              {s.lang && <span className="src-lang">{s.lang.toUpperCase()}</span>}
              {s.nsfw && <span className="src-18">18+</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
