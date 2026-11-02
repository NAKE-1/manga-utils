import { useEffect, useRef } from 'react'
import type { FleetDto, FleetWorker } from '../api'

// Live fleet map: each worker drawn at its Mullvad egress city, with harvest dots flowing home to the main
// node (central FL). Ported from the design mockup - imperative SVG build so the SMIL animations are cheap.
const LON0 = -125, LON1 = -66.5, LAT0 = 24, LAT1 = 49.7, W = 1020, H = 600, PAD = 14
const sy = (H - 2 * PAD) / (LAT1 - LAT0), sx = sy * Math.cos(38 * Math.PI / 180)
const offX = (W - (LON1 - LON0) * sx) / 2
const px = (lon: number) => offX + (lon - LON0) * sx
const py = (lat: number) => PAD + (LAT1 - lat) * sy

const LAND: [number, number][] = [
  [-124.7, 48.4], [-124.6, 47.2], [-124.1, 46.3], [-124.0, 45.0], [-124.1, 43.3], [-124.4, 42.0], [-124.2, 40.4], [-123.8, 39.4],
  [-122.9, 38.3], [-122.5, 37.8], [-122.0, 36.9], [-121.9, 36.6], [-120.9, 35.4], [-120.5, 34.5], [-119.7, 34.4], [-118.5, 34.0],
  [-117.3, 33.0], [-117.1, 32.5], [-115.0, 32.6], [-114.7, 32.7], [-113.0, 32.0], [-111.1, 31.3], [-108.2, 31.3], [-108.2, 31.8],
  [-106.5, 31.8], [-105.0, 30.6], [-103.0, 29.0], [-102.3, 29.9], [-101.4, 29.8], [-100.0, 28.2], [-99.5, 27.6], [-98.2, 26.2],
  [-97.4, 25.9], [-97.2, 27.3], [-96.4, 28.4], [-95.3, 28.9], [-94.0, 29.7], [-93.8, 29.7], [-92.3, 29.6], [-91.3, 29.3],
  [-90.0, 29.2], [-89.2, 29.0], [-89.4, 29.3], [-88.9, 30.4], [-87.5, 30.3], [-85.7, 29.9], [-84.3, 30.0], [-83.6, 29.9],
  [-82.8, 28.9], [-82.7, 27.8], [-82.1, 26.6], [-81.6, 25.9], [-81.1, 25.2], [-80.4, 25.2], [-80.1, 26.0], [-80.1, 26.8],
  [-80.5, 28.4], [-81.0, 29.2], [-81.4, 30.7], [-80.9, 32.0], [-79.9, 32.8], [-78.9, 33.9], [-77.0, 34.7], [-75.5, 35.2],
  [-75.9, 36.9], [-76.0, 37.9], [-75.1, 38.4], [-74.9, 38.9], [-74.4, 39.4], [-74.0, 40.5], [-72.0, 41.0], [-72.6, 41.3],
  [-71.4, 41.5], [-70.7, 41.6], [-70.0, 41.8], [-70.8, 42.3], [-70.6, 43.1], [-69.0, 44.0], [-67.8, 44.5], [-67.0, 44.8],
  [-67.1, 45.2], [-69.2, 47.4], [-71.5, 45.1], [-74.7, 45.0], [-76.8, 43.9], [-79.8, 43.6], [-82.5, 45.0], [-84.6, 46.2],
  [-88.4, 48.2], [-90.0, 48.3], [-95.0, 49.0], [-104.0, 49.0], [-123.0, 49.0], [-123.1, 48.2],
]
const WCOL = ['#7f93b3', '#b39a7f', '#7fb39a', '#a67fb3', '#8aa0d8', '#c08f9a', '#9ab37f', '#7fb3b0'] // per-worker line
const SCOL = ['#f6a623', '#ff5d8f', '#4db5ff', '#3ddc97', '#b19cff', '#ffd166', '#e879f9', '#ff8a5c']  // per-source dot
const CITY: Record<string, string> = {
  mia: 'Miami, FL', atl: 'Atlanta, GA', bham: 'Birmingham, AL', cae: 'Columbia, SC', chs: 'Charleston, SC',
  clt: 'Charlotte, NC', rag: 'Raleigh, NC', rdu: 'Raleigh, NC', bna: 'Nashville, TN', mem: 'Memphis, TN',
  msy: 'New Orleans, LA', lit: 'Little Rock, AR', hou: 'Houston, TX', dal: 'Dallas, TX', was: 'Ashburn, VA', nyc: 'New York, NY',
}
const srcColor = (name: string) => { let h = 0; for (const c of name) h = (h * 31 + c.charCodeAt(0)) >>> 0; return SCOL[h % SCOL.length] }
const abbrev = (name: string) => (name.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '?')

const NS = 'http://www.w3.org/2000/svg'
function el(t: string, a: Record<string, string | number> = {}) {
  const e = document.createElementNS(NS, t)
  for (const k in a) e.setAttribute(k, String(a[k]))
  return e
}
const path = (arr: [number, number][]) => 'M' + arr.map((p) => px(p[0]).toFixed(1) + ',' + py(p[1]).toFixed(1)).join('L') + 'Z'

export default function FlowMap({ data }: { data: FleetDto | null }) {
  const ref = useRef<SVGSVGElement | null>(null)
  const seen = useRef<Record<string, number>>({})   // per-worker max shipment seq already shown as a dot
  useEffect(() => { if (ref.current) build(ref.current, data, seen.current) }, [data])
  return <svg ref={ref} viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Fleet flow map" style={{ width: '100%', height: 'auto', display: 'block' }} />
}

type Pos = FleetWorker & { _x: number; _y: number }

function build(svg: SVGSVGElement, data: FleetDto | null, seen: Record<string, number>) {
  // Two layers: static (rebuilt each poll) + ships (one-shot shipment dots that persist and self-remove).
  let gS = svg.querySelector('g.fm-static') as SVGGElement | null
  let gP = svg.querySelector('g.fm-ships') as SVGGElement | null
  if (!gS || !gP) {
    while (svg.firstChild) svg.removeChild(svg.firstChild)
    gS = el('g', { class: 'fm-static' }) as SVGGElement
    gP = el('g', { class: 'fm-ships' }) as SVGGElement
    svg.appendChild(gS); svg.appendChild(gP)
  }
  while (gS.firstChild) gS.removeChild(gS.firstChild)
  gS.appendChild(el('path', { d: path(LAND), fill: 'var(--card-2, #17171d)', stroke: 'var(--border, #33333d)', 'stroke-width': 1.5, 'stroke-linejoin': 'round' }))
  const mainLat = data?.mainLat ?? 28.4, mainLon = data?.mainLon ?? -81.3
  const M = { x: px(mainLon), y: py(mainLat) }
  const compact = svg.clientWidth > 0 && svg.clientWidth < 440

  const ws = (data?.workers ?? []).filter((w) => w.online)
  const pts: Pos[] = ws.map((w) => ({ ...w, _x: px(w.lon ?? mainLon), _y: py(w.lat ?? mainLat) }))
  // decluster: off main, then off each other, so co-located nodes don't stack
  pts.forEach((w) => { let dx = w._x - M.x, dy = w._y - M.y, d = Math.hypot(dx, dy) || 1; if (d < 30) { const f = 30 - d + 6; w._x += dx / d * f; w._y += dy / d * f } })
  for (let a = 0; a < pts.length; a++) for (let b = a + 1; b < pts.length; b++) {
    let dx = pts[b]._x - pts[a]._x, dy = pts[b]._y - pts[a]._y, d = Math.hypot(dx, dy) || 1
    if (d < 26) { const f = (26 - d) / 2 + 3; dx /= d; dy /= d; pts[a]._x -= dx * f; pts[a]._y -= dy * f; pts[b]._x += dx * f; pts[b]._y += dy * f }
  }
  const names = new Set(pts.map((w) => w.name))
  for (const k of Object.keys(seen)) if (!names.has(k)) delete seen[k]
  pts.forEach((w, i) => {
    const bow = (i - (pts.length - 1) / 2) * 0.2
    const d = arc(w._x, w._y, M, bow)
    drawWorker(gS!, w, i, M, d, compact)
    spawnShips(gP!, w, d, seen)
  })
  drawMain(gS, M)

  if (ws.length === 0) {
    const t = el('text', { x: W / 2, y: H / 2, 'text-anchor': 'middle', fill: 'var(--muted, #8b8b93)', 'font-size': 15 })
    t.textContent = 'No online workers'
    gS.appendChild(t)
  }
}

// Fire one dot per NEW shipment (a seq we haven't shown). First time a worker appears we prime - no replay of
// history - so only chapters that ship from now on animate.
function spawnShips(g: SVGGElement, w: Pos, d: string, seen: Record<string, number>) {
  const maxSeq = w.ships.reduce((m, s) => Math.max(m, s.seq), 0)
  if (!(w.name in seen)) { seen[w.name] = maxSeq; return }
  let k = 0
  for (const s of w.ships) if (s.seq > seen[w.name]) spawnShip(g, d, srcColor(s.source), (k++) * 0.25)
  seen[w.name] = Math.max(seen[w.name], maxSeq)
}
function spawnShip(g: SVGGElement, d: string, color: string, delay: number) {
  const grp = el('g')
  grp.appendChild(el('circle', { r: 5.5, fill: color }))
  grp.appendChild(el('circle', { r: 2.4, fill: '#fff' }))
  const am = el('animateMotion', { dur: '2.2s', path: d, repeatCount: '1', fill: 'remove', begin: delay + 's' })
  grp.appendChild(am)
  const done = () => grp.remove()
  am.addEventListener('endEvent', done)
  g.appendChild(grp)
  setTimeout(done, 2600 + delay * 1000) // fallback if endEvent doesn't fire (e.g. backgrounded tab)
}

function arc(x: number, y: number, M: { x: number; y: number }, bow: number) {
  const midx = (x + M.x) / 2, midy = (y + M.y) / 2
  const dx = M.x - x, dy = M.y - y, len = Math.hypot(dx, dy) || 1, nx = -dy / len, ny = dx / len
  const cx = midx + nx * len * bow, cy = midy + ny * len * bow - 18
  return `M${x.toFixed(1)},${y.toFixed(1)} Q${cx.toFixed(1)},${cy.toFixed(1)} ${M.x.toFixed(1)},${M.y.toFixed(1)}`
}

function drawWorker(g: SVGGElement, w: Pos, i: number, M: { x: number; y: number }, d: string, compact: boolean) {
  const x = w._x, y = w._y, wc = WCOL[i % WCOL.length]
  g.appendChild(el('path', { d, fill: 'none', stroke: wc, 'stroke-width': 1.6, 'stroke-opacity': 0.4 }))
  // faint flowing underlay so the path reads even between shipments; the moving dots are the real shipments
  const stream = el('path', { d, fill: 'none', stroke: wc, 'stroke-width': 1.6, 'stroke-opacity': 0.22, 'stroke-dasharray': '2 12', 'stroke-linecap': 'round' })
  stream.appendChild(el('animate', { attributeName: 'stroke-dashoffset', from: '28', to: '0', dur: '1.6s', repeatCount: 'indefinite' }))
  g.appendChild(stream)
  // download pulse ring while anything is downloading (source -> exit; NOT travelling the line)
  if (w.inflight > 0) {
    const ring = el('circle', { cx: x, cy: y, r: 7, fill: 'none', stroke: wc, 'stroke-width': 1.5 })
    ring.appendChild(el('animate', { attributeName: 'r', values: '7;16;7', dur: '1.8s', repeatCount: 'indefinite' }))
    ring.appendChild(el('animate', { attributeName: 'stroke-opacity', values: '.75;0;.75', dur: '1.8s', repeatCount: 'indefinite' }))
    g.appendChild(ring)
  }
  g.appendChild(el('circle', { cx: x, cy: y, r: 10, fill: wc, 'fill-opacity': 0.18 }))
  g.appendChild(el('circle', { cx: x, cy: y, r: 6, fill: wc, stroke: 'var(--card, #141418)', 'stroke-width': 2 }))
  g.appendChild(el('circle', { cx: x, cy: y, r: 6, fill: 'none', stroke: 'rgba(255,255,255,.45)', 'stroke-width': 1 }))
  const right = x < M.x, ax = x + (right ? 14 : -14), anchor = right ? 'start' : 'end'
  if (w.inflight > 0) {
    const bx = x + (right ? -8 : 8)
    g.appendChild(el('circle', { cx: bx, cy: y - 8, r: 7, fill: wc, stroke: 'var(--card, #141418)', 'stroke-width': 1.5 }))
    const bt = el('text', { x: bx, y: y - 4.8, 'text-anchor': 'middle', fill: '#0a0a0c', 'font-size': 9.5, 'font-weight': 800 })
    bt.textContent = String(w.inflight); g.appendChild(bt)
  }
  const ty = y - 6
  const t1 = el('text', { x: ax, y: ty, 'text-anchor': anchor, fill: 'var(--text, #e8e8ec)', 'font-size': 13, 'font-weight': 700 })
  t1.textContent = w.name; g.appendChild(t1)
  const many = w.sources.length > 2
  if (compact && many) {
    const line = el('text', { x: ax, y: ty + 14, 'text-anchor': anchor, fill: 'var(--muted, #8b8b93)', 'font-size': 11, 'font-weight': 600 })
    line.textContent = w.sources.length + ' sources · ' + w.inflight + ' dl'; g.appendChild(line)
  } else {
    w.sources.forEach((s, k) => {
      const ry = ty + 14 + k * 14, sc = srcColor(s.name)
      const tileX = anchor === 'start' ? ax : ax - 9
      g.appendChild(el('rect', { x: tileX, y: ry - 8.5, width: 9, height: 9, rx: 2, fill: sc }))
      const gl = el('text', { x: tileX + 4.5, y: ry - 1.5, 'text-anchor': 'middle', fill: '#0a0a0c', 'font-size': 7, 'font-weight': 800 })
      gl.textContent = abbrev(s.name)[0]; g.appendChild(gl)
      const row = el('text', { x: anchor === 'start' ? ax + 14 : ax - 14, y: ry, 'text-anchor': anchor, fill: sc, 'font-size': 11, 'font-weight': 700 })
      row.textContent = abbrev(s.name); g.appendChild(row)
    })
    const cap = el('text', { x: ax, y: ty + 14 + w.sources.length * 14, 'text-anchor': anchor, fill: 'var(--muted, #8b8b93)', 'font-size': 10.5 })
    cap.textContent = (CITY[w.exitCity] || w.exitCity || 'direct') + ' · ' + w.inflight + ' downloading'; g.appendChild(cap)
  }
}

function drawMain(g: SVGGElement, M: { x: number; y: number }) {
  const ring = el('circle', { cx: M.x, cy: M.y, r: 10, fill: 'none', stroke: 'var(--accent, #7c6cff)', 'stroke-width': 2 })
  ring.appendChild(el('animate', { attributeName: 'r', values: '10;22;10', dur: '2.4s', repeatCount: 'indefinite' }))
  ring.appendChild(el('animate', { attributeName: 'stroke-opacity', values: '.8;0;.8', dur: '2.4s', repeatCount: 'indefinite' }))
  g.appendChild(ring)
  g.appendChild(el('rect', { x: M.x - 8, y: M.y - 8, width: 16, height: 16, fill: 'var(--accent, #7c6cff)', stroke: 'var(--card, #141418)', 'stroke-width': 2, transform: `rotate(45 ${M.x} ${M.y})`, rx: 2 }))
  const t = el('text', { x: M.x + 14, y: M.y + 2, 'text-anchor': 'start', fill: 'var(--text, #e8e8ec)', 'font-size': 13, 'font-weight': 800 })
  t.textContent = 'MAIN'; g.appendChild(t)
  const t2 = el('text', { x: M.x + 14, y: M.y + 16, 'text-anchor': 'start', fill: 'var(--muted, #8b8b93)', 'font-size': 11 })
  t2.textContent = 'harvests'; g.appendChild(t2)
}
