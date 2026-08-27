# Plan #2 — FlareSolverr as the fetch engine for hard hosts (MangaFire)

**Goal:** stop trying to make JCEF pass Cloudflare in the container. Instead, let the browser that
*already* passes it here — FlareSolverr (headed Chrome on Xvfb) — **make the request itself** and hand
back the body. No fingerprint transplant, so nothing to re-challenge.

**Status: half-built already.** The pieces exist; this plan promotes a last-resort branch to the primary
path and makes it fast + correct for JSON.

---

## 1. Why this is the safe bet

- **Campus IP, the whole time** (not Mullvad). So the challenge isn't a fixable IP-reputation problem —
  the university ASN reads as non-residential *and* the offscreen browser is detectable. We can't cheaply
  change either. What we *can* rely on: **FlareSolverr passes Turnstile in this exact container, every
  time** (~11s cold). That's the one proven-good primitive. Build on it.
- The cookie-transplant approach (path A) fails because `cf_clearance` is bound to FS's browser
  fingerprint; JCEF (or okhttp) presenting it gets re-challenged. Making **FS do the fetch** sidesteps that
  entirely — the browser holding the clearance is the one requesting.

## 2. What already exists (so we're not starting cold)

In `FlareSolverrInterceptor.kt`:
- **Sessions are already on**: `session = "mangautils"`, `sessionTtlMinutes = 15`. FlareSolverr keeps a
  persistent cleared browser for that session, so a second `request.get` reuses the clearance (fast).
- **A rendered-body fallback already exists** (lines ~139–146): on the *last* attempt, for a GET text
  request, it calls `solve(request, returnOnlyCookies = false)` and returns `sol.response` (the page body)
  wrapped via `flareResponse()`.
- `FsSolution.response` already carries the rendered body; `flareResponse()` already wraps it as an OkHttp
  response.

**So the plumbing is there. The problems are: (a) it's last-resort, not primary, so we pay the whole
cookie-replay dance first; (b) it wraps everything as `text/html`, but MangaFire's `/api/titles` is JSON —
Chrome renders JSON inside a viewer, so the body isn't clean JSON.**

## 3. What changes

### 3a. Promote FS-rendered-body to the PRIMARY path for known-hard hosts
Add a small allowlist (or a per-source flag) — e.g. `mangafire.to` — for which we **skip** the
cookie-replay + JCEF seeding and go straight to `solve(request, returnOnlyCookies = false)` on the warm
session. For everything else, keep today's behavior (JCEF/desktop clears itself; cookie replay works).

Result for MangaFire: request → FS warm session `request.get(url-with-vrf)` → body back. First call ~11s
(cold session), subsequent calls ~1–3s (session reuse). No JCEF, no transplant, no re-challenge loop.

### 3b. Extract clean JSON from FS's rendered response
When Chrome loads a JSON URL it wraps it (a `<pre>` or the JSON viewer). Before returning:
- If the request path looks like an API/JSON endpoint (or the extension asked for JSON), strip the wrapper:
  take `document.body.innerText` equivalent — in practice, pull the text out of the returned HTML's `<pre>`
  (FlareSolverr returns `outerHTML`; the raw JSON is the `<pre>` text). Set `Content-Type: application/json`.
- Safer alternative: FlareSolverr can run `request.get` and, for many builds, the JSON body comes back
  fairly clean. Verify empirically first (one MangaFire `/api/titles` solve, inspect `sol.response`), then
  pick the minimal strip.

### 3c. Keep the session warm proactively (optional, for speed)
A 15-min TTL means the first request after idle pays the ~11s cold solve. Optionally ping the session with
a root `request.get` on a timer (or on the first sign of use) so interactive searches feel instant. Lazy
version: don't — accept one slow cold call per 15 min. Add the keep-warm only if it's annoying.

## 4. Pros / Cons

### Pros
- **Uses the one thing proven to work here.** No dependence on JCEF passing Turnstile (which may be
  impossible offscreen, and unproven headed).
- No fingerprint transplant → no re-challenge loop → no stampede, no "sometimes doesn't call FS."
- Most of the code already exists; this is a promotion + a JSON-extraction tweak, not a new subsystem.
- Independent of Plan B — ship it whether or not headed JCEF works.

### Cons / Risks
- **JSON-in-HTML extraction is fiddly** and could break if FlareSolverr/Chrome change how JSON renders.
  Needs one empirical check before committing to a strip strategy.
- **~11s cold per 15-min idle window** (mitigable with keep-warm). Warm is fast.
- FS `request.get` runs a full browser navigation per *cold* call — heavier than a same-origin fetch. Fine
  at search/browse cadence; a library update fanning out dozens of titles on a cold session could be slow
  until the session warms (then it's fine).
- Images still can't use this (a browser returns HTML, not binary) — but MangaFire images are on an
  un-gated CDN (`static.mfcdn.nl`, already 200 in the logs), so images don't need it.
- POST endpoints with JSON bodies can't be replayed cleanly through FS `request.post` (form-only) — but
  MangaFire's hard calls are GETs with the vrf in the URL, so this is a non-issue for it.

## 5. Rollout / test plan
1. **Empirical check first:** one MangaFire `/api/titles` solve with `returnOnlyCookies=false`; log the raw
   `sol.response` and confirm exactly how the JSON is wrapped. Pick the minimal extraction from that.
2. Add the hard-host allowlist + primary FS-fetch path behind a flag (`MU_FS_FETCH_HOSTS=mangafire.to`),
   default off, so it's opt-in and revertible.
3. Test cold (empty session) → expect ~11s, clean JSON, results render.
4. Test warm (immediately after) → expect ~1–3s.
5. Test a library update across several MangaFire titles → confirm it doesn't thrash (session reuse holds).
6. If clean: make the allowlist the default for MangaFire.

## 6. Files touched
- `source-api/.../FlareSolverrInterceptor.kt` — hard-host allowlist; promote rendered-body to primary for
  those hosts; JSON extraction from the rendered response; `Content-Type` fix.
- (maybe) `source-api/.../FlareSolverrConfig.kt` — the allowlist / keep-warm config, fed from settings/env.
- No JCEF changes; no Dockerfile changes.

## 7. Relationship to Plan B
Orthogonal. If B works, JCEF passes Turnstile itself and this is unnecessary for MangaFire. If B is dead,
this is the fallback that doesn't depend on beating Cloudflare's headless detection at all — it delegates
that to the browser that already wins here.
