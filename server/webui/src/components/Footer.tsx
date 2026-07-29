import { useEffect, useState } from 'react'
import { api, VersionInfo } from '../api'

// Short, honest boilerplate for a self-hosted personal reader — not a commercial site's legalese.
const DOCS: Record<string, { title: string; body: string[] }> = {
  about: {
    title: 'About',
    body: [
      'manga-utils is a self-hosted, phone-first manga reader and downloader. It runs on your own server and is reached over your private network (e.g. Tailscale).',
      'It hosts no content of its own. Chapters and metadata are fetched from the third-party sources and extensions you choose to install and enable.',
    ],
  },
  privacy: {
    title: 'Privacy',
    body: [
      'Everything runs on your own machine. There is no analytics, no telemetry, and no account — nothing about your library or reading leaves your server.',
      'The only outbound traffic is the requests this app makes to the sources/extensions you enable (and, if configured, FlareSolverr). Your reading history, positions, and library are stored locally in the app data folder.',
    ],
  },
  terms: {
    title: 'Terms & License',
    body: [
      'Licensed under the Mozilla Public License 2.0. The software is provided “as is”, without warranty of any kind.',
      'You are responsible for how you use it and for the sources you configure. Respect the rights of content creators and your local laws.',
    ],
  },
  support: {
    title: 'Support',
    body: [
      'Something broken? Check Settings → Logs and the source Health dashboard first — most issues are a source being down, rate-limited, or behind Cloudflare.',
      'Dev mode (triple-tap the Settings title) unlocks diagnostics and a data-migration tool for moving between machines.',
    ],
  },
}

function DocModal({ id, onClose }: { id: string; onClose: () => void }) {
  const d = DOCS[id]
  if (!d) return null
  return (
    <div className="doc-overlay" onClick={onClose}>
      <div className="doc" onClick={(e) => e.stopPropagation()}>
        <div className="doc-head"><span className="doc-title">{d.title}</span><button className="doc-x" onClick={onClose} aria-label="Close">✕</button></div>
        {d.body.map((p, i) => <p key={i} className="doc-p">{p}</p>)}
      </div>
    </div>
  )
}

export function Footer() {
  const [v, setV] = useState<VersionInfo | null>(null)
  const [doc, setDoc] = useState<string | null>(null)
  useEffect(() => { api.version().then(setV).catch(() => {}) }, [])
  return (
    <footer className="site-footer">
      <div className="foot-links">
        <button className="foot-link" onClick={() => setDoc('about')}>About</button>
        <span className="foot-sep">·</span>
        <button className="foot-link" onClick={() => setDoc('privacy')}>Privacy</button>
        <span className="foot-sep">·</span>
        <button className="foot-link" onClick={() => setDoc('terms')}>Terms &amp; License</button>
        <span className="foot-sep">·</span>
        <button className="foot-link" onClick={() => setDoc('support')}>Support</button>
      </div>
      <div className="foot-meta">
        manga-utils{v ? ` · v${v.version}` : ''}{v?.commit ? ` (${v.commit.slice(0, 7)})` : ''}
      </div>
      <div className="foot-fine">Self-hosted software for personal use · MPL-2.0 · hosts no content of its own</div>
      {doc && <DocModal id={doc} onClose={() => setDoc(null)} />}
    </footer>
  )
}
