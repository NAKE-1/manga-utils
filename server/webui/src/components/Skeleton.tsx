// Loading placeholders so screens show structure (not a bare spinner) while data/images load.

export function SkeletonGrid({ count = 9 }: { count?: number }) {
  return (
    <div className="grid" style={{ paddingTop: 14 }}>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="cover-card full">
          <div className="cover-frame"><div className="cover-skel skeleton" /></div>
          <div className="skeleton sk-line" style={{ marginTop: 8, width: '85%' }} />
        </div>
      ))}
    </div>
  )
}

export function DetailSkeleton() {
  return (
    <div style={{ paddingTop: 8 }}>
      <div className="detail-head">
        <div className="detail-cover"><div className="cover-skel skeleton" /></div>
        <div style={{ flex: 1 }}>
          <div className="skeleton sk-line" style={{ height: 24, width: '80%' }} />
          <div className="skeleton sk-line" style={{ height: 14, width: '50%', marginTop: 12 }} />
        </div>
      </div>
      <div className="chips-2" style={{ paddingLeft: 16, paddingRight: 16 }}>
        <div className="chip-row" style={{ padding: 0 }}>
          {Array.from({ length: 5 }).map((_, i) => <div key={i} className="skeleton" style={{ height: 30, width: 84, borderRadius: 10, flex: '0 0 auto' }} />)}
        </div>
      </div>
      <div className="chapters" style={{ paddingTop: 16 }}>
        {Array.from({ length: 6 }).map((_, i) => <div key={i} className="skeleton" style={{ height: 52, borderRadius: 10, marginBottom: 8 }} />)}
      </div>
    </div>
  )
}
