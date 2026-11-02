# PLAN: Fleet flow map + Add-worker modal (Worker nodes screen)

Status: **MOCKUP ONLY** - not wired. Interactive mockups published as Claude artifacts
(flow map + Add-worker popup). This doc is the spec for when we build it for real.

## 1. Fleet flow map

A live US map that sits **above the node list** on the Worker nodes screen. Each online
worker is drawn at its **egress city** (the Mullvad exit it fetches through); the **main
node** is a fixed marker in Florida (direct egress, no Mullvad city). Traffic animates
from each worker back to main as it harvests finished chapters over the tailnet.

### Why egress, not physical
Both boxes physically sit at home next to main. Plotting physical location just stacks
everything on Florida. The arc is the **logical fetch path** (source -> Mullvad exit ->
tailnet -> main), which is the useful thing to see. A worker egressing "Off" (direct)
collapses onto main's location - handle that case (short/degenerate arc or a badge).

### Hard rule: 1 download per source at a time (rate-limit guard)
A worker downloads at most ONE chapter per source concurrently; further chapters for that source QUEUE (or get
split to another worker). So per worker/source there is exactly one chapter "in the pipe"; the rest are waiting.
This collapses two visual choices into one: "one dot per chapter" == "one dot per source" (max one in-flight
each), so the mockup keeps only **per chapter** (dot = the chapter shipping from that source) and **by rate**.

### Two stages, kept visually distinct
- **Download** (source -> exit, AT the worker): a **pulse ring + count badge** on the worker marker. Ring =
  "is downloading", badge = number of sources downloading now (one each). Does NOT travel the line.
- **Harvest** (worker -> main): **moving dots travel home**, one per source = the chapter currently shipping
  from it. Queued chapters are NOT dots - they show as a **+N** on that source's row.

### Other animation cues (surface in a small legend)
- **Flowing dashed stream** under the dots = an active transfer on that worker's line.
- **Pulsing ring on main** = main actively receiving / committing.
- **Worker halo** = online. No halo / greyed = offline.
- **Red dashed arc + x** = that worker's egress check is failing (downloads stalled).

### Per-source rows (the "worker is pulling many sources" case)
Stack one row per source next to the worker: a small **source icon** (the real extension/favicon in-app; a
colour-chip-with-initial stand-in in the mockup, since the artifact CSP blocks live favicons) **+ a 2-letter
code + x1** (downloading) **+N** (queued, muted) - e.g. `AT x1 +3`. Coloured by source. Scales vertically; a
`city - N downloading` caption closes the stack. 7-source worker is the stress test.

### Label placement / collisions
Nearby workers (e.g. NY + Ashburn) collide when both expand tall stacks. Mockup handles it with a per-worker
`side` override (throw a crowded node's stack into open space) + putting the count badge on the side opposite
the label. In-app, generalise: auto-pick each label's side by local density, and collapse a many-source worker
to `N sources` with tap-to-expand if stacks still overlap at phone width.

### Colour model (two palettes, two meanings)
- **Each worker owns a line colour** (muted) used for its arc + location dot - that's the "which box" channel.
- **Each source owns a colour** (vivid) used for the **moving dots** - that's the "which site" channel. A worker
  pulls several sources at once, so one line can carry mixed-colour packets simultaneously. Moving dots get a
  white core so a bright packet never reads as part of a dim line. Keep the two palettes clear of red (failing)
  and the main's purple.

### Data - mostly already have
| | source |
|---|---|
| have | `/api/workers` -> per worker `online`, `egressIp`, `inflight`, metrics |
| have | `GET /worker/exit-nodes` -> `currentCity` (exit state) + per-state ping |
| have | `DownloadQueue.workerLoad` -> which worker holds which source's tasks |
| add | `code -> {lat,lon}` table for Mullvad cities (we already keep the labels) |
| add | main node position: one setting (default FL); or geo-locate its `egressIp` once + cache |
| add | per-worker `bytesPerSec` (nice-to-have; derive from download progress, else drop MB/s) |

### Build
- Thin endpoint `GET /api/fleet/flows` (or extend `/api/workers`) returning
  `[{worker, exitCity, lat, lon, inflight, bytesPerSec, egressOk}]` - assembly, no new state.
- Frontend: reuse the mockup's SVG projection + US outline + lake overlays; one arc `<path>`
  per worker, pulses via SVG `animateMotion` (vanilla, no new dep). Pulse count = `inflight`,
  speed = throughput. Pause/resume with `svg.pauseAnimations()` behind an "Animate" toggle.
- Poll the Workers screen's existing 5s timer. No websocket needed for a glance view.
- Place it in a collapsible card directly above the node list so it doesn't push the list
  far down on phones; collapsed state remembered in `localStorage`.

### Projection (carry over from the mockup)
Linear equirectangular, 38N standard parallel: `sx = sy*cos(38deg)`, CONUS bounds
lon -125..-66.5, lat 24..49.7. City dots plotted from true lat/long so positions are exact;
the US outline is a hand-built polygon with Great Lakes overlays (recognizable, not survey-grade).

### Effort
~half a day. Reuses the map, the poll loop, and existing worker fields; real new code is the
lat/long table + the assembling endpoint.

### Later / easy wins
- Label the **source site** on the far end of each arc (source -> exit -> main).
- Arc thickness by throughput; colour by source.
- Multiple workers sharing an exit city: fan the arcs / offset labels (mockup tests Miami
  worker vs FL main overlap - keep that collision handling).

## 2. Add-worker modal

Replace the inline "Add a worker" card fields with a **`+` button** that opens a popup
(modal) containing: **Name**, **URL**, **Token**, a **Test** button (probes and shows
`online / egress IP / version` or the error), and **Add worker**. Keeps the screen clean -
the add form is only present when you want it.

- Reuse existing calls: `api.testWorker(url, token)`, `api.addWorker(name, url, token)`.
- Modal is a focus-trapped overlay; Esc/backdrop closes (note: artifact `confirm()` is inert,
  but in-app a real modal is fine). On successful Add, close + refresh the list.
- The `+` lives where the card title is now; on open, the map card (if present) stays put.
