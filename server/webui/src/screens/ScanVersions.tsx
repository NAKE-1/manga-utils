import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ScanPlan, ScanSeriesPlan } from '../api'

const fmtBytes = (b: number) => {
  if (b <= 0) return '—'
  const gb = b / 1024 ** 3
  return gb >= 1 ? `${gb.toFixed(1)} GB` : `${Math.max(1, Math.round(b / 1024 ** 2))} MB`
}

/**
 * Scan for scanlations you don't have yet, and fill them in — one series at a time.
 *
 * The dry run is the point. Keeping every version of every chapter is roughly a 3x storage increase, so
 * nothing is downloaded until you pick a series and confirm. Library-wide is a report only; the server
 * refuses to start it, deliberately.
 */
export default function ScanVersions() {
  const nav = useNavigate()
  const [plan, setPlan] = useState<ScanPlan | null>(null)
  const [err, setErr] = useState('')
  const [open, setOpen] = useState<string | null>(null)   // series expanded for its per-chapter detail
  const [detail, setDetail] = useState<ScanSeriesPlan | null>(null)
  const [busy, setBusy] = useState('')
  const [queued, setQueued] = useState<{ title: string; n: number } | null>(null)
  const [started, setStarted] = useState<Record<string, number>>({}) // title -> versions queued, sticky

  useEffect(() => {
    api.scanverPlan().then(setPlan).catch((e) => setErr(String(e?.message || e)))
  }, [])

  async function expand(title: string) {
    if (open === title) { setOpen(null); setDetail(null); return }
    setOpen(title); setDetail(null)
    // The library-wide plan omits per-chapter detail (it would be enormous), so fetch it per series.
    setDetail(await api.scanverPlan(title).then((p) => p.series[0] ?? null).catch(() => null))
  }

  async function start(title: string) {
    setBusy(title)
    try {
      const r = await api.scanverStart(title)
      setQueued({ title, n: r.queued })
      setStarted((m) => ({ ...m, [title]: r.queued }))
      setPlan(await api.scanverPlan().catch(() => plan))
    } catch (e: any) { setErr(String(e?.message || e)) } finally { setBusy('') }
  }

  if (err) return <div className="center-msg">{err}</div>
  if (!plan) return <div className="spinner" />

  const series = plan.series
    .filter((s) => s.missing > 0)
    .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }))

  return (
    <>
      <div className="list-head"><span className="list-title">Scanlation versions</span></div>

      <div className="sv-intro">
        Other groups' releases of chapters you already have. Nothing downloads until you pick a series.
      </div>

      <div className="sv-tot">
        <div><b>{plan.totalMissing}</b> version{plan.totalMissing === 1 ? '' : 's'} missing</div>
        <div className="sv-tot-sub">across {series.length} series · about {fmtBytes(plan.totalEstBytes)} if you fetched them all</div>
      </div>

      {queued && (
        <div className="sv-queued">
          Queued {queued.n} version{queued.n === 1 ? '' : 's'} for “{queued.title}”.
          <button className="dl-link" onClick={() => nav('/downloads')}>View downloads →</button>
        </div>
      )}

      {series.length === 0 ? (
        <div className="center-msg">Nothing missing — you have every version your sources list.</div>
      ) : (
        <div className="sv-list">{series.map((s) => (
          <div className="sv-row" key={s.title}>
            <button className="sv-head" onClick={() => expand(s.title)}>
              <div className="sv-name">{s.title}</div>
              <div className="sv-nums">
                <span className="sv-missing">+{s.missing}</span>
                <span className="sv-have">{s.versionsOnDisk}/{s.versionsAtSource} on disk</span>
                <span className="sv-size">{fmtBytes(s.estBytes)}</span>
                <span className="sv-caret">{open === s.title ? '▾' : '▸'}</span>
              </div>
            </button>

            {open === s.title && (
              <div className="sv-detail">
                {!detail ? <div className="spinner" /> : (
                  <>
                    <div className="sv-chaps">
                      {detail.chapters.map((c) => (
                        <div className="sv-chap" key={c.number}>
                          <span className="sv-ch-no">Ch. {c.number}</span>
                          <span className="sv-ch-have">{c.have.length ? c.have.join(', ') : '—'}</span>
                          <span className="sv-ch-miss">+ {c.missing.join(', ')}</span>
                        </div>
                      ))}
                    </div>
                    <div className="sv-act">
                      {started[s.title] ? (
                        <div className="sv-started">
                          ✓ Queued {started[s.title]} version{started[s.title] === 1 ? '' : 's'} — downloading now.
                          <button className="dl-link" onClick={() => nav('/downloads')}>View →</button>
                        </div>
                      ) : (
                        <button className="btn primary" disabled={!!busy} onClick={() => start(s.title)}>
                          {busy === s.title ? 'Queuing…' : `Download ${s.missing} missing version${s.missing === 1 ? '' : 's'} (~${fmtBytes(s.estBytes)})`}
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            )}
          </div>
        ))}</div>
      )}
    </>
  )
}
