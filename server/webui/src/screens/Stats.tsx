import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type Stats as StatsData } from '../api'
import { IconArrowLeft } from '../components/icons'

const fmtSize = (b: number) => (b >= 1 << 30 ? `${(b / (1 << 30)).toFixed(1)} GB` : b >= 1 << 20 ? `${(b / (1 << 20)).toFixed(0)} MB` : `${Math.max(0, Math.round(b / 1024))} KB`)
function ago(ts: number): string {
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (s < 60) return 'just now'
  const m = Math.floor(s / 60); if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60); if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24); if (d < 7) return `${d}d ago`
  const w = Math.floor(d / 7); if (w < 5) return `${w}w ago`
  return `${Math.floor(d / 30)}mo ago`
}

export default function Stats() {
  const nav = useNavigate()
  const [s, setS] = useState<StatsData | null>(null)
  useEffect(() => { api.stats().then(setS).catch(() => setS(null)) }, [])

  const openManga = (sourceId: string, mangaUrl: string) => nav(`/manga/${sourceId}?url=${encodeURIComponent(mangaUrl)}`)

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Reading stats</span>
      </div>

      {s === null ? <div className="spinner" /> : (
        <>
          <div className="dm-overview">
            <div className="dm-stat"><span className="dm-stat-n">{s.chaptersRead.toLocaleString()}</span><span className="dm-stat-l">chapters read</span></div>
            <div className="dm-stat"><span className="dm-stat-n">{s.readThisWeek.toLocaleString()}</span><span className="dm-stat-l">this week</span></div>
            <div className="dm-stat"><span className="dm-stat-n">{s.seriesInLibrary.toLocaleString()}</span><span className="dm-stat-l">in library</span></div>
            <div className="dm-stat"><span className="dm-stat-n">{s.chaptersDownloaded.toLocaleString()}</span><span className="dm-stat-l">downloaded</span></div>
            <div className="dm-stat wide"><span className="dm-stat-n">{fmtSize(s.bytesOnDisk)}</span><span className="dm-stat-l">on disk</span></div>
          </div>

          <div className="st-section-h">Most read</div>
          {s.topSeries.length === 0 ? <div className="st-empty">No read chapters yet.</div> : (
            <div className="st-list">
              {s.topSeries.map((t, i) => {
                const max = s.topSeries[0]?.count || 1
                return (
                  <button key={t.sourceId + '|' + t.mangaUrl + i} className="st-bar-row" onClick={() => t.mangaUrl && openManga(t.sourceId, t.mangaUrl)}>
                    <div className="st-bar-top">
                      <span className="st-bar-title">{t.title}</span>
                      <span className="st-bar-count">{t.count}</span>
                    </div>
                    <div className="st-bar-track"><div className="st-bar-fill" style={{ width: `${Math.round((t.count / max) * 100)}%` }} /></div>
                  </button>
                )
              })}
            </div>
          )}

          <div className="st-section-h">Recently read</div>
          {s.recent.length === 0 ? <div className="st-empty">Nothing read recently.</div> : (
            <div className="st-list">
              {s.recent.map((r, i) => (
                <button key={r.sourceId + '|' + r.mangaUrl + i} className="st-recent-row" onClick={() => openManga(r.sourceId, r.mangaUrl)}>
                  <div className="st-recent-main">
                    <div className="st-recent-title">{r.title}</div>
                    <div className="st-recent-sub">{r.chapter}</div>
                  </div>
                  <span className="st-recent-time">{ago(r.readAt)}</span>
                </button>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
