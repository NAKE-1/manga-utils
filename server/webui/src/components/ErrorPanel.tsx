import { useState } from 'react'
import { WebviewModal } from './WebviewModal'

// Friendly, retryable error state (distinguishes offline from a source/server error). When a source id
// (or url) is given, also offers "Open in WebView" — solve the source's human-check, then it retries.
export function ErrorPanel({
  onRetry,
  message,
  webviewSource,
  webviewUrl,
}: {
  onRetry: () => void
  message?: string
  webviewSource?: number | string
  webviewUrl?: string
}) {
  const offline = typeof navigator !== 'undefined' && !navigator.onLine
  const [wv, setWv] = useState(false)
  const canWebview = !offline && (webviewSource != null || !!webviewUrl)
  return (
    <div className="center-msg">
      <div style={{ fontSize: 28, marginBottom: 8 }}>{offline ? '📡' : '⚠️'}</div>
      <div>{offline ? "You're offline." : message ?? "Couldn't load that."}</div>
      {!offline && <div style={{ fontSize: 13, marginTop: 4 }}>The source may be slow, blocked, or asking you to verify you're human.</div>}
      <div style={{ display: 'flex', gap: 8, marginTop: 16, flexWrap: 'wrap', justifyContent: 'center' }}>
        <button className="btn" onClick={onRetry}>Retry</button>
        {canWebview && <button className="btn" onClick={() => setWv(true)}>Open in WebView</button>}
      </div>
      {wv && (
        <WebviewModal
          url={webviewUrl}
          source={webviewSource}
          onClose={() => { setWv(false); onRetry() }}
        />
      )}
    </div>
  )
}
