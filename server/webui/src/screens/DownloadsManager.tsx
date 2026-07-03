import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ManagedSeries, ManagedChapter } from '../api'
import { IconArrowLeft, IconChevronDown, IconDownload } from '../components/icons'
import { ConfirmDialog, ConfirmSpec } from '../components/ConfirmDialog'

const fmtSize = (b: number) => (b >= 1 << 30 ? `${(b / (1 << 30)).toFixed(1)} GB` : b >= 1 << 20 ? `${(b / (1 << 20)).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`)

export function DownloadsManager() {
  const nav = useNavigate()
  const [series, setSeries] = useState<ManagedSeries[] | null>(null)
  const [open, setOpen] = useState<string | null>(null)
  const [chapters, setChapters] = useState<Record<string, ManagedChapter[]>>({})
  const [confirm, setConfirm] = useState<ConfirmSpec | null>(null)
  const [msg, setMsg] = useState('')

  const load = () => api.manageDownloads().then(setSeries).catch(() => setSeries([]))
  useEffect(() => { load() }, [])

  function toggle(title: string) {
    if (open === title) { setOpen(null); return }
    setOpen(title)
    if (!chapters[title]) api.manageChapters(title).then((c) => setChapters((m) => ({ ...m, [title]: c }))).catch(() => {})
  }
  function flash(t: string) { setMsg(t); setTimeout(() => setMsg(''), 2500) }

  function delChapter(title: string, ch: ManagedChapter) {
    setConfirm({
      title: 'Delete chapter?', message: `Delete "${ch.name}" from ${title}? You can re-download it later.`,
      confirmLabel: 'Delete', danger: true, onCancel: () => setConfirm(null),
      onConfirm: async () => {
        setConfirm(null)
        await api.deleteDownloadChapter(title, ch.name).catch(() => {})
        const c = await api.manageChapters(title).catch(() => [])
        setChapters((m) => ({ ...m, [title]: c }))
        load()
      },
    })
  }
  function delSeries(s: ManagedSeries) {
    setConfirm({
      title: 'Delete all downloads?', message: `Delete all ${s.chapters} downloaded chapter${s.chapters === 1 ? '' : 's'} of ${s.title} (${fmtSize(s.bytes)})?`,
      confirmLabel: 'Delete all', danger: true, onCancel: () => setConfirm(null),
      onConfirm: async () => { setConfirm(null); await api.deleteDownloads(s.title).catch(() => {}); setOpen(null); load() },
    })
  }
  async function markUnread(s: ManagedSeries) {
    const r = await api.markSeriesUnread(s.title).catch(() => ({ count: 0 }))
    flash(r.count > 0 ? `Marked ${s.title} unread` : 'Not in your library — can’t mark unread')
  }
  async function repair(s: ManagedSeries) {
    flash(`Repairing ${s.title}…`)
    const r = await api.repairDownloads(s.title).catch(() => ({ count: 0 }))
    if (r.count > 0) flash(`Re-downloading ${r.count} chapter${r.count === 1 ? '' : 's'} — see Downloads`)
    else if (r.count < 0) flash('Not in your library — can’t map chapters to re-download')
    else flash('Nothing to repair')
    const c = await api.manageChapters(s.title).catch(() => [])
    setChapters((m) => ({ ...m, [s.title]: c }))
    load()
  }
  function delIncomplete(s: ManagedSeries) {
    setConfirm({
      title: 'Delete incomplete chapters?', message: `Delete ${s.incomplete} interrupted/partial chapter${s.incomplete === 1 ? '' : 's'} of ${s.title} so you can re-download them?`,
      confirmLabel: 'Delete incomplete', danger: true, onCancel: () => setConfirm(null),
      onConfirm: async () => {
        setConfirm(null)
        await api.deleteIncomplete(s.title).catch(() => {})
        const c = await api.manageChapters(s.title).catch(() => [])
        setChapters((m) => ({ ...m, [s.title]: c }))
        load()
      },
    })
  }

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Downloaded content</span>
      </div>

      {series === null ? <div className="spinner" /> : series.length === 0 ? (
        <div className="center-msg">Nothing downloaded yet.</div>
      ) : (
        <>
          {(() => {
            const totalBytes = series.reduce((a, s) => a + s.bytes, 0)
            const totalCh = series.reduce((a, s) => a + s.chapters, 0)
            const biggest = series.reduce((m, s) => (s.bytes > (m?.bytes ?? -1) ? s : m), null as ManagedSeries | null)
            return (
              <div className="dm-overview">
                <div className="dm-stat"><span className="dm-stat-n">{fmtSize(totalBytes)}</span><span className="dm-stat-l">on disk</span></div>
                <div className="dm-stat"><span className="dm-stat-n">{series.length}</span><span className="dm-stat-l">series</span></div>
                <div className="dm-stat"><span className="dm-stat-n">{totalCh}</span><span className="dm-stat-l">chapters</span></div>
                {biggest && <div className="dm-stat wide"><span className="dm-stat-n">{biggest.title}</span><span className="dm-stat-l">largest · {fmtSize(biggest.bytes)}</span></div>}
              </div>
            )
          })()}
          {series.map((s) => (
            <div key={s.title} className="dm-series">
              <button className="dm-row" onClick={() => toggle(s.title)}>
                <IconChevronDown className={'dm-caret' + (open === s.title ? ' open' : '')} />
                <div className="ext-info">
                  <div className="ext-name">{s.title}{s.incomplete > 0 && <span className="dm-badge">{s.incomplete} incomplete</span>}</div>
                  <div className="ext-sub">{s.chapters} chapter{s.chapters === 1 ? '' : 's'} · {fmtSize(s.bytes)}</div>
                </div>
                <IconDownload className="dm-dl" />
              </button>
              {open === s.title && (
                <div className="dm-body">
                  <div className="dm-actions">
                    <button className="btn sm" onClick={() => markUnread(s)}>Mark unread</button>
                    {s.incomplete > 0 && <button className="btn sm" onClick={() => repair(s)}>Repair ({s.incomplete})</button>}
                    {s.incomplete > 0 && <button className="btn sm danger" onClick={() => delIncomplete(s)}>Delete incomplete</button>}
                    <button className="btn sm danger" onClick={() => delSeries(s)}>Delete all</button>
                  </div>
                  {[...(chapters[s.title] ?? [])].sort((a, b) => Number(a.complete) - Number(b.complete)).map((ch) => (
                    <div className={'dm-chapter' + (ch.complete ? '' : ' bad')} key={ch.name}>
                      <div className="ext-info">
                        <div className="dm-ch-name">{ch.name}{!ch.complete && <span className="dm-badge red">INCOMPLETE</span>}</div>
                        <div className="ext-sub">{ch.pages} page{ch.pages === 1 ? '' : 's'} · {fmtSize(ch.bytes)}{ch.cbz ? ' · CBZ' : ''}</div>
                      </div>
                      <button className="btn sm danger" onClick={() => delChapter(s.title, ch)}>Delete</button>
                    </div>
                  ))}
                  {chapters[s.title] && chapters[s.title].length === 0 && <div className="center-msg">No chapters.</div>}
                </div>
              )}
            </div>
          ))}
        </>
      )}

      {msg && <div className="dl-toast">{msg}</div>}
      {confirm && <ConfirmDialog spec={confirm} />}
    </div>
  )
}
