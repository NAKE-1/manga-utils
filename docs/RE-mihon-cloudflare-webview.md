# Reverse-engineering how Mihon gets into MangaFire (and how to replicate it)

**Status:** analysis only — no code changed yet. Written to decide the build before touching anything.
**Versions inspected:** Mihon `v0.20.1`, Suwayomi-Server `v2.3.2243`, Keiyoushi MangaFire `1.6.27` (`KeiSource`, ext-lib 1.6), our `source-api` (Suwayomi-derived).

---

## 0. TL;DR

- MangaFire's `/api` is behind Cloudflare that **fingerprint-gates the `cf_clearance` cookie** — only a *real browser's* clearance is honoured. (Proven: your real Opera/Windows cookies → `200`; FlareSolverr's headless-Linux clearance → re-challenge.)
- The **vrf is not the problem**. `KeiSource` bundles `VrfSigner` into the jar and it runs on our host too (byte-identical, verified earlier). The `"Missing token"` we see is an **artifact** of our FlareSolverr rendered-body fallback fetching the API URL *without* the vrf.
- **Mihon clears CF automatically** with a *real Android WebView* (`CloudflareInterceptor` → `WebViewInterceptor`). No FlareSolverr, no user action, no "open webview" step — it's a hidden auto-solve.
- The load-bearing trick: **WebView and OkHttp share ONE cookie store** (`AndroidCookieJar` delegates to `android.webkit.CookieManager`), so the WebView's fresh real-browser `cf_clearance` is transparently sent on the OkHttp retry, with a **matching User-Agent**.
- **We ship a no-op `CloudflareInterceptor` shim** (only there to satisfy `KeiSource`'s presence assertion) and do CF via `FlareSolverrInterceptor` (headless Linux Chrome) → its clearance is rejected → re-challenge → `"Missing token"`.
- **Replication = replace the no-op shim with a real JCEF (Chromium) CloudflareInterceptor** that mirrors Mihon's flow, bridging JCEF's cookies into our `PersistentCookieStore` (JVM has no shared cookie store like Android's).

---

## 1. The MangaFire request path in Mihon, end to end

1. `KeiSource` builds its `client` = host `network.client` + (`SessionWarmup` upstream doesn't have — that's ours) + `VrfSigner` interceptor, via `configureClient()`.
2. Extension calls e.g. `GET /api/titles?order[views_30d]=desc&page=1&limit=50` with headers `Accept: application/json` (+ our added `Referer`/`X-Requested-With`, which upstream does **not** set).
3. `VrfSigner` interceptor (innermost) rewrites the URL: canonicalises the query (sort keys, `[]`→`[i]`), signs it with the 3-stage cipher, appends `&vrf=…`.
4. Request hits Cloudflare. If challenged (`403/503` + `Server: cloudflare` + challenge HTML), the host's **`CloudflareInterceptor` fires**.
5. `CloudflareInterceptor` opens a **real WebView**, UA = the request's UA, loads the challenge URL, waits (≤30 s) for a **new `cf_clearance`** to appear in `android.webkit.CookieManager`.
6. Because the OkHttp cookie jar **is** that `CookieManager` (`AndroidCookieJar`), the retried request automatically carries the fresh `cf_clearance` — and the UA matches, so the (UA-bound) clearance stays valid.
7. `/api` returns `200` JSON. Done. The clearance came from a **real browser fingerprint**, which is what MangaFire's `/api` accepts.

---

## 2. The four load-bearing pieces (with real code)

### 2.1 `AndroidCookieJar` — the shared cookie store (the linchpin)
```kotlin
private val manager = android.webkit.CookieManager.getInstance()   // SAME instance WebView uses
override fun saveFromResponse(url, cookies) = cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
override fun loadForRequest(url) = get(url)                          // manager.getCookie(url) → parsed
fun remove(url, names, maxAge=-1) = ...setCookie(url, "$name=;Max-Age=-1")
```
**Consequence:** anything the WebView stores is instantly visible to OkHttp and vice-versa. No sync code, no race. On a JVM there is **no equivalent** — JCEF's `CefCookieManager` is separate from our OkHttp jar, so we must bridge explicitly.

### 2.2 `CloudflareInterceptor` — detection + WebView solve
```kotlin
ERROR_CODES = [403, 503]; SERVER_CHECK = ["cloudflare-nginx", "cloudflare"]
// challenge confirmed by HTML: getElementById("challenge-error-title"|"challenge-error-text") != null
val oldCookie = cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }
cookieManager.remove(url, COOKIE_NAMES, 0)          // drop stale clearance first
webview.loadUrl(origRequestUrl, headers)            // load with request headers
latch.awaitFor30Seconds()                           // block the OkHttp thread
// success = new cf_clearance present AND != oldCookie ; else CloudflareBypassException → IOException
```
Latch releases on: **new `cf_clearance`** / `onReceivedHttpError` non-challenge / first `onPageFinished` with no challenge.

### 2.3 `WebViewInterceptor` (base) — the WebView plumbing
```kotlin
fun createWebView(request): WebView { settings.userAgentString = request.header("User-Agent") ?: defaultUserAgentProvider() ... }
fun parseHeaders(headers) = headers.filter { isRequestHeaderSafe(it) }   // strip content-length, host, cookie2, proxy-*
fun CountDownLatch.awaitFor30Seconds() = await(30, SECONDS)              // OkHttp has no async interceptors → block
```
UA priority: request header, else `defaultUserAgentProvider()`. Empty UA is avoided ("Chromium WebView resets to default if empty"). **WebView UA == OkHttp UA**, which is why the clearance stays valid across the hand-off.

### 2.4 `KeiSource.client` — what the extension actually requires of the host
```kotlin
final override val client by lazy {
    network.client.newBuilder().apply {
        require(UncaughtExceptionInterceptor present)
        require(UserAgentInterceptor present)
        require(CloudflareInterceptor present)          // <-- RELIES ON THE HOST TO SOLVE CF
        require(IgnoreGzipInterceptor NOT present)
        require(BrotliInterceptor NOT present)
        configureClient()                                // adds rateLimit + VrfSigner (+ our SessionWarmup)
    }.build()
}
headersBuilder() = super.headersBuilder().add("Accept","application/json").configureHeaders()
```
The extension **delegates Cloudflare entirely to the host's `CloudflareInterceptor`**. It only asserts one is present — it can't tell whether it's real or a shim.

---

## 3. Why it works on Mihon and fails on us

| Piece | Mihon v0.20.1 | Us (Suwayomi-derived) |
|---|---|---|
| `CloudflareInterceptor` | **Real** — WebView solver | **No-op shim** (only satisfies KeiSource's presence check) |
| CF actually solved by | Android WebView (real Chromium, on-device) | `FlareSolverrInterceptor` → FlareSolverr (headless `X11; Linux` Chrome) |
| Clearance fingerprint | **Real browser** → accepted by MangaFire `/api` | **Headless** → rejected → re-challenge |
| Cookie store | `AndroidCookieJar` = shared with WebView | `PersistentCookieStore` + `PersistentCookieJar` (separate from any browser) |
| UA vs clearance | WebView UA == OkHttp UA (consistent) | FlareSolverr UA forced on solved hosts; app default now Android 141 |
| vrf | `VrfSigner` in jar | same `VrfSigner` in jar (works) |
| `callTimeout` | 2 min | **45 s** (too short for a slow solve) |

**One-line diagnosis:** the extension asks the host to solve Cloudflare with a real browser; Mihon does, we hand it a no-op and solve with a headless service whose clearance MangaFire won't accept.

---

## 4. What we already have that maps straight over

- **JCEF real Chromium** — already working in-app for the comix WebView (`CefManager`). This is our "Android WebView" equivalent, with a *real desktop* fingerprint (the kind your Opera test proved MangaFire accepts).
- **The cookie-bridge pattern** — `FlareSolverrInterceptor` already does `cookieStore.addAll(url, cookies)` to push solved cookies into `PersistentCookieStore`. We reuse exactly this to bridge JCEF → OkHttp (the manual stand-in for `AndroidCookieJar`).
- **Challenge detection** — `isCloudflareChallenge()` already exists (same 403/503 + Server + markers logic as Mihon).
- **The empty slot** — our `CloudflareInterceptor` is *already wired into `network.client`* as a no-op. It's the precise place to drop a real implementation, and it satisfies KeiSource unchanged.

---

## 5. Proposed replication design (JCEF `CloudflareInterceptor`)

Mirror Mihon's `WebViewInterceptor` + `CloudflareInterceptor`, backed by JCEF instead of Android WebView:

1. **Detect** the challenge with the existing `isCloudflareChallenge()` (unchanged).
2. **Solve**: load the challenge URL in a JCEF browser (reuse `CefManager`), UA = the request's UA (keep JCEF UA == OkHttp UA), wait (latch, ~30–60 s) for a **new `cf_clearance`** in `CefCookieManager` — via a cookie-visitor poll and/or `onLoadEnd`.
3. **Bridge**: copy JCEF cookies (`cf_clearance`, `session`, `__pf`, …) into `PersistentCookieStore` with `cookieStore.addAll(url, cookies)` (the FlareSolverr pattern). Pin the JCEF UA for that host so the OkHttp retry's UA matches the clearance.
4. **Retry** the original request once; return it if it's no longer a challenge.
5. **Keep FlareSolverr as an optional fallback**, but make JCEF the default CF path (it's the one with evidence of working here). Selection can reuse the existing `cloudflareBypass` gate.

**Concurrency/threading:** one shared JCEF browser (or a tiny pool), serialise solves per host, block the OkHttp thread with a `CountDownLatch` exactly like Mihon (OkHttp interceptors are synchronous).

**Timeouts:** the CF solve must be allowed to exceed our current **45 s `callTimeout`** (NetworkHelper.kt:67). Either raise it on the CF path or exempt bypass requests — otherwise a real solve is guillotined the same way FlareSolverr's was.

---

## 6. Risks / unknowns (resolve before/while building)

1. **Headless/OSR fingerprint.** Mihon's WebView is a real (invisible) on-screen Android WebView. JCEF can render on-screen or windowless (OSR). The open question is whether MangaFire accepts an **OSR JCEF** clearance. Mitigation: run JCEF with a real (hidden/off-screen but on-screen-capable) window on desktop; your Opera test says *a* real desktop Chromium is accepted, so odds are good.
2. **Deploy target.** Strong while self-hosting on the **Windows desktop** (real display). On **headless Docker/Proxmox** later, JCEF needs a virtual display (Xvfb) and drifts back toward FlareSolverr's headless class. Acceptable: keep FlareSolverr as the headless-deploy fallback.
3. **JCEF startup cost / lifecycle.** `CefManager` already downloads/initialises native Chromium (comix). Reuse it; don't spin a browser per request.
4. **UA drift.** cf_clearance is UA-bound. We must force the exact JCEF UA on the host's retry for that host (we already do this for FlareSolverr via `solvedUserAgents`).

---

## 7. Decision

If approved: implement a **JCEF-backed `CloudflareInterceptor`** replacing the no-op shim (steps in §5), gated so FlareSolverr remains the fallback and headless deploys still work. This is the direct, evidence-backed replication of what makes MangaFire work on Mihon. Estimated scope: one new interceptor + a JCEF cookie/UA bridge + a callTimeout carve-out — not a rewrite.

---

## 8. Fingerprint question — answered (risk downgraded to LOW)

Two facts remove most of the §6 risk #1:

- **Mihon's WebView runs zero stealth.** `WebViewUtil.setDefaultSettings()` only enables `javaScriptEnabled`, `domStorageEnabled`, wide-viewport, third-party cookies — **no `navigator.webdriver` spoof, no injected JS, no fingerprint obfuscation.** The only tweak is UA (WebView→`Chrome/… Mobile`). If MangaFire fingerprinted automation, a plain no-stealth WebView would fail. It works ⇒ the bar is just *"a real browser engine that completes the JS challenge."* OSR JCEF (real Chromium, no webdriver flag) meets that bar.
- **`cf_clearance` is portable across TLS stacks.** Mihon harvests the WebView cookie and replays it through okhttp (different TLS/JA3); your Opera→curl test did the same (curl's JA3 ≠ Opera's → still 200). So the clearance is bound to **UA + cookie set**, not the solver's TLS fingerprint. Copying cookies out of a real browser and replaying via our okhttp is a supported path, not a hack.

**Reconciling why FlareSolverr still failed:** its solves carried `cf_chl_rc_ni` ("challenge remaining") — i.e. **cut off before completion** by our 45 s `callTimeout` (and FlareSolverr's own 60 s), which is exactly the Suwayomi "raise to 120 s" symptom. A real browser that runs the challenge **to completion** and hands over the finished cookie set is what's missing — headless-vs-real is a smaller factor than *incomplete-vs-complete*.

## 9. Background jobs — auto-update & new-chapter scans (headless, no human)

The manual "browse in WebView" button (Mihon's other feature; the one you want) is **great for interactive use and zero-risk**, but auto-update and scan-for-new-chapters run **unattended** — nobody's there to click. Design must cover both:

- **`cf_clearance` is cached + reused.** It already lives in `PersistentCookieStore` and is valid for a good while (typically 30 min–hours). Background jobs **reuse** the cached clearance — they do **not** each trigger a solve.
- **Automated refresh keeps it warm.** When a background call hits a challenge and the cached clearance is stale/absent, the **automated JCEF interceptor** solves it in-process (OSR, no window needed) and refreshes the cache — the same code path as the interactive case, minus the human. Optional: proactively re-warm shortly before expiry so scheduled scans usually start with a valid cookie.
- **Graceful degradation.** If an unattended solve fails (e.g. a new managed/Turnstile variant), the job logs "needs manual WebView solve for `<source>`" and moves on, instead of hanging — the user clears it next time they open the app via the button, which re-seeds the cache for the next scan.

So the two features are **one cache with two writers**: the manual button (human-driven, guaranteed real) and the automated JCEF interceptor (unattended). Background scans are pure **readers** of that cache, with the automated writer as their fallback.

## 10. Options & recommendation

| Option | Interactive | Background (auto-update/scan) | Fingerprint risk | Effort | Verdict |
|---|---|---|---|---|---|
| **A. Manual WebView button only** | ✅ real browser, guaranteed | ❌ needs a human | none | low | Necessary, not sufficient |
| **B. Automated JCEF interceptor** | ✅ auto-solve | ✅ unattended solve + cache | low (§8) | med | Core of the fix |
| **C. FlareSolverr + raise timeout to 120 s** | ⚠️ works sometimes | ⚠️ slow, flaky | n/a (real Chromium remote) | tiny | Keep as headless-deploy fallback |
| **D. Migrate off MangaFire** | — | — | — | low | Last resort |

**Recommendation — B + A, C as fallback, in phases:**
1. **Phase 1 — Manual WebView button** (`CefManager` visible window → harvest `cf_clearance`+`session`+UA into `PersistentCookieStore`). Smallest, zero fingerprint risk, unblocks *reading* MangaFire immediately, seeds the cache for scans. Proves the copied-cookie path end-to-end.
2. **Phase 2 — Automated JCEF `CloudflareInterceptor`** replacing the no-op shim (§5): OSR solve, cache write, `callTimeout` carve-out. Makes it hands-free and covers background jobs.
3. **Phase 3 — keep FlareSolverr as fallback** for headless Docker (no display) + a proactive re-warm before expiry.

This sequences risk low→high and delivers a usable result at Phase 1.
