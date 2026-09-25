import { useState } from 'react'
import { WebviewModal } from './WebviewModal'

// Guided parity test for the WebView engine (dev). Walks you step-by-step through the checks that matter
// (render, scroll, click, autosolve, cookie/UA/token sharing, and — for the chrome sidecar — crash
// isolation), one instruction at a time, then prints a copyable pass/fail report. It's the same harness
// for BOTH engines: run it on jcef to get a baseline, then on chrome to confirm parity.

type Res = 'pass' | 'fail' | 'skip'
interface Step {
  key: string
  title: string
  instr: string
  action?: 'open' | 'openMf' // shows a button that opens the streamed WebView for this step
  chromeOnly?: boolean
}

const STEPS: Step[] = [
  { key: 'render', title: 'Open & render', action: 'open',
    instr: 'Tap "Open test WebView" below. The page should appear in the frame within a few seconds (after a brief "Starting…"). PASS if it renders; FAIL if it stays black or blank.' },
  { key: 'scroll', title: 'Scroll', action: 'open',
    instr: 'In the open WebView, drag up and down (tap "Open test WebView" if you closed it). PASS if the page scrolls and tracks your finger; FAIL if it does not move.' },
  { key: 'click', title: 'Tap / click', action: 'open',
    instr: 'Tap a link or button inside the frame (re-open it below if needed). PASS if it responds (highlights, navigates, opens something); FAIL if taps do nothing.' },
  { key: 'autosolve', title: 'Auto-solve (MangaFire captcha)', action: 'openMf',
    instr: 'Tap "Open MangaFire challenge" below, then press Auto-solve in the WebView top bar. PASS if it solves — the 🍪 cookie count rises and the page moves past the challenge. FAIL if it stays stuck.' },
  { key: 'cookies', title: 'Cookie / UA / token sharing',
    instr: 'Close the WebView, then run "MangaFire / solver self-test" further down this page. PASS if it goes GREEN — that proves the solved cf_clearance, User-Agent and WAF token reached the actual fetch path (a queued MangaFire download would now succeed without re-solving).' },
  { key: 'isolation', title: 'Crash isolation (sidecar)', chromeOnly: true,
    instr: 'On the server, run `docker kill browser` while a WebView is open. The manga-utils server must STAY UP — other tabs (Library, Downloads) keep working and the sidecar auto-restarts. PASS if the server survived; FAIL if the whole server went down.' },
]

export function WebviewTestWizard({ engine, pick }: { engine: string; pick: { url?: string; source?: string } | null }) {
  const steps = STEPS.filter((s) => !(s.chromeOnly && engine !== 'chrome'))
  const [running, setRunning] = useState(false)
  const [i, setI] = useState(0)
  const [res, setRes] = useState<Record<string, Res>>({})
  const [wv, setWv] = useState<{ url?: string; source?: string } | null>(null)
  const [done, setDone] = useState(false)

  const step = steps[i]

  function start() { setRunning(true); setI(0); setRes({}); setDone(false) }
  function mark(r: Res) {
    const nr = { ...res, [step.key]: r }
    setRes(nr)
    if (i + 1 < steps.length) setI(i + 1)
    else setDone(true)
  }
  function openFor(action: 'open' | 'openMf') {
    if (action === 'openMf') { setWv({ url: 'https://mangafire.to/@waf/challenge?return=%2F' }); return }
    if (pick?.url) setWv({ url: pick.url })
    else if (pick?.source) setWv({ source: pick.source })
    else setWv({ url: 'https://example.com/' })
  }

  const report = () => {
    const lines = steps.map((s) => `  ${res[s.key] === 'pass' ? '✅' : res[s.key] === 'fail' ? '❌' : '⏭️ '} ${s.title}: ${(res[s.key] || 'skip').toUpperCase()}`)
    const passes = steps.filter((s) => res[s.key] === 'pass').length
    const fails = steps.filter((s) => res[s.key] === 'fail').length
    return `WebView parity test — engine: ${engine}\n${new Date().toLocaleString()}\n\n${lines.join('\n')}\n\nResult: ${passes} passed, ${fails} failed of ${steps.length}.`
  }

  if (!running) {
    return (
      <div className="set-actions">
        <button className="btn primary" onClick={start}>Start guided test ({engine})</button>
      </div>
    )
  }

  if (done) {
    const txt = report()
    const fails = steps.filter((s) => res[s.key] === 'fail').length
    return (
      <div className="wtz-report">
        <div className="set-row-label">{fails === 0 ? '✅ All checks passed' : `❌ ${fails} check${fails === 1 ? '' : 's'} failed`}</div>
        <pre className="wtz-pre">{txt}</pre>
        <div className="set-actions">
          <button className="btn" onClick={() => { navigator.clipboard?.writeText(txt).catch(() => {}) }}>Copy report</button>
          <button className="btn" onClick={start}>Run again</button>
          <button className="btn" onClick={() => setRunning(false)}>Close</button>
        </div>
      </div>
    )
  }

  return (
    <div className="wtz">
      <div className="wtz-prog">Step {i + 1} / {steps.length} · engine: {engine}</div>
      <div className="set-row-label">{step.title}</div>
      <div className="set-hint">{step.instr}</div>
      {step.action && (
        <div className="set-actions">
          <button className="btn primary" onClick={() => openFor(step.action!)}>
            {step.action === 'openMf' ? 'Open MangaFire challenge' : 'Open test WebView'}
          </button>
        </div>
      )}
      <div className="set-actions wtz-verdict">
        <button className="btn" style={{ color: 'var(--good, #4ade80)' }} onClick={() => mark('pass')}>✅ Pass</button>
        <button className="btn danger" onClick={() => mark('fail')}>❌ Fail</button>
        <button className="btn" onClick={() => mark('skip')}>Skip</button>
      </div>
      {wv && <WebviewModal url={wv.url} source={wv.source} onClose={() => setWv(null)} />}
    </div>
  )
}
