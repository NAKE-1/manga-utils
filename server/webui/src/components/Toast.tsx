import { useEffect, useRef, useState } from 'react'
import { api } from '../api'

// Tiny global toast bus — call toast() from anywhere (even non-React code). Stylized for the web UI,
// pinned bottom-left, auto-dismiss, tap to dismiss early.
export type ToastKind = 'info' | 'success' | 'error'
type Item = { id: number; key?: string; msg: string; kind: ToastKind; bg?: boolean }

let seq = 0
let items: Item[] = []
const timers = new Map<number, number>()
const listeners = new Set<(t: Item[]) => void>()
const emit = () => listeners.forEach((l) => l(items))
const drop = (id: number) => {
  const t = timers.get(id); if (t) { clearTimeout(t); timers.delete(id) }
  items = items.filter((x) => x.id !== id); emit()
}
const arm = (id: number, ms: number) => {
  const t = timers.get(id); if (t) clearTimeout(t)
  timers.set(id, window.setTimeout(() => drop(id), ms))
}

// While the full-screen reader is open we mute BACKGROUND toasts (FlareSolverr solves, download
// progress, library scans, Discord throttling) so they don't pop over what you're reading. Muting is
// done at RENDER time (not at creation), so a bg toast already on screen vanishes the moment you open
// the reader and reappears if you back out while it's still alive; its dismiss timer keeps ticking
// either way. Toasts left un-`bg` — e.g. a connectivity/"can't reach server" alert — always render,
// since those are worth interrupting a read for. Set from App on the reader route.
let readerActive = false
export function setReaderActive(b: boolean) { if (readerActive !== b) { readerActive = b; items = [...items]; emit() } } // fresh ref so Toasts re-renders + re-filters

/** Pass a stable `key` to update an existing toast in place (e.g. a FlareSolverr host going
 *  solving → cleared) instead of stacking a new one. Auto-dismiss timer resets on each update.
 *  Pass `bg: true` for background/ambient toasts that are muted (not rendered) while reading. */
export function toast(msg: string, kind: ToastKind = 'info', ms = 3800, key?: string, opts?: { bg?: boolean }) {
  if (key) {
    const ex = items.find((t) => t.key === key)
    if (ex) { ex.msg = msg; ex.kind = kind; ex.bg = opts?.bg; items = [...items]; emit(); arm(ex.id, ms); return }
  }
  const id = ++seq
  items = [...items, { id, key, msg, kind, bg: opts?.bg }].slice(-4) // cap at 4 stacked
  emit()
  arm(id, ms)
}

export function Toasts() {
  const [list, setList] = useState<Item[]>(items)
  useEffect(() => { listeners.add(setList); return () => { listeners.delete(setList) } }, [])
  return (
    <div className="toasts">
      {list.filter((t) => !(t.bg && readerActive)).map((t) => (
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
let notifyRateAt = -1 // last-seen Discord rate-limit timestamp (-1 = not yet synced)

export function DownloadWatcher() {
  const prev = useRef<Record<string, string>>({})
  const doneInBatch = useRef(0)
  const wasBusy = useRef(false)
  const wasScanning = useRef(false)
  useEffect(() => {
    let alive = true
    // FlareSolverr solve activity → toasts, so a Cloudflare-solve pause is explained.
    const flareTick = async () => {
      try {
        const r = await api.flaresolverrEvents(flareCursor ?? undefined)
        if (flareCursor == null) { flareCursor = r.lastId; return } // first poll ever: just sync, no backlog
        for (const e of r.events) {
          const key = 'fs:' + e.host // one toast per host, updated in place (no solving+solved spam)
          if (e.phase === 'solving') toast(`FS · ${e.host} solving…`, 'info', 6000, key, { bg: true })
          else if (e.phase === 'solved') toast(`FS · ${e.host} cleared`, 'success', 2500, key, { bg: true })
          else if (e.phase === 'failed') toast(`FS · ${e.host} failed`, 'error', 4500, key, { bg: true })
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
            if (t.state === 'done') { toast(`Downloaded “${t.mangaTitle}”`, 'success', 3800, undefined, { bg: true }); doneInBatch.current++ }
            else if (t.state === 'failed') toast(`Download failed: ${t.mangaTitle}${t.error ? ' — ' + t.error : ''}`, 'error', 3800, undefined, { bg: true })
          }
        }
        prev.current = Object.fromEntries(d.tasks.map((t) => [t.id, t.state]))
        const busy = d.active + d.queued
        if (wasBusy.current && busy === 0) {
          if (doneInBatch.current > 1) toast('All downloads complete', 'success', 3800, undefined, { bg: true })
          doneInBatch.current = 0
        }
        wasBusy.current = busy > 0
      } catch { /* server not reachable — ignore */ }
      // Library-update scan (manual OR the daily scheduled one) → a live "Scanning for updates NN%" toast.
      try {
        const u = await api.updateProgress()
        if (u.running) { toast(`Scanning for updates ${u.total > 0 ? Math.round((u.done / u.total) * 100) : 0}%`, 'info', 5000, 'lib-scan', { bg: true }); wasScanning.current = true }
        else if (wasScanning.current) { toast('Library scan complete', 'success', 3000, 'lib-scan', { bg: true }); wasScanning.current = false }
      } catch { /* ignore */ }
      // Discord webhook hit a rate limit → let the user know it's throttling (auto-retries server-side).
      try {
        const n = await api.notifyStatus()
        if (notifyRateAt < 0) notifyRateAt = n.rateLimitedAtMs
        else if (n.rateLimitedAtMs > notifyRateAt) {
          notifyRateAt = n.rateLimitedAtMs
          toast(`Discord rate-limited — retrying in ${Math.ceil(n.retryAfter)}s`, 'info', 6000, 'discord-rl', { bg: true })
        }
      } catch { /* ignore */ }
    }
    const iv = window.setInterval(() => { if (alive) tick() }, 3500)
    tick()
    return () => { alive = false; window.clearInterval(iv) }
  }, [])
  return null
}
