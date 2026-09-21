import { useEffect, useRef, useState, type PointerEvent } from 'react'

// Full-screen overlay that streams the server-side offscreen browser (JPEG feed) and forwards taps back
// to it, so a human can solve a source's "verify you're human" challenge. On close, cookies solved here
// are already in the shared cookie store, so the caller just retries. Opens by ?url or by source id.
export function WebviewModal({ url, source, path, onClose }: { url?: string; source?: number | string; path?: string; onClose: () => void }) {
  const [status, setStatus] = useState('Opening…')
  const [starting, setStarting] = useState(false) // CEF still coming up → show spinner + let the effect auto-retry
  const [reloadKey, setReloadKey] = useState(0)    // Reload button bumps this to re-run the open effect
  const [shownUrl, setShownUrl] = useState('')
  const [cookies, setCookies] = useState<number | null>(null)
  const [solveMsg, setSolveMsg] = useState('')
  const [solving, setSolving] = useState(false)
  const [dims, setDims] = useState<{ w: number; h: number } | null>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const lastObj = useRef<string | null>(null)
  const drag = useRef<{ y: number; startY: number; moved: boolean } | null>(null)

  useEffect(() => {
    let alive = true
    let retryTimer: number | undefined
    let frameTimer: number | undefined
    const q = url ? 'url=' + encodeURIComponent(url) : 'source=' + encodeURIComponent(String(source ?? '')) + (path ? '&path=' + encodeURIComponent(path) : '')

    // Stream JPEG frames — only started once CEF reports "ready" (so we never poll frames during cold init).
    const startFrames = () => {
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
        if (alive) frameTimer = window.setTimeout(tick, 130)
      }
      tick()
    }

    // Ask the server to open the page. CEF is lazy (no startup prewarm), so the first open after a restart
    // returns "starting" while Chromium comes up — show a spinner and retry every 2s until "ready" (or a
    // clean "failed" that tells you to restart), instead of the request hanging and reading as "unreachable".
    const attempt = async () => {
      if (!alive) return
      try {
        const r = await fetch('/api/webview/open?' + q, { method: 'POST' })
        if (!alive) return
        if (!r.ok) { setStarting(false); setStatus(`Couldn't open (HTTP ${r.status})`); return }
        const info = await r.json()
        if (!alive) return
        if (info.status === 'ready') {
          setStarting(false)
          setDims({ w: info.width, h: info.height })
          setShownUrl(info.url || '')
          setStatus('Loading page…')
          startFrames()
        } else if (info.status === 'failed') {
          setStarting(false)
          setStatus(info.detail || 'The in-app browser failed to start — restart the server to recover.')
        } else { // "starting" / "cold" — Chromium is coming up; keep polling
          setStarting(true)
          setStatus('Starting the in-app browser… (first use after a restart can take a few seconds)')
          retryTimer = window.setTimeout(attempt, 2000)
        }
      } catch { if (alive) { setStarting(false); setStatus("Couldn't reach the server — tap Reload to try again.") } }
    }
    setStatus('Opening…')
    attempt()

    return () => {
      alive = false
      if (retryTimer) clearTimeout(retryTimer)
      if (frameTimer) clearTimeout(frameTimer)
      if (lastObj.current) URL.revokeObjectURL(lastObj.current)
      fetch('/api/webview/close', { method: 'POST' }).catch(() => {})
    }
  }, [url, source, path, reloadKey])

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

  // Map a client point on the displayed frame to OSR pixels (+ the display→OSR scale for scroll deltas).
  function toOsr(clientX: number, clientY: number) {
    const img = imgRef.current
    if (!img || !dims) return null
    const rect = img.getBoundingClientRect()
    if (rect.width === 0 || rect.height === 0) return null
    return {
      x: Math.round(((clientX - rect.left) / rect.width) * dims.w),
      y: Math.round(((clientY - rect.top) / rect.height) * dims.h),
      scale: dims.h / rect.height, // screen px → OSR px (frame is usually shown smaller than 780px)
    }
  }

  function sendClick(clientX: number, clientY: number) {
    const o = toOsr(clientX, clientY)
    if (!o || o.x < 0 || o.y < 0 || o.x > dims!.w || o.y > dims!.h) return
    fetch(`/api/webview/input?x=${o.x}&y=${o.y}`, { method: 'POST' }).catch(() => {})
  }
  function sendScroll(clientX: number, clientY: number, dyScreen: number) {
    const o = toOsr(clientX, clientY)
    if (!o || dyScreen === 0) return
    fetch(`/api/webview/scroll?x=${o.x}&y=${o.y}&dy=${Math.round(dyScreen * o.scale)}`, { method: 'POST' }).catch(() => {})
  }

  // Pointer drag = scroll (touch or mouse); a drag that barely moved is treated as a tap → click.
  function onDown(e: PointerEvent<HTMLImageElement>) {
    drag.current = { y: e.clientY, startY: e.clientY, moved: false }
    imgRef.current?.setPointerCapture(e.pointerId)
  }
  function onMove(e: PointerEvent<HTMLImageElement>) {
    const d = drag.current
    if (!d) return
    if (Math.abs(e.clientY - d.startY) > 6) d.moved = true
    sendScroll(e.clientX, e.clientY, d.y - e.clientY) // finger up → positive → page down
    d.y = e.clientY
  }
  function onUp(e: PointerEvent<HTMLImageElement>) {
    const d = drag.current
    drag.current = null
    imgRef.current?.releasePointerCapture(e.pointerId)
    if (d && !d.moved) sendClick(e.clientX, e.clientY) // it was a tap, not a scroll
  }

  // Mouse wheel → scroll. React's onWheel is passive (can't preventDefault), so attach non-passively
  // here to stop the wheel from scrolling the page/modal behind the frame.
  useEffect(() => {
    const img = imgRef.current
    if (!img) return
    const onWheel = (e: WheelEvent) => { e.preventDefault(); sendScroll(e.clientX, e.clientY, e.deltaY) }
    img.addEventListener('wheel', onWheel, { passive: false })
    return () => img.removeEventListener('wheel', onWheel)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dims])

  return (
    <div className="wv-overlay" role="dialog" aria-modal="true">
      <div className="wv-bar">
        <button className="btn" title="Close &amp; retry" onClick={onClose}>← C+R</button>
        <span className="wv-url" title={shownUrl}>{shownUrl || 'Tap the shapes to solve, then Close'}</span>
        {/* Status lives INSIDE the button (solveMsg already holds "🧩 solving…" / "✓ solved in 2 clicks" /
            "✗ …") so it can't overflow the row as a separate label. */}
        <button className="btn wv-solve" disabled={solving} title="Auto-solve the shape captcha" onClick={autoSolve}>{solveMsg || '🧩 Auto-solve'}</button>
        {cookies != null && <span className="wv-cookies" title="Cookies stored for this site in the session">🍪 {cookies}</span>}
      </div>
      <div className="wv-stage">
        {status && (
          <div className="wv-status">
            {starting && <div className="spinner" />}
            <div className="wv-status-msg">{status}</div>
            <button className="btn" onClick={() => { setStarting(false); setStatus('Opening…'); setReloadKey((k) => k + 1) }}>⟳ Reload</button>
          </div>
        )}
        <img
          ref={imgRef}
          className="wv-frame"
          alt=""
          draggable={false}
          onPointerDown={onDown}
          onPointerMove={onMove}
          onPointerUp={onUp}
          style={dims ? { aspectRatio: `${dims.w} / ${dims.h}` } : undefined}
        />
      </div>
    </div>
  )
}
