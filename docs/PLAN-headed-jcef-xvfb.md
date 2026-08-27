# Plan B — Headed JCEF on Xvfb (make the fetch pool a real windowed browser)

**Goal:** make the container's JCEF pass Cloudflare's Turnstile *itself*, the way it did on Windows,
instead of getting stuck on the "Verify you are human" bot screen and needing a manual solve.

**Rollback point:** tag `snapshot-before-headed-jcef` (commit `f2a0dee`).
Revert everything with `git reset --hard snapshot-before-headed-jcef`.

---

## 1. Why the container gets stuck (root cause, confirmed)

MangaFire has **two separate walls**:

1. **Cloudflare Managed Challenge / Turnstile** ("Verify you are human" — the screenshot). Passing it
   sets the **`cf_clearance` cookie**.
2. **MangaFire's own `/@waf/` shapes captcha** ("click the shapes"). Passing it sets MangaFire's own
   WAF session. The YOLO **autosolver** handles this one and works fine.

Wall 2 was never the problem. **Wall 1 is.** Our JCEF renders **offscreen/windowless** (so it can stream
frames to the phone WebView). Cloudflare's bot detection reads exactly the signals an offscreen browser
lacks:

- **no GPU compositing** (we run `--disable-gpu`),
- **`window.outerHeight` == 0 / no real window**,
- headless canvas + render-timing tells.

On **Windows**, JCEF was *also* offscreen, but the real GPU + real desktop supplied those signals, so
Cloudflare waved it through. In the container it has neither → **detected → stuck on Turnstile every cold
solve.** (This is also why **FlareSolverr works**: it runs a *headed* browser on Xvfb, which has the
signals. Approach A tried to borrow FS's cookie; it partially works but Cloudflare re-challenges JCEF on
its own fingerprint, so JCEF still hits the wall.)

**Conclusion:** the only robust fix is to give JCEF the same signals FlareSolverr/Windows have — a
**genuinely headed browser rendered into a virtual X server (Xvfb)**.

---

## 2. The core insight that makes this feasible

`windowless_rendering_enabled` is a **global** `CefSettings` flag, but per the CEF/JCEF docs you can
**mix windowed and offscreen browsers in one app** as long as that flag stays `true`. So:

| Browser | Mode | Why |
|---|---|---|
| **Fetch pool** (`JcefFetch`) | **Windowed** (real X11 window on `:99`) | Presents real window/GPU signals → **passes Turnstile itself** |
| **Interactive WebView** (`JcefRemoteView`) | **Offscreen** (unchanged) | Still needs `onPaint`/`frameJpeg` to stream to the phone |

We do **not** flip the global flag to `false` (that's what broke the guide's approach — it kills the OSR
WebView). We keep OSR enabled and create the *fetch* browsers windowed.

---

## 3. What actually changes (implementation)

### 3a. Dockerfile — real GL + X libs (software rendering under Xvfb)
Windowed rendering needs a working GL stack; there's no GPU, so use **SwiftShader** (software GL — this is
the safe path; hardware GL/`/dev/dri` is what broke CEF before, so we do **not** use it).
- Ensure `mesa` GL libs are present (already added earlier: `libgl1 libegl1 libgles2 libglx-mesa0
  libgl1-mesa-dri libvulkan1 mesa-vulkan-drivers`).
- Xvfb is already launched at `:99` by the entrypoint. Confirm it starts with GLX:
  `Xvfb :99 -screen 0 1920x1080x24 +extension GLX +render -noreset`.

### 3b. `CefManager` — flags + AWT
- Keep `windowless_rendering_enabled = true` (WebView OSR).
- **Remove `--disable-gpu`** *only via SwiftShader* for the app, add:
  `--use-gl=angle --use-angle=swiftshader --enable-unsafe-swiftshader` (software GL → gives compositing).
  Note: this is the same software-GL that worked before; only hardware GL broke CEF.
- Add anti-automation flags: `--disable-blink-features=AutomationControlled`, `--window-size=1920,1080`.
- Ensure the JVM is **not** headless: set `System.setProperty("java.awt.headless", "false")` before CEF
  init, and `DISPLAY=:99` is already exported by the entrypoint.

### 3c. `JcefFetch` — create the pool browsers windowed
Today `newBrowser()` does:
```
client.createBrowser(root, CefRendering.CefRenderingWithHandler(renderHandler, JPanel()), false)
```
Change the **fetch** browsers to windowed. In JCEF that means hosting the browser in a real (but never
shown) AWT window on `:99`:
- Create an off-list `java.awt.Frame`/`Window` (or `CefBrowser` via `createBrowser(url, false /*OSR=false*/,
  false /*transparent*/, requestContext)` and add its `uiComponent` to a Frame sized 1920x1080 on `:99`).
- `frame.setVisible(true)` on the Xvfb display (invisible to us; real to Cloudflare).
- The **fetch mechanism is unchanged** — we still call `browser.evaluateJavaScript(fetchJs)` and read the
  same-origin `fetch()` result. Windowed vs offscreen doesn't change how JS eval works.
- Keep the `HostPool` coalescing + `waitCleared` logic as-is; only the browser *construction* changes.

`JcefRemoteView` (the WebView) is left **exactly as-is** (offscreen).

---

## 4. Pros / Cons

### Pros
- **Windows-identical mechanism**: JCEF clears Turnstile on its own; no FlareSolverr dependency for wall 1,
  no cookie transplant, `cf_clearance` minted with JCEF's own fingerprint (no rebind rejection).
- Fixes the **"sometimes doesn't call FlareSolverr → manual solve"** bug: we stop depending on FS for
  Turnstile at all.
- Cold solve becomes: JCEF passes Turnstile (~2–5s) → shapes captcha → YOLO autosolve (~5s) → done,
  unattended. Warm stays 230ms.
- Keeps the WebView streaming intact (OSR unchanged).

### Cons / Risks
- **Highest-risk change of the session.** Windowed CEF in a headless JVM is finicky (AWT/X11 init) — this
  is the *same class* of change that broke CEF twice (GPU passthrough, log routing). CEF may fail to init
  windowed (`0/5 browsers`, "CEF client unavailable").
- Requires `java.awt.headless=false` + a live Xvfb; if AWT can't connect to `:99`, browser creation fails.
- Software GL (SwiftShader) adds CPU per browser; 5 windowed browsers × SwiftShader compositing is heavier
  than OSR. May need to drop `MU_JCEF_POOL` back to 2–3.
- Only fixes **wall 1**. Wall 2 (shapes captcha) still needs the autosolver — that's expected and fine.
- Mixed windowed+OSR in one CEF app is documented as *possible* but is a less-travelled path; edge cases
  (focus, message loop) may surface.

---

## 5. Rollout / test plan (incremental, revert-ready)

1. **Snapshot** ✅ already tagged `snapshot-before-headed-jcef`.
2. Implement 3a–3c behind an env flag **`MU_JCEF_HEADED=1`** so it's opt-in and instantly disableable
   (unset the env → back to offscreen fetch, no rebuild). Default off.
3. Build + deploy with `MU_JCEF_HEADED=1`.
4. **Gate 1 — does CEF even init windowed?** Watch for `CEF runtime ready` + `opened windowed browser` and
   **not** `0/5 busy` / `CEF client unavailable`. If it won't init → set `MU_JCEF_HEADED=0`, we're back to
   today instantly, and B is dead (fall back to living with A + autosolve).
5. **Gate 2 — does it pass Turnstile?** egress reset → cold MangaFire. Success = **no** `didn't visibly
   clear within 20s`; JCEF goes straight past Turnstile (cf_clearance appears from JCEF itself) to the
   shapes captcha/200. Failure = still stuck on Turnstile → B didn't help, disable the flag.
6. **Gate 3 — WebView still works?** Open an interactive WebView source; confirm frames still stream to the
   phone (OSR untouched).
7. If all three pass: make `MU_JCEF_HEADED` the default; keep the offscreen path as the fallback.

---

## 6. If B fails at Gate 1 or 2

Fallback ladder (in order):
1. Keep **A** (FlareSolverr-seeds-JCEF) + the interactive-bail + autosolve — today's behavior. Cold solve
   ~15–25s with occasional manual click; warm fast.
2. Route MangaFire's fetches **entirely** through FlareSolverr's rendered-page path (FS returns the page
   body) where the vrf allows — narrower, but avoids JCEF for wall 1.
3. Accept the manual-click fallback for cold MangaFire and move on (it's a once-per-clearance-expiry cost).

---

## 7. Files touched (for review / revert)
- `deploy/Dockerfile` — confirm GL/X libs + Xvfb GLX (mostly already done).
- `android-compat/.../CefManager.kt` — flags, `java.awt.headless=false`, SwiftShader GL.
- `android-compat/.../JcefFetch.kt` — `newBrowser()` windowed construction behind `MU_JCEF_HEADED`.
- `deploy/docker-compose.yml` — `MU_JCEF_HEADED=1` env (opt-in).

No changes to `JcefRemoteView` (WebView stays offscreen).
