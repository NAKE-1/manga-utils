// Friendly, retryable error state (distinguishes offline from a source/server error).
export function ErrorPanel({ onRetry, message }: { onRetry: () => void; message?: string }) {
  const offline = typeof navigator !== 'undefined' && !navigator.onLine
  return (
    <div className="center-msg">
      <div style={{ fontSize: 28, marginBottom: 8 }}>{offline ? '📡' : '⚠️'}</div>
      <div>{offline ? "You're offline." : message ?? "Couldn't load that."}</div>
      {!offline && <div style={{ fontSize: 13, marginTop: 4 }}>The source may be slow or unavailable.</div>}
      <button className="btn" style={{ marginTop: 16 }} onClick={onRetry}>Retry</button>
    </div>
  )
}
