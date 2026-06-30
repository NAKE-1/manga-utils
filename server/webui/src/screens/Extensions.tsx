import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ExtInstalled, ExtAvailable } from '../api'
import { IconArrowLeft } from '../components/icons'

type Tab = 'installed' | 'browse' | 'repos'
const cleanName = (n: string) => n.replace(/^Tachiyomi:\s*/i, '')

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
  const [repoInput, setRepoInput] = useState('')
  const [repoMsg, setRepoMsg] = useState('')

  const loadInstalled = () => api.extensions().then(setInstalled).catch(() => {})
  const loadBrowse = () => { setBrowseLoading(true); api.extAvailable(q).then(setAvail).catch(() => setAvail([])).finally(() => setBrowseLoading(false)) }

  useEffect(() => { loadInstalled(); api.repos().then(setRepos).catch(() => {}) }, [])
  useEffect(() => { if (tab === 'browse') loadBrowse() }, [tab, q]) // eslint-disable-line react-hooks/exhaustive-deps

  async function checkUpdates() {
    setChecking(true)
    setUpdates(new Set(await api.extCheckUpdates().catch(() => [])))
    setChecking(false)
  }
  async function install(pkg: string, label: string) {
    setBusy((b) => ({ ...b, [pkg]: label }))
    await api.extInstall(pkg).catch(() => {})
    setBusy((b) => { const n = { ...b }; delete n[pkg]; return n })
    setUpdates((u) => { const n = new Set(u); n.delete(pkg); return n })
    loadInstalled(); if (tab === 'browse') loadBrowse()
  }
  async function uninstall(pkg: string) {
    setBusy((b) => ({ ...b, [pkg]: 'Removing…' }))
    await api.extUninstall(pkg)
    setBusy((b) => { const n = { ...b }; delete n[pkg]; return n })
    loadInstalled(); if (tab === 'browse') loadBrowse()
  }
  async function addRepo() {
    const url = repoInput.trim(); if (!url) return
    setRepoMsg('')
    try { setRepos(await api.addRepo(url)); setRepoInput('') } catch (e) { setRepoMsg(e instanceof Error ? e.message : 'Failed') }
  }
  async function removeRepo(url: string) { setRepos(await api.removeRepo(url).catch(() => repos)) }

  return (
    <div className="ext-page">
      <div className="ext-top">
        <button className="iconbtn" onClick={() => nav('/settings')} aria-label="Back"><IconArrowLeft /></button>
        <span className="ext-title">Extensions &amp; repositories</span>
      </div>

      <div className="seg" style={{ margin: '0 16px 12px' }}>
        {(['installed', 'browse', 'repos'] as Tab[]).map((t) => (
          <button key={t} className={'seg-btn' + (tab === t ? ' on' : '')} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>
        ))}
      </div>

      {tab === 'installed' && (
        <>
          <div className="ext-actions"><button className="btn" disabled={checking} onClick={checkUpdates}>{checking ? 'Checking…' : 'Check for updates'}</button></div>
          {installed.map((e) => (
            <div className="ext-row" key={e.pkg}>
              <ExtIcon pkg={e.pkg} />
              <div className="ext-info">
                <div className="ext-name">{cleanName(e.name)}{e.nsfw && <span className="src-18">18+</span>}</div>
                <div className="ext-sub">v{e.version} · {e.lang.toUpperCase()} · {e.sources} source{e.sources === 1 ? '' : 's'}</div>
              </div>
              {updates.has(e.pkg) && <button className="btn primary sm" disabled={!!busy[e.pkg]} onClick={() => install(e.pkg, 'Updating…')}>{busy[e.pkg] || 'Update'}</button>}
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
                <div className="ext-sub">v{e.version} · {e.lang.toUpperCase()}</div>
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
          {repos.map((url) => (
            <div className="ext-row" key={url}>
              <div className="ext-info"><div className="ext-sub repo-url">{url}</div></div>
              <button className="btn sm danger" onClick={() => removeRepo(url)}>Remove</button>
            </div>
          ))}
          {repos.length === 0 && <div className="center-msg">No repositories. Add one to install extensions.</div>}
        </>
      )}
    </div>
  )
}
