import { useEffect, useRef, useState } from 'react'
import { api } from '../api'

// Tiny global toast bus — call toast() from anywhere (even non-React code). Stylized for the web UI,
// pinned bottom-left, auto-dismiss, tap to dismiss early.
export type ToastKind = 'info' | 'success' | 'error'
type Item = { id: number; msg: string; kind: ToastKind }

let seq = 0
let items: Item[] = []
const listeners = new Set<(t: Item[]) => void>()
const emit = () => listeners.forEach((l) => l(items))
const drop = (id: number) => { items = items.filter((t) => t.id !== id); emit() }

export function toast(msg: string, kind: ToastKind = 'info', ms = 3800) {
  const id = ++seq
  items = [...items, { id, msg, kind }].slice(-4) // cap at 4 stacked
  emit()
  window.setTimeout(() => drop(id), ms)
}

export function Toasts() {
  const [list, setList] = useState<Item[]>(items)
  useEffect(() => { listeners.add(setList); return () => { listeners.delete(setList) } }, [])
  return (
    <div className="toasts">
      {list.map((t) => (
        <button key={t.id} className={'toast toast-' + t.kind} onClick={() => drop(t.id)}>
          <span className="toast-icon">{t.kind === 'success' ? '✓' : t.kind === 'error' ? '✕' : 'ℹ'}</span>
          <span className="toast-msg">{t.msg}</span>
        </button>
      ))}
    </div>
  )
}

/**
 * Watches the download queue app-wide and raises toasts on completion/failure, so you get notified
 * even when you're not on the Downloads screen. Mounted once at the app root.
 */
// Module-level so the poll cursor survives a DownloadWatcher remount (otherwise re-syncing swallows
// the events we want to toast).
let flareCursor: number | null = null

export function DownloadWatcher() {
  const prev = useRef<Record<string, string>>({})
  const doneInBatch = useRef(0)
  const wasBusy = useRef(false)
  useEffect(() => {
    let alive = true
    // FlareSolverr solve activity → toasts, so a Cloudflare-solve pause is explained.
    const flareTick = async () => {
      try {
        const r = await api.flaresolverrEvents(flareCursor ?? undefined)
        if (flareCursor == null) { flareCursor = r.lastId; return } // first poll ever: just sync, no backlog
        for (const e of r.events) {
          if (e.phase === 'solving') toast(`FS · solving ${e.host}…`, 'info')
          else if (e.phase === 'solved') toast(`FS · ${e.host} cleared`, 'success')
          else if (e.phase === 'failed') toast(`FS · ${e.host} failed`, 'error')
        }
        flareCursor = r.lastId
      } catch { /* ignore */ }
    }
    const tick = async () => {
      flareTick()
      try {
        const d = await api.downloads()
        for (const t of d.tasks) {
          const before = prev.current[t.id]
          if (before && before !== t.state) {
            if (t.state === 'done') { toast(`Downloaded “${t.mangaTitle}”`, 'success'); doneInBatch.current++ }
            else if (t.state === 'failed') toast(`Download failed: ${t.mangaTitle}${t.error ? ' — ' + t.error : ''}`, 'error')
          }
        }
        prev.current = Object.fromEntries(d.tasks.map((t) => [t.id, t.state]))
        const busy = d.active + d.queued
        if (wasBusy.current && busy === 0) {
          if (doneInBatch.current > 1) toast('All downloads complete', 'success')
          doneInBatch.current = 0
        }
        wasBusy.current = busy > 0
      } catch { /* server not reachable — ignore */ }
    }
    const iv = window.setInterval(() => { if (alive) tick() }, 3500)
    tick()
    return () => { alive = false; window.clearInterval(iv) }
  }, [])
  return null
}
