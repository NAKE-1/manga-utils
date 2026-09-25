# SUPERMODEL PLAN — pluggable WebView engines (keep JCEF, add a Chrome+CDP sidecar, pick in dev)

**Approach chosen by the user (2026-09-25):** do NOT delete JCEF. Introduce an **engine abstraction** with
two interchangeable backends, selectable from a **dev setting**, and rename the dev UI from JCEF-specific
labels to generic "WebView" labels. Ship both; A/B them; only retire JCEF later if the Chrome engine wins.
Supersedes the "delete JCEF" framing in [[manga-utils-vm-cpu-host]] / docs/PLAN-cef-sidecar.md (that
analysis still holds for WHY; this is the coexistence HOW). Root cause recap: libcef MemoryInfra CFI
SIGILL, unfixable in-process — see the memory note.

Do NOT start coding until this plan is reviewed. Each phase is independently shippable + reversible
(engine defaults to `jcef` until we flip it).

---

## 0. Goal & guardrails
- Two engines behind one interface: **`jcef`** (today's in-process JCEF, unchanged) and **`chrome`**
  (new headed-Chrome-in-Xvfb sidecar driven over CDP — crash-isolated, no libcef in the JVM).
- A **dev setting** `webviewEngine` (`jcef` | `chrome`, default `jcef`) picks which one all WebView +
  JcefFetch traffic uses. Switchable live from the dev menu.
- **Rename** dev UI: "JCEF test" → "WebView test", "Reset JCEF pool" → "Reset browser pool", etc. Keep the
  engine name only as a small sub-label / the selector itself.
- **Parity is the acceptance bar.** The `chrome` engine must match JCEF on: scroll, click, YOLO autosolve,
  User-Agent capture+forcing, and cookie/token sharing. If any of those don't work, `chrome` is not done.

## 1. The engine abstraction
Define one interface both backends implement (place in `android-compat` webkit or a new `server` package):

```
interface WebViewEngine {
  // interactive OSR view
  fun open(url: String): OpenResult          // {status: ready|starting|failed, w, h}
  fun frameJpeg(): ByteArray?
  fun click(x: Int, y: Int)
  fun scroll(x: Int, y: Int, dy: Int)
  fun close()
  fun isReady(): Boolean; fun state(): String
  // programmatic CF fetch (JcefFetch's job)
  fun fetch(url: String, method: String, headers: Map<String,String>, body: String?): FetchResult?
  // autosolve + verification
  fun autosolve(host: String): SolveResult   // YOLO read→click loop
  // cookie / UA bridge (the crux — see §4)
  fun cookieHosts(): List<CookieHost>
  fun setCookie(host, name, value, …): Boolean
  fun clearCookies(host: String?): Int
}
```
- `JcefEngine` = a thin wrapper over the existing `JcefRemoteView` + `JcefFetch` + `CefManager` (no
  behavior change — just implement the interface by delegating).
- `ChromeEngine` = an HTTP client (like `SolverClient`) to the new `browser` sidecar; every method is one
  sidecar call, returning null/failed on unreachable so existing fallbacks fire.
- A `WebViewEngines` selector object reads `SettingsStore.get().webviewEngine` and returns the active
  engine. **All** call sites (Main.kt webview routes, `JcefFetchInterceptor`, autosolve, dev cookie/pool
  menu) go through the selector, never the concrete object.

## 2. Backend routing changes (main server)
- `Main.kt` webview routes (`open/frame/input/scroll/close/autosolve/status/pending/cookies/pool`):
  replace direct `JcefRemoteView.*` / `JcefFetch.*` with `WebViewEngines.active().*`.
- `JcefFetchInterceptor`: replace `JcefFetch.fetch(...)` with `WebViewEngines.active().fetch(...)`. The
  MangaFire→solver branch (line 40-45) is unchanged (still the curl_cffi solver, engine-independent).
- Add `webviewEngine` to `SettingsStore` (default `"jcef"`) + `applyX` live-apply on settings save.

## 3. The `chrome` sidecar (deploy/browser/)
A new container mirroring the solver/flaresolverr pattern. Python (Flask) + Xvfb + real Chrome +
Selenium/undetected-chromedriver (the FlareSolverr recipe that already passes CF on this box) + a CDP
session. **Reuses the solver sidecar's `best.onnx` + `captcha.py`** for autosolve.

Endpoints (1:1 with the interface):
| Route | CDP mechanism |
|---|---|
| `POST /webview/open {url}` | `Page.navigate` + `Page.startScreencast` (jpeg, everyNthFrame) |
| `GET /webview/frame` | latest `Page.screencastFrame` buffer (ack with `screencastFrameAck`) |
| `POST /webview/input {x,y}` | `Input.dispatchMouseEvent` mousePressed+mouseReleased (left) |
| `POST /webview/scroll {x,y,dy}` | `Input.dispatchMouseEvent` type=mouseWheel deltaY=dy |
| `POST /webview/autosolve {host}` | read captcha via `Runtime.evaluate` → YOLO (best.onnx) → CDP clicks; or `/@waf/generate`+`/@waf/verify` |
| `POST /fetch {url,method,headers,body}` | `Runtime.evaluate` same-origin `fetch()` (like JcefFetch.buildFetchJs) |
| `GET /cookies?host=` / `POST /cookies/set` / `POST /cookies/clear` | `Network.getAllCookies` / `Network.setCookie` / `Network.deleteCookies` |
| `GET /ua` | `Browser.getVersion` userAgent (for §4) |
| `GET /health` | `{ready, url}` |

Compose: add `browser` service, `restart: unless-stopped` (autoheal already present),
`MU_BROWSER_SIDECAR_URL=http://browser:9000`. `ChromeEngine` is inert unless that env is set.

## 4. PARITY — the five things that MUST work (acceptance checklist)
### 4.1 Scroll
- Frontend already sends OSR-pixel `x,y,dy` to `/api/webview/scroll`. Map to CDP `mouseWheel`. Watch the
  coordinate scale: JCEF OSR is 440×780 (`JcefRemoteView.WIDTH/HEIGHT`); the CDP screencast viewport must
  be set to the SAME device metrics (`Emulation.setDeviceMetricsOverride` 440×780) so the frontend's
  `toOsr()` math (frame→OSR) is unchanged. **Test:** scroll a long page in both engines, identical feel.
### 4.2 Click / tap
- `/api/webview/input {x,y}` → `Input.dispatchMouseEvent` moved→pressed→released at (x,y). Same viewport
  mapping as scroll. **Test:** tap the shapes on a live captcha in both engines; taps land on target.
### 4.3 Autosolve (YOLO)
- JCEF path: `RV.evalJs(CAPTCHA_READ_JS)` reads a/b images → `CaptchaSolver.solve` (best.onnx) →
  `RV.click`. Chrome path: sidecar reads the same DOM via `Runtime.evaluate`, runs the **same** ONNX
  model (reuse `captcha.py`/`best.onnx`), clicks via CDP. Keep the `/api/webview/autosolve/events` toast
  stream working (sidecar streams progress back, or main server polls). **Test:** MangaFire captcha
  tester + a real overnight-style solve pass on both engines.
### 4.4 User-Agent (cf_clearance is UA-bound — DO NOT skip)
- cf_clearance only works with the UA it was minted under. Today FlareSolverr returns its UA →
  `FlareSolverrConfig.solvedUserAgents[host]` → a network interceptor forces that UA on every okhttp
  request to the host (NetworkHelper.kt:119-126). For the `chrome` engine: after a solve, capture Chrome's
  real UA (`GET /ua`) and register it in `solvedUserAgents[host]` exactly like FS does, so okhttp/solver
  replays match. **Test:** after a chrome-engine solve, confirm subsequent okhttp requests carry Chrome's
  UA and the clearance is accepted (no re-challenge).
### 4.5 Cookie / token sharing (the crux)
- A solve produces `cf_clearance` (+ MangaFire's WAF cookie/token). These must reach whatever actually
  fetches. The plumbing already exists for FS → sinks; the chrome engine reuses the SAME sinks:
  1. okhttp **cookieStore** (the `PersistentCookieStore` FlareSolverrInterceptor writes) — inject
     cf_clearance so plain okhttp requests carry it.
  2. **solver sidecar** — for MangaFire, POST the WAF cookie/token + cf_clearance to the solver so its
     curl_cffi Session is warm (mirror `JcefFetch.setCookie` which today seeds FS→CEF).
  3. `solvedUserAgents[host]` (see §4.4) + `HumanCheckState.cleared(host)` so paused downloads resume.
- Direction of flow (chrome engine): user solves in sidecar Chrome → sidecar `Network.getAllCookies` →
  main server pulls cookies+UA via `/cookies` + `/ua` on close/verify → fans them into the three sinks
  above. **Test:** solve MangaFire's challenge in the chrome WebView → a queued MangaFire download
  resumes and succeeds WITHOUT re-solving (proves cookie+UA+token reached the fetch path).
- JCEF path keeps writing to `CefCookieManager` as today (no change).

## 5. Dev UI (rename + engine picker)
- New dev row **"WebView engine"**: a selector `JCEF (in-process)` / `Chrome sidecar (isolated)` bound to
  `webviewEngine`; a health dot per engine (JCEF state / sidecar `/health`).
- Renames (functional labels only — behavior unchanged):
  - "WebView tester" stays (already generic).
  - "MangaFire captcha tester" stays.
  - "JCEF browser pool" → **"Browser pool (fetch)"**; "Reset JCEF pool" → **"Reset browser pool"**.
  - "WebView cookies" stays; hint wording drop "JCEF"-specific phrasing → "the browser engine".
  - Restart hint mentioning "Chromium (JCEF)" → "the WebView engine".
- The dev cookie/pool panels read from `WebViewEngines.active()` so they reflect whichever engine is on.

## 6. Phased implementation (each shippable, reversible)
- **P0 — measure (½ day).** Grep logs `JCEF[` success vs `→ falling back` per host. If fetch is rarely
  load-bearing, the chrome engine can implement `fetch()` as a thin FlareSolverr passthrough first and we
  focus effort on the interactive view. Decides scope.
- **P1 — abstraction, no new engine (1 day).** Introduce `WebViewEngine` + `JcefEngine` delegating to
  today's code; route everything through `WebViewEngines.active()`; add `webviewEngine` setting (only
  `jcef` valid yet). Pure refactor — zero behavior change, fully shippable. Rename dev labels here.
- **P2 — sidecar skeleton + interactive view (2-3 days).** `deploy/browser/` (Xvfb+Chrome+CDP+Flask);
  `/health`, `/webview/open`, `/webview/frame`; `ChromeEngine` open/frame; get a page streaming to the
  modal under engine=`chrome`. **Parity §4.1/4.2** (scroll/click via CDP input).
- **P3 — autosolve + cookies/UA (2 days).** `/webview/input,scroll,autosolve` (reuse YOLO); `/cookies`,
  `/ua`; the §4.5 fan-out into the three sinks; `HumanCheckState`. **Parity §4.3/4.4/4.5.**
- **P4 — fetch (only if P0 says needed) (1-2 days).** `/fetch` via `Runtime.evaluate`; `ChromeEngine.fetch`.
- **P5 — soak.** Run engine=`chrome` for a week; confirm no server crashes (isolated), verification works.
  Only after that, consider defaulting to `chrome` and/or retiring JCEF.

## 7. Test matrix (run for BOTH engines before calling parity done)
| Check | jcef | chrome |
|---|---|---|
| Open a source WebView, page renders | | |
| Scroll a long page | | |
| Tap lands on target (captcha shapes) | | |
| YOLO autosolve clears MangaFire /@waf | | |
| After solve, UA forced on okhttp (no re-challenge) | | |
| After solve, queued MangaFire download resumes + succeeds (cookie+token+UA shared) | | |
| Sidecar/engine crash does NOT kill the server (chrome: kill the container mid-solve) | n/a | |
| Non-MangaFire CF source loads (fetch or FS fallback) | | |

## 8. Risks / open questions
- **CDP screencast latency vs JCEF OSR** — both are poll/push JPEG; expect parity, verify on phone.
- **undetected-chromedriver vs Chrome version drift** — pin versions in the sidecar image (FlareSolverr
  has the same concern; copy its approach).
- **RAM** — a 2nd headed Chrome alongside FlareSolverr (~300-500MB). Fine on the i5-1340P; consolidation
  possible later (reuse FS's browser) but not day one.
- **Two cookie worlds while both engines exist** — jcef uses CefCookieManager, chrome uses its own +
  the shared sinks. Switching engines mid-session may need a re-solve on the new engine. Acceptable
  (dev-only switch); document it.
- **Autosolve event stream** (`/api/webview/autosolve/events`) — ensure the chrome engine feeds it so the
  toast UX is identical.
