import { useEffect, useRef, useState, type PointerEvent } from 'react'

// Full-screen overlay that streams the server-side offscreen browser (JPEG feed) and forwards taps back
// to it, so a human can solve a source's "verify you're human" challenge. On close, cookies solved here
// are already in the shared cookie store, so the caller just retries. Opens by ?url or by source id.
export function WebviewModal({ url, source, path, onClose }: { url?: string; source?: number | string; path?: string; onClose: () => void }) {
  const [status, setStatus] = useState('Opening…')
  const [shownUrl, setShownUrl] = useState('')
  const [cookies, setCookies] = useState<number | null>(null)
  const [solveMsg, setSolveMsg] = useState('')
  const [solving, setSolving] = useState(false)
  const [dims, setDims] = useState<{ w: number; h: number } | null>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const lastObj = useRef<string | null>(null)

  useEffect(() => {
    let alive = true
    let timer: number | undefined
    const q = url ? 'url=' + encodeURIComponent(url) : 'source=' + encodeURIComponent(String(source ?? '')) + (path ? '&path=' + encodeURIComponent(path) : '')
    ;(async () => {
      try {
        const r = await fetch('/api/webview/open?' + q, { method: 'POST' })
        if (!alive) return
        if (!r.ok) { setStatus(`Couldn't open (HTTP ${r.status})`); return }
        const info = await r.json()
        if (!alive) return
        setDims({ w: info.width, h: info.height })
        setShownUrl(info.url || '')
        setStatus('Loading page…')
      } catch { if (alive) setStatus("Couldn't reach the server"); return }

      const tick = async () => {
        try {
          const fr = await fetch('/api/webview/frame', { cache: 'no-store' })
          if (fr.status === 200) {
            const obj = URL.createObjectURL(await fr.blob())
            if (imgRef.current) imgRef.current.src = obj
            if (lastObj.current) URL.revokeObjectURL(lastObj.current)
            lastObj.current = obj
            setStatus('')
          }
        } catch { /* transient — keep polling */ }
        if (alive) timer = window.setTimeout(tick, 130)
      }
      tick()
    })()

    return () => {
      alive = false
      if (timer) clearTimeout(timer)
      if (lastObj.current) URL.revokeObjectURL(lastObj.current)
      fetch('/api/webview/close', { method: 'POST' }).catch(() => {})
    }
  }, [url, source, path])

  // Poll the cookie counter for the top bar (visual only) — slow, it just reassures you the session is
  // capturing cookies (e.g. cf_clearance) as you solve.
  useEffect(() => {
    let alive = true
    const tick = async () => {
      try { const r = await fetch('/api/webview/status', { cache: 'no-store' }); if (alive && r.ok) setCookies((await r.json()).cookies) } catch { /* ignore */ }
    }
    tick()
    const t = window.setInterval(tick, 1500)
    return () => { alive = false; clearInterval(t) }
  }, [])

  // Fire the ONNX auto-solver against whatever challenge is currently in the WebView.
  async function autoSolve() {
    setSolving(true); setSolveMsg('🧩 solving…')
    let host = ''
    try { host = new URL(shownUrl).host } catch { /* no url yet */ }
    try {
      const r = await fetch('/api/webview/autosolve' + (host ? `?host=${encodeURIComponent(host)}` : ''), { method: 'POST' }).then((x) => x.json())
      setSolveMsg((r.solved ? '✓ ' : '✗ ') + (r.message || (r.solved ? 'solved' : 'not solved')))
    } catch { setSolveMsg('✗ auto-solve failed') }
    finally { setSolving(false) }
  }

  // Map a tap on the displayed frame to OSR-pixel coordinates and forward it as a click.
  function onTap(e: PointerEvent<HTMLImageElement>) {
    const img = imgRef.current
    if (!img || !dims) return
    const rect = img.getBoundingClientRect()
    if (rect.width === 0 || rect.height === 0) return
    const x = Math.round(((e.clientX - rect.left) / rect.width) * dims.w)
    const y = Math.round(((e.clientY - rect.top) / rect.height) * dims.h)
    if (x < 0 || y < 0 || x > dims.w || y > dims.h) return
    fetch(`/api/webview/input?x=${x}&y=${y}`, { method: 'POST' }).catch(() => {})
  }

  return (
    <div className="wv-overlay" role="dialog" aria-modal="true">
      <div className="wv-bar">
        <button className="btn" onClick={onClose}>← Close &amp; retry</button>
        <span className="wv-url" title={shownUrl}>{shownUrl || 'Tap the shapes to solve, then Close'}</span>
        <button className="btn wv-solve" disabled={solving} onClick={autoSolve}>{solving ? '🧩 solving…' : '🧩 Auto-solve'}</button>
        {solveMsg && <span className="wv-solvemsg" title={solveMsg}>{solveMsg}</span>}
        {cookies != null && <span className="wv-cookies" title="Cookies stored for this site in the session">🍪 {cookies}</span>}
      </div>
      <div className="wv-stage">
        {status && <div className="wv-status">{status}</div>}
        <img
          ref={imgRef}
          className="wv-frame"
          alt=""
          draggable={false}
          onPointerUp={onTap}
          style={dims ? { aspectRatio: `${dims.w} / ${dims.h}` } : undefined}
        />
      </div>
    </div>
  )
}
