import { useEffect, useState } from 'react'
import { WebviewModal } from './WebviewModal'

// Polls the server for hosts that hit an interactive human-check (captcha) — from any path: search,
// browse, a manual/overnight library update, or a download. Surfaces one banner with "Solve", which
// opens the WebView; solving clears the flag so the blocked action can go through next time.
export function HumanCheckWatcher() {
  const [hosts, setHosts] = useState<string[]>([])
  const [solving, setSolving] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    const poll = async () => {
      try {
        const r = await fetch('/api/webview/pending')
        if (!r.ok || !alive) return
        const list: { host: string }[] = await r.json()
        setHosts(list.map((x) => x.host))
      } catch { /* transient */ }
    }
    poll()
    const id = window.setInterval(poll, 8000)
    return () => { alive = false; clearInterval(id) }
  }, [])

  if (solving) {
    return (
      <WebviewModal
        url={`https://${solving}/`}
        onClose={async () => {
          const h = solving
          await fetch('/api/webview/pending/clear?host=' + encodeURIComponent(h), { method: 'POST' }).catch(() => {})
          setHosts((prev) => prev.filter((x) => x !== h))
          setSolving(null)
        }}
      />
    )
  }

  if (hosts.length === 0) return null
  const host = hosts[0]
  return (
    <div className="humancheck-bar" role="alert">
      <span className="humancheck-txt">
        🔒 <b>{host}</b> needs you to verify you're human{hosts.length > 1 ? ` (+${hosts.length - 1} more)` : ''} before it can load.
      </span>
      <button className="btn" onClick={() => setSolving(host)}>Solve</button>
    </div>
  )
}
