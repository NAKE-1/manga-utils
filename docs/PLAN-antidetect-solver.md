# Plan #3 — Stronger anti-detect solver sidecar (escalation past FlareSolverr)

**Goal:** if FlareSolverr's stock Chrome starts failing Turnstile on the campus ASN (or is too slow/flaky),
replace/augment it with a purpose-built anti-detect browser that passes more reliably on non-residential IPs
— and, ideally, does the fetch itself (like Plan #2) so there's no fingerprint transplant.

**This is the escalation.** Only reach for it if both B (headed JCEF) and #2 (FS-as-fetch-engine) prove
insufficient. It's the most infrastructure.

---

## 1. Why you might need it

- Campus ASN + datacenter-class fingerprint is the hard case Cloudflare is tuned to catch. FlareSolverr uses
  **stock** Chromium with light patches; Cloudflare periodically catches up to it. When it does, solves
  start failing or looping.
- The maintainer quote you found says the quiet part: on non-residential IPs, automation is *detectable* —
  so the solver's stealth quality is what determines whether you pass. A stronger solver buys margin.

## 2. Candidate engines (pick one)

**Crucial context: FlareSolverr already IS undetected-chromedriver** — it drives Chrome via Selenium + UC
internally. So an engine only helps if it's *newer/stronger* than the UC FlareSolverr bundles. Two common
suggestions are therefore lateral moves, not upgrades.

| Engine | Verdict | Why |
|---|---|---|
| **SeleniumBase UC (`uc=True`, `cdp_mode`)** | ✅ **Top pick** | Newer patches than FS's bundled UC; `cdp_mode` gives clean CDP → in-session `fetch()` returns **clean JSON** (Plan #2 win, no HTML-unwrap). Real `xvfb=True`. The GitHub thread uses exactly this. Note the `activate_cdp_mode` virtual-display gotcha they hit. Heaviest stack. |
| **nodriver** | ✅ **Lean pick** | Successor to undetected-chromedriver (same author); async CDP, **no** Selenium/chromedriver; strongest current Turnstile record; lighter than SeleniumBase. |
| **patchright** / **rebrowser-patches** | ⚠️ OK (JS route) | Stealth-patched Playwright; stronger than puppeteer-extra-stealth. Only if you want Node. |
| **undetected-chromedriver (plain UC)** | ❌ Lateral | ≈ what FlareSolverr already runs → inherits the same "caught up to stock UC" problem. No margin gained. |
| **puppeteer-extra-stealth** | ❌ Weakest | Oldest stealth layer; Cloudflare catches stock puppeteer-stealth often now. Use patchright instead if going Node. |
| **FlareSolverr fork w/ better patches** | ⚠️ Least code | Keep the /v1 API, swap the browser — but you inherit FS's cadence of getting caught. |

**Recommendation:** **SeleniumBase UC (cdp_mode)** or **nodriver** in a small Python sidecar that mimics
FlareSolverr's `/v1` contract (so our Kotlin side barely changes) AND adds a "fetch this URL in the cleared
session, return the body" call (Plan #2 semantics with a *stronger, newer* browser than FS's bundled UC).
Plain UC and puppeteer-stealth are lateral moves — skip them.

## 3. Architecture

- A new container next to `flaresolverr` (call it `solver`), Python + the chosen engine, headed on its own
  Xvfb.
- Exposes two endpoints:
  1. `POST /solve` → clear Cloudflare for a host, return `{cookies, userAgent}` (FlareSolverr-compatible).
  2. `POST /fetch` → **in the already-cleared session**, `fetch(url, {credentials:'include'})` (or a CDP
     `Network` fetch) and return `{status, headers, body}` — clean JSON, no HTML wrapper (this is the big
     win over FlareSolverr's rendered-outerHTML).
- Persistent session/browser kept warm so repeat calls are ~1–2s.
- Our Kotlin `FlareSolverrConfig.url` points at `solver` instead of (or in addition to) `flaresolverr`; the
  interceptor gains a `/fetch` path for hard hosts (mirrors Plan #2, but the sidecar returns clean JSON so
  no extraction hack).

## 4. Pros / Cons

### Pros
- **Best stealth available** — the engines here are what the scraping community uses specifically to beat
  Turnstile on datacenter IPs. Biggest margin against Cloudflare updates.
- `/fetch` returns **clean JSON** (in-page `fetch`), so none of Plan #2's HTML-unwrap fragility.
- Same-fingerprint fetch → no transplant, no re-challenge.
- FlareSolverr-compatible `/solve` means our existing Kotlin path keeps working; we only *add* `/fetch`.

### Cons / Risks
- **Most infrastructure** — a new sidecar we write and maintain (Python, Xvfb, an engine that itself needs
  updating when Cloudflare moves). This is real ongoing cost.
- Anti-detect engines are a **cat-and-mouse** — they break periodically and need version bumps. You're
  signing up to babysit it.
- More RAM/CPU (a second headed Chrome).
- The engines' own quirks (the SeleniumBase `activate_cdp_mode` virtual-display bug from your thread is a
  taste of this).
- Doesn't fix the root IP/ASN issue — just raises the stealth bar. A truly hostile Cloudflare config on a
  flagged ASN can still win; the ultimate lever is still a residential egress.

## 5. Rollout / test plan
1. Prototype the sidecar with `nodriver` or `patchright`: `/solve` + `/fetch`, persistent session.
2. Point a dev build's `FlareSolverrConfig.url` at it; verify `/solve` still satisfies the existing path.
3. Add the `/fetch` primary path for `mangafire.to` (behind a flag); confirm clean JSON + speed.
4. Soak test: cold, warm, and a full library update; watch for the engine getting caught over days.
5. If stable, make it the hard-host default; keep FlareSolverr as the generic fallback for other sources.

## 6. Files / infra touched
- **New:** `deploy/solver/` (Dockerfile + Python app) and a `solver` service in `docker-compose.yml`.
- `source-api/.../FlareSolverrInterceptor.kt` — add the `/fetch` call for hard hosts (or a new
  `SolverFetchInterceptor`).
- `source-api/.../FlareSolverrConfig.kt` — URL + hard-host config.
- No JCEF changes required (this can fully replace JCEF for hard hosts).

## 7. Decision guide (when to pick #3 over #2)
- Start with **#2** (FS-as-fetch-engine) — zero new containers, mostly-existing code.
- Escalate to **#3** only if: FlareSolverr starts *failing* Turnstile (not just being slow), or #2's
  JSON-unwrap proves too brittle, or you want the clean `/fetch` JSON contract badly enough to run a sidecar.
- The endgame lever above all of these remains a **residential egress** for MangaFire — #3 raises stealth
  but doesn't change the ASN Cloudflare sees.
