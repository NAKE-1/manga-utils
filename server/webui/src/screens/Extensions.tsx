import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ExtInstalled, ExtAvailable } from '../api'
import { IconArrowLeft, IconJetBrains } from '../components/icons'
import { ConfirmDialog, ConfirmSpec } from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

type Tab = 'installed' | 'browse' | 'repos'
const cleanName = (n: string) => n.replace(/^Tachiyomi:\s*/i, '')
function repoLabel(url: string): string {
  try { const u = new URL(url); return u.hostname === 'raw.githubusercontent.com' ? (u.pathname.split('/').filter(Boolean)[0] || u.hostname) : u.hostname } catch { return url }
}

function ExtIcon({ pkg }: { pkg: string }) {
  const [failed, setFailed] = useState(false)
  if (failed) return <span className="ext-icon ph" />
  return <img className="ext-icon" src={`/img/ext-icon?pkg=${pkg}`} alt="" loading="lazy" onError={() => setFailed(true)} />
}

export function Extensions() {
  const nav = useNavigate()
  const [tab, setTab] = useState<Tab>('installed')
  const [installed, setInstalled] = useState<ExtInstalled[]>([])
  const [updates, setUpdates] = useState<Set<string>>(new Set())
  const [checking, setChecking] = useState(false)
  const [busy, setBusy] = useState<Record<string, string>>({})
  const [q, setQ] = useState('')
  const [avail, setAvail] = useState<ExtAvailable[]>([])
  const [browseLoading, setBrowseLoading] = useState(false)
  const [repos, setRepos] = useState<string[]>([])
  const [repoStats, setRepoStats] = useState<Record<string, { extensions: number; sources: number }>>({})
  const [repoInput, setRepoInput] = useState('')
  const [repoMsg, setRepoMsg] = useState('')
  const [confirm, setConfirm] = useState<ConfirmSpec | null>(null)

  const loadInstalled = () => api.extensions().then(setInstalled).catch(() => {})
  const loadBrowse = () => { setBrowseLoading(true); api.extAvailable(q).then(setAvail).catch(() => setAvail([])).finally(() => setBrowseLoading(false)) }

  async function checkUpdates(manual = false) {
    setChecking(true)
    const found = await api.extCheckUpdates().catch(() => [] as string[])
    setUpdates(new Set(found))
    setChecking(false)
    if (manual) {
      toast(found.length ? `${found.length} update${found.length === 1 ? '' : 's'} available` : 'All extensions up to date',
        found.length ? 'info' : 'success')
    }
  }

  useEffect(() => { loadInstalled(); api.repos().then(setRepos).catch(() => {}); checkUpdates() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (tab === 'browse') loadBrowse() }, [tab, q]) // eslint-disable-line react-hooks/exhaustive-deps
  // Counts need every index fetched, so only pay for it when the Repos tab is actually open.
  useEffect(() => {
    if (tab !== 'repos') return
    api.repoStats()
      .then((s) => setRepoStats(Object.fromEntries(s.map((r) => [r.url, { extensions: r.extensions, sources: r.sources }]))))
      .catch(() => {})
  }, [tab, repos])

  async function install(pkg: string, label: string) {
    setBusy((b) => ({ ...b, [pkg]: label }))
    try {
      const r = await api.extInstall(pkg)
      toast(`${label.startsWith('Updat') ? 'Updated' : 'Installed'} ${cleanName(r.name || pkg)}${r.sources ? ` · ${r.sources} source${r.sources === 1 ? '' : 's'}` : ''}`, 'success')
      setUpdates((u) => { const n = new Set(u); n.delete(pkg); return n }) // only clear the "update available" badge on real success
    } catch (e) {
      toast(`${label.startsWith('Updat') ? 'Update' : 'Install'} failed: ${e instanceof Error ? e.message : 'error'}`, 'error')
    } finally {
      setBusy((b) => { const n = { ...b }; delete n[pkg]; return n })
    }
    loadInstalled(); if (tab === 'browse') loadBrowse()
  }
  async function updateAll() {
    const pkgs = Array.from(updates)
    if (!pkgs.length) return
    toast(`Updating ${pkgs.length} extension${pkgs.length === 1 ? '' : 's'}…`, 'info')
    for (const pkg of pkgs) await install(pkg, 'Updating…')
    toast('Updates complete', 'success')
  }
  async function uninstall(pkg: string) {
    setBusy((b) => ({ ...b, [pkg]: 'Removing…' }))
    await api.extUninstall(pkg)
    setBusy((b) => { const n = { ...b }; delete n[pkg]; return n })
    loadInstalled(); if (tab === 'browse') loadBrowse()
  }
  // Unload frees the extension's .jar (so a Windows update won't hit a file lock); Load re-enables it.
  async function toggleLoad(e: ExtInstalled) {
    setBusy((b) => ({ ...b, [e.pkg]: e.loaded ? 'Unloading…' : 'Loading…' }))
    try { if (e.loaded) await api.extUnload(e.pkg); else await api.extLoad(e.pkg) } catch { /* ignore */ }
    setBusy((b) => { const n = { ...b }; delete n[e.pkg]; return n })
    loadInstalled()
  }
  async function addRepo() {
    const url = repoInput.trim(); if (!url) return
    setRepoMsg('')
    try { setRepos(await api.addRepo(url)); setRepoInput(''); loadInstalled() } catch (e) { setRepoMsg(e instanceof Error ? e.message : 'Failed') }
  }
  function askRemoveRepo(url: string) {
    const assoc = installed.filter((e) => e.repo && e.repo === repoLabel(url))
    setConfirm({
      title: 'Remove repository?',
      message: assoc.length
        ? `${assoc.length} installed extension${assoc.length === 1 ? '' : 's'} came from this repo (${assoc.map((e) => cleanName(e.name)).join(', ')}). They stay installed but will no longer receive updates. Remove the repo?`
        : 'This repository will be removed. You can add it again any time.',
      confirmLabel: 'Remove repo',
      danger: true,
      onConfirm: async () => { setConfirm(null); setRepos(await api.removeRepo(url).catch(() => repos)); loadInstalled() },
      onCancel: () => setConfirm(null),
    })
  }

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Extensions &amp; repositories</span>
      </div>

      <div className="seg" style={{ margin: '0 16px 12px' }}>
        {(['installed', 'browse', 'repos'] as Tab[]).map((t) => (
          <button key={t} className={'seg-btn' + (tab === t ? ' on' : '')} onClick={() => setTab(t)}>
            {t[0].toUpperCase() + t.slice(1)}{t === 'installed' && updates.size > 0 ? ` (${updates.size})` : ''}
          </button>
        ))}
      </div>

      {tab === 'installed' && (
        <>
          {updates.size > 0 && (
            <div className="ext-banner">
              <span>{updates.size} update{updates.size === 1 ? '' : 's'} available</span>
              <button className="btn primary sm" onClick={updateAll}>Update all</button>
            </div>
          )}
          <div className="ext-actions"><button className="btn" disabled={checking} onClick={() => checkUpdates(true)}>{checking ? 'Checking…' : 'Check for updates'}</button></div>
          {installed.map((e) => (
            <div className="ext-row" key={e.pkg}>
              <ExtIcon pkg={e.pkg} />
              <div className="ext-info">
                <div className="ext-name">{cleanName(e.name)}{e.usesWebView && <IconJetBrains className="src-wv" />}{e.nsfw && <span className="src-18">18+</span>}{updates.has(e.pkg) && <span className="ext-badge">UPDATE</span>}{!e.loaded && <span className="ext-badge" style={{ background: 'var(--muted-2)' }}>UNLOADED</span>}</div>
                <div className="ext-sub">v{e.version} · {e.lang.toUpperCase()} · {e.sources} source{e.sources === 1 ? '' : 's'}{e.repo ? ` · ${e.repo}` : ''}</div>
              </div>
              {updates.has(e.pkg)
                ? <button className="btn primary sm" disabled={!!busy[e.pkg]} onClick={() => install(e.pkg, 'Updating…')}>{busy[e.pkg] || 'Update'}</button>
                : <button className="btn sm" disabled={!!busy[e.pkg]} title="Re-download this extension at its current version — repairs a bad install" onClick={() => install(e.pkg, 'Reinstalling…')}>{busy[e.pkg] || 'Reinstall'}</button>}
              <button className="btn sm" disabled={!!busy[e.pkg]} title={e.loaded ? 'Free the .jar so it can be updated on Windows' : 'Re-enable this extension'} onClick={() => toggleLoad(e)}>{busy[e.pkg] || (e.loaded ? 'Unload' : 'Load')}</button>
              <button className="btn sm danger" disabled={!!busy[e.pkg]} onClick={() => uninstall(e.pkg)}>{busy[e.pkg] === 'Removing…' ? '…' : 'Remove'}</button>
            </div>
          ))}
          {installed.length === 0 && <div className="center-msg">No extensions installed.</div>}
        </>
      )}

      {tab === 'browse' && (
        <>
          <div className="ext-search"><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search extensions…" enterKeyHint="search" /></div>
          {browseLoading ? <div className="spinner" /> : avail.length === 0 ? <div className="center-msg">No extensions found.</div> : avail.map((e) => (
            <div className="ext-row" key={e.pkg}>
              <ExtIcon pkg={e.pkg} />
              <div className="ext-info">
                <div className="ext-name">{cleanName(e.name)}{e.nsfw && <span className="src-18">18+</span>}</div>
                <div className="ext-sub">v{e.version} · {e.lang.toUpperCase()}{e.repo ? ` · ${e.repo}` : ''}</div>
              </div>
              {e.installed && !e.hasUpdate
                ? <span className="ext-installed">Installed</span>
                : <button className="btn primary sm" disabled={!!busy[e.pkg]} onClick={() => install(e.pkg, e.hasUpdate ? 'Updating…' : 'Installing…')}>{busy[e.pkg] || (e.hasUpdate ? 'Update' : 'Install')}</button>}
            </div>
          ))}
        </>
      )}

      {tab === 'repos' && (
        <>
          <div className="ext-search">
            <input value={repoInput} onChange={(e) => setRepoInput(e.target.value)} placeholder="Repo index URL (…/index.min.json)" spellCheck={false} autoCapitalize="off" autoCorrect="off" onKeyDown={(e) => { if (e.key === 'Enter') addRepo() }} />
            <button className="btn primary" onClick={addRepo}>Add</button>
          </div>
          {repoMsg && <div className="set-msg err" style={{ padding: '0 16px 8px' }}>{repoMsg}</div>}
          {repos.length > 0 && (() => {
            const tot = repos.reduce((a, u) => {
              const s = repoStats[u]; return s ? { e: a.e + s.extensions, s: a.s + s.sources } : a
            }, { e: 0, s: 0 })
            return tot.s > 0 ? (
              <div className="repo-total">
                <b>{tot.s.toLocaleString()}</b> sources<span className="sep">·</span>
                <b>{tot.e.toLocaleString()}</b> extensions
                <span className="sep">·</span>{repos.length} repo{repos.length === 1 ? '' : 's'}
              </div>
            ) : null
          })()}
          {repos.map((url) => {
            const count = installed.filter((e) => e.repo === repoLabel(url)).length
            const st = repoStats[url]
            return (
              <div className="ext-row" key={url}>
                <div className="ext-info">
                  <div className="ext-name">{repoLabel(url)}</div>
                  <div className="ext-sub repo-url">{url}</div>
                  <div className="ext-sub">
                    {st
                      ? `${st.sources.toLocaleString()} source${st.sources === 1 ? '' : 's'} · ${st.extensions.toLocaleString()} extension${st.extensions === 1 ? '' : 's'}`
                      : 'Counting…'}
                    {count > 0 && ` · ${count} installed`}
                  </div>
                </div>
                <button className="btn sm danger" onClick={() => askRemoveRepo(url)}>Remove</button>
              </div>
            )
          })}
          {repos.length === 0 && <div className="center-msg">No repositories. Add one to install extensions.</div>}
        </>
      )}

      {confirm && <ConfirmDialog spec={confirm} />}
    </div>
  )
}
