// Small circular progress indicator (e.g. a chapter downloading). pct is 0–100.
export function ProgressRing({ pct, size = 28 }: { pct: number; size?: number }) {
  const r = (size - 4) / 2
  const circ = 2 * Math.PI * r
  const off = circ * (1 - Math.max(0, Math.min(1, pct / 100)))
  const c = size / 2
  return (
    <svg className="ring" width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-label={`${Math.round(pct)}%`}>
      <circle cx={c} cy={c} r={r} className="ring-bg" />
      <circle cx={c} cy={c} r={r} className="ring-fg" strokeDasharray={circ} strokeDashoffset={off} transform={`rotate(-90 ${c} ${c})`} />
      <text x="50%" y="53%" className="ring-txt" dominantBaseline="middle" textAnchor="middle">{Math.round(pct)}</text>
    </svg>
  )
}
