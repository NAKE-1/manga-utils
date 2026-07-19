import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type LibraryEntry, type WebhookResult, type NotifyConfig } from '../api'
import { IconArrowLeft } from '../components/icons'

const KINDS: { id: string; label: string }[] = [
  { id: 'newchapters', label: 'New chapters' },
  { id: 'poster', label: 'Poster (big image)' },
  { id: 'download', label: 'Download complete' },
  { id: 'sourcedown', label: 'Source down' },
]
const EVENTS: { key: keyof NotifyConfig; label: string }[] = [
  { key: 'libraryCheck', label: 'Library check ran' },
  { key: 'newChapters', label: 'New chapters found' },
  { key: 'downloadStart', label: 'Download started' },
  { key: 'downloadComplete', label: 'Download complete' },
  { key: 'downloadFailed', label: 'Download failed' },
  { key: 'sourceHealth', label: 'Source down / recovered' },
  { key: 'serviceHealth', label: 'Server online · Cloudflare bypass down / recovered' },
]

export default function Webhooks() {
  const nav = useNavigate()
  const [url, setUrl] = useState('')
  const [saved, setSaved] = useState('')
  const [notify, setNotify] = useState<NotifyConfig | null>(null)
  const [lib, setLib] = useState<LibraryEntry[]>([])
  const [pick, setPick] = useState('')
  const [busy, setBusy] = useState('')
  const [result, setResult] = useState<{ kind: string; r: WebhookResult } | null>(null)

  useEffect(() => {
    api.getSettings().then((s) => { setUrl(s.discordWebhookUrl || ''); setSaved(s.discordWebhookUrl || ''); setNotify(s.notify) }).catch(() => {})
    api.library().then((l) => { setLib(l); if (l[0]) setPick(l[0].sourceId + '|' + l[0].url) }).catch(() => {})
  }, [])

  async function save() {
    const s = await api.saveSettings({ discordWebhookUrl: url.trim() }).catch(() => null)
    if (s) setSaved(s.discordWebhookUrl || '')
  }
  async function patchNotify(part: Partial<NotifyConfig>) {
    if (!notify) return
    const n = { ...notify, ...part }
    setNotify(n)
    api.saveSettings({ notify: n }).catch(() => {})
  }

  function show(kind: string, r: WebhookResult) { setResult({ kind, r }); setBusy('') }

  async function ping() { setBusy('ping'); show('ping', await api.webhookPing().catch(() => ({ ok: false, status: 0, rateLimited: false, error: 'request failed' }))) }
  async function sample(kind: string) {
    setBusy(kind)
    const i = pick.indexOf('|')
    const source = i > 0 ? pick.slice(0, i) : ''
    const mangaUrl = i > 0 ? pick.slice(i + 1) : ''
    show(kind, await api.webhookSample(source, mangaUrl, kind).catch(() => ({ ok: false, status: 0, rateLimited: false, error: 'request failed' })))
  }

  const configured = saved.trim().length > 0

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Webhook options</span>
      </div>

      <div className="wh-card">
        <div className="set-row-label">Webhook URL</div>
        <div className="set-hint">Paste a Discord channel webhook (Server Settings → Integrations → Webhooks). Kept private on your server.</div>
        <input className="set-input" type="password" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://discord.com/api/webhooks/…" spellCheck={false} autoCapitalize="off" autoCorrect="off" />
        <div className="set-actions">
          <button className="btn primary" disabled={url.trim() === saved.trim()} onClick={save}>{url.trim() === saved.trim() ? 'Saved' : 'Save'}</button>
          {configured && <span className="wh-ok">● configured</span>}
        </div>
      </div>

      {notify && (
        <div className="wh-card">
          <button className="set-toggle" onClick={() => patchNotify({ enabled: !notify.enabled })}>
            <div>
              <div className="set-row-label">Webhook enabled</div>
              <div className="set-hint">Turn the whole webhook on or off — stops all sending but keeps your URL and event choices.</div>
            </div>
            <span className={'switch' + (notify.enabled ? ' on' : '')}><span className="knob" /></span>
          </button>
          {notify.enabled && (
            <>
              <div className="wh-events">
                {EVENTS.map((e) => (
                  <button key={e.key} className={'wh-ev' + (notify[e.key] ? ' on' : '')} onClick={() => patchNotify({ [e.key]: !notify[e.key] } as Partial<NotifyConfig>)}>
                    <span className="wh-ev-dot" />{e.label}
                  </button>
                ))}
              </div>
              <div className="wh-row">
                <span className="wh-lbl">Cover</span>
                <div className="wh-seg">
                  <button className={notify.coverStyle !== 'poster' ? 'on' : ''} onClick={() => patchNotify({ coverStyle: 'thumbnail' })}>Thumbnail</button>
                  <button className={notify.coverStyle === 'poster' ? 'on' : ''} onClick={() => patchNotify({ coverStyle: 'poster' })}>Poster</button>
                </div>
              </div>
            </>
          )}
        </div>
      )}

      <div className="wh-card">
        <div className="set-row-label">Send a test</div>
        <div className="set-hint">Fires a real message so you can dial in the embed. Uses a library manga for the cover, title &amp; source.</div>
        <div className="wh-row">
          <span className="wh-lbl">Manga</span>
          <select className="set-select" value={pick} onChange={(e) => setPick(e.target.value)}>
            {lib.length === 0 && <option value="">— none in library —</option>}
            {lib.map((m) => <option key={m.sourceId + '|' + m.url} value={m.sourceId + '|' + m.url}>{m.title}</option>)}
          </select>
        </div>
        <div className="wh-btns">
          <button className="btn" disabled={!configured || !!busy} onClick={ping}>{busy === 'ping' ? '…' : 'Ping'}</button>
          {KINDS.map((k) => (
            <button key={k.id} className="btn" disabled={!configured || !!busy} onClick={() => sample(k.id)}>{busy === k.id ? '…' : k.label}</button>
          ))}
        </div>
        {!configured && <div className="set-hint">Save a webhook URL first.</div>}
        {result && (
          <div className={'wh-result' + (result.r.ok ? ' ok' : ' err')}>
            {result.r.ok
              ? `✓ Sent (${result.kind}) — HTTP ${result.r.status}`
              : result.r.rateLimited
                ? `⏳ Rate limited — retry after ${result.r.retryAfter ?? '?'}s`
                : `✕ Failed (${result.kind}) — ${result.r.error || `HTTP ${result.r.status}`}`}
          </div>
        )}
      </div>

      <div className="wh-note">
        <b>Format:</b> title (clickable) · info (chapters) → description · source → footer + violet accent ·
        cover → ~80px top-right thumbnail. The cover uploads as a file attachment (Discord can't reach your
        tailnet). Size options: icon (tiny, left) · thumbnail (right, current) · full image (large, below).
        <br /><b>Limits (for the real thing):</b> ≤10 embeds/message, ≤6000 chars across embeds, 8&nbsp;MB
        total, 5 requests/2s per webhook, 30 messages/min per channel — a big update will batch 10 manga
        per message and pace itself.
      </div>
    </div>
  )
}
