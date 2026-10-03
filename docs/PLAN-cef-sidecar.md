# PLAN — Option B: move JCEF/CEF out-of-process (a CEF sidecar)

Goal: a `libcef` SIGILL should kill only a **helper process**, not the whole server. Investigation +
design. **No code yet.** Companion to the crash history in [[manga-utils-vm-cpu-host]].

## Why in-process can't be made crash-safe
JCEF runs Chromium (`libcef.so`) **inside the server JVM**. The crash is a native `SIGILL` on a
background CEF thread (`libcef.so+0x792ec42`, deterministic offset; the error-reporter itself SEGVs
reading the freed PC → a CEF memory-lifetime bug). A native crash in the JVM's own address space takes
the JVM down (`pid=1`) — no try/catch, no CPU flag, no init fix can contain it. Isolation therefore
requires a **separate OS process**. (JCEF's own helper subprocesses don't help — the crashing libcef is
the in-JVM one.)

## What actually uses CEF today (both in-process, one shared cookie jar)
1. **`JcefFetch`** (`android-compat/.../JcefFetch.kt`) — a per-host **pool of offscreen browsers** that
   clear Cloudflare once and then run the request as a same-origin `fetch()` (carrying the real
   TLS/JA4+HTTP2 fingerprint, cf_clearance, Referer, and the extension's `vrf`). This is the **primary
   programmatic Cloudflare bypass for all NON-MangaFire CF sources**. Invoked from
   `JcefFetchInterceptor` (a network interceptor, so it sees the vrf). MangaFire itself does NOT use
   this — it routes to the curl_cffi **solver** sidecar (`SolverClient`).
2. **`JcefRemoteView`** (`JcefRemoteView.kt`) — the interactive **OSR WebView** streamed to the phone as
   JPEG frames for manual challenge solving (+ the YOLO autosolve).
3. **Shared cookie jar** — both use the global `CefCookieManager`. A WebView solve writes `cf_clearance`
   there; `JcefFetch` reads it. `setCookie` also **seeds FlareSolverr's** cf_clearance into CEF. The
   okhttp cookie jar is separate — the only okhttp↔CEF bridge is that FS-seed. So **CEF owns its own
   cookie world**, which makes moving it out cleanly feasible.

Also in `android-compat/.../webkit`: `CefManager` (init/lifecycle), `CefHelper` (client factory),
`JcefHelper`, `JcefWebViewProvider`.

## The graceful-degradation we ALREADY have (this is what makes B cheap-ish)
`JcefFetchInterceptor` already treats a **null** JcefFetch result as "JCEF unavailable → fall back to
FlareSolverr / return the challenge" (lines 48-52). So if the CEF sidecar is **down/restarting**, the
main server already knows how to cope: it just falls back. We don't need to invent failure handling —
we need the CEF call to become an HTTP call that returns null when the sidecar is unreachable.

## Design — a third sidecar (mirrors the existing solver + flaresolverr sidecars)
The app already runs two sidecars; add a third. Because JCEF is JVM/Kotlin, the sidecar is a **small
standalone JVM app** wrapping the *existing* webkit code + a tiny HTTP server.

```
main server (JVM)                          cef-sidecar (JVM, own process/container)
  JcefFetchInterceptor ──HTTP /fetch──────▶  JcefFetch pool  ┐
  webview routes ──────HTTP /webview/*────▶  JcefRemoteView   ├─ share the CefCookieManager
  cookie/dev ops ──────HTTP /cookie/*─────▶  CefManager       ┘   (all in THIS process now)
                        (null/5xx ⇒ existing fallback)         a libcef SIGILL kills ONLY this,
                                                               Docker restart:unless-stopped revives it
```

### Sidecar HTTP API (thin wrappers over today's calls)
- `POST /fetch {url, method, headers, body}` → `{status, body, headers}` | 503 → replaces `JcefFetch.fetch`
- `POST /webview/open {url}` → `{status, w, h}` ; `GET /webview/frame` → JPEG ;
  `POST /webview/{input,scroll,click,autosolve}` → replaces the `JcefRemoteView` routes
- `POST /cookie/set`, `GET /cookie/hosts`, `POST /cookie/clear`, `GET /fetch/pools` (dev UI) → cookie/pool ops
- `GET /health` → `{ready, cefState}` (already have `CefManager.state()`)

### Main-server changes
- New `CefClient` (HTTP, like `SolverClient`) with short timeouts; returns null on any failure so the
  **existing** interceptor fallback fires.
- `JcefFetchInterceptor`: replace `JcefFetch.fetch(...)` with `cefClient.fetch(...)`.
- `Main.kt` webview routes: proxy to the sidecar (frame bytes passed through — already poll-based, so
  HTTP latency is a non-issue).
- Cookie/dev-menu ops (`cookieHosts`, `clearCookies`, `setCookie`, `poolStatus`): proxy to the sidecar.
- FS→CEF cf_clearance seed becomes `cefClient.setCookie(...)`.

### Packaging / deploy
- New Gradle module `:cef-sidecar` (depends on `:android-compat`, a minimal HTTP server — Ktor or
  `com.sun.net.httpserver`).
- Its own Docker image (same JCEF native + `--no-sandbox`/`--disable-gpu`/`MU_JCEF_NO_SANDBOX=1` setup —
  just relocated), or a second process in the existing image started in "cef mode".
- `docker-compose`: add a `cef` service on the same network; `restart: unless-stopped` so a crash
  self-revives. `MU_CEF_SIDECAR_URL` wires the main server to it (default off → in-process, so desktop
  builds are unchanged).

## What B buys / costs
**Buys:** the server stops dying — a CEF crash becomes a ~2s sidecar blip with automatic FlareSolverr
fallback in the meantime. Keeps BOTH the programmatic CF bypass and the interactive WebView.
**Costs:** a new module + image + compose service to build and maintain; a bit more RAM (a second JVM +
the browser pool live in the sidecar, not the main heap — arguably *better* isolation of the RAM too);
frame path goes over localhost HTTP (fine, already polled). Effort: **medium-large but well-bounded**,
and it reuses the sidecar pattern + the already-built fallback.

## The decision question to answer FIRST (cheaper than building B)
CEF/`JcefFetch` is now only the CF bypass for **non-MangaFire** sources (MangaFire = solver;
many sources aren't CF-gated at all). **FlareSolverr is also installed and can clear CF for those same
sources.** So before building a whole sidecar, measure how much CEF is actually pulling its weight:
- Grep the server log for `JCEF[` success lines vs `→ falling back` — how often does JcefFetch actually
  succeed and matter, per host?
- If ~all CF sources are covered by FlareSolverr + the solver anyway → **Option A (disable CEF, opt-in
  flag)** loses almost nothing and is a fraction of the work. B is only worth it if real sources depend
  on JcefFetch's in-session/vrf fetch that FS can't reproduce.

**Recommendation:** instrument first (one session of log-reading), then choose. If CEF still earns its
place, B is the right, well-scoped way to keep it without the crashes. If not, A wins on effort.
