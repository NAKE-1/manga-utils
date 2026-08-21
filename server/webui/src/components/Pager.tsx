// A small numbered pager: ‹ Page N of M ›. Renders nothing when there's only one page.
// `page` is 0-indexed; `onPage` receives the new 0-indexed page.
export function Pager({ page, total, size, onPage }: { page: number; total: number; size: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  if (pages <= 1) return null
  const clamped = Math.min(page, pages - 1)
  return (
    <div className="pager">
      <button className="pager-btn" disabled={clamped <= 0} onClick={() => onPage(clamped - 1)} aria-label="Previous page">‹</button>
      <span className="pager-label">Page {clamped + 1} of {pages}</span>
      <button className="pager-btn" disabled={clamped >= pages - 1} onClick={() => onPage(clamped + 1)} aria-label="Next page">›</button>
    </div>
  )
}
