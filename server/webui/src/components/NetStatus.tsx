import { createContext, useContext, useEffect, useRef, useState, ReactNode } from 'react'
import { api } from '../api'

// Server-side connectivity, shared app-wide. "Offline" means the SERVER has no internet egress — the phone
// still reaches the LAN server, so navigator.onLine is wrong here; the truth comes from /api/net/status.
interface NetCtx { online: boolean; checking: boolean; check: () => void }
const Ctx = createContext<NetCtx>({ online: true, checking: false, check: () => {} })
export const useNet = () => useContext(Ctx)

export function NetProvider({ children }: { children: ReactNode }) {
  const [online, setOnline] = useState(true)
  const [checking, setChecking] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout>>()

  const check = () => {
    setChecking(true)
    api.netCheck().then((s) => setOnline(s.online)).catch(() => {}).finally(() => setChecking(false))
  }

  useEffect(() => {
    let alive = true
    // Poll status; faster while offline so the banner clears within seconds of the server reconnecting
    // (the server itself re-probes every 8s when down).
    const tick = () => {
      api.netStatus()
        .then((s) => { if (alive) setOnline(s.online) })
        .catch(() => { /* the /status call failing means the LAN server is unreachable, not the internet — leave state */ })
        .finally(() => { if (alive) timer.current = setTimeout(tick, online ? 20000 : 8000) })
    }
    tick()
    return () => { alive = false; clearTimeout(timer.current) }
  }, [online])

  return <Ctx.Provider value={{ online, checking, check }}>{children}</Ctx.Provider>
}

/** Full-width bar shown under the top bar whenever the server is offline. */
export function OfflineBanner() {
  const { online, checking, check } = useNet()
  if (online) return null
  return (
    <div className="offline-banner" role="status">
      <span className="offline-banner-txt">
        <b>You appear to be offline.</b> The server can't reach the internet — downloaded manga still works.
      </span>
      <button className="offline-banner-btn" onClick={check} disabled={checking}>
        {checking ? 'Checking…' : 'Check again'}
      </button>
    </div>
  )
}

/** Standalone "you're offline" panel for screens that need the network (search, a non-downloaded chapter).
 *  onRetry (if given) re-runs the screen's own fetch alongside the connectivity re-check. */
export function OfflineNotice({ what, onRetry }: { what: string; onRetry?: () => void }) {
  const { checking, check } = useNet()
  return (
    <div className="offline-notice">
      <div className="offline-notice-icon">⚠</div>
      <div className="offline-notice-title">You appear to be offline</div>
      <div className="offline-notice-sub">The server can't reach the internet, so {what} isn't available. Downloaded manga still works.</div>
      <button className="offline-notice-btn" onClick={() => { check(); onRetry?.() }} disabled={checking}>
        {checking ? 'Checking…' : 'Try again'}
      </button>
    </div>
  )
}
