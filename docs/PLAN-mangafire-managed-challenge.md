# Plan — Survive Cloudflare's *managed challenge* on MangaFire (the 5-hour-download wall)

## What happened (from the 2026-08-18 log)

MangaFire mass-downloaded fine for ~5 hours, then hit a wall it never recovered from:

```
403 challenge on /api/… — reloading root to re-clear, retry once
JCEF: mangafire.to didn't visibly clear Cloudflare within 45s — trying the fetch anyway
403 … in 45358ms   body: <Just a moment...>
→ will retry in ~10min → ~15min → ~20min → DOWNLOAD FAILED (37, 92, 146 chapters)
```

**This is Cloudflare's *managed challenge* (`Just a moment…`), not MangaFire's shape-captcha.** Two
different walls, and only one of them is auto-solvable:

| Wall | Detected as | Handler | Worked in the log? |
|---|---|---|---|
| MangaFire WAF `/@waf/challenge` (click shapes) | `isInteractive` | ONNX auto-solver / WebView | ✅ solved twice (20:14, 20:45) |
| Cloudflare managed challenge (`Just a moment…`) | `isChallenge && !isInteractive` | JCEF silent "reload root", 45 s timeout | ❌ never clears |

**Why it appeared:** ~5 h of sustained high-volume downloading (plus a root-GET before each `/api` call,
the `200 / … 2135B` lines) made Cloudflare flag the IP/session as bot-like and escalate to a managed
challenge. Exactly the load-induced scenario we predicted.

**Why it spirals instead of recovering** (`JcefFetch.kt:86-89`): the managed-challenge branch reloads root
and retries **once**; if that fails it returns the 403. The download queue then sets a ~10–20 min cooldown
and retries — firing *another* 45 s-timeout request into an already-angry Cloudflare, which **re-provokes**
the flag. It never decays, so every series marches to FAILED. A server restart (fresh `cf_clearance`)
resumes it — until the load trips it again.

**Key constraint:** the auto-solver **cannot** clear a managed challenge — there are no shapes to click.
Only a real interactive browser session (a human, or a Turnstile the WebView renders) or **waiting it out**
clears it. So the two runtime modes need different answers:
- **Attended** (you're at the UI): escalate to the WebView human-check so you can clear it.
- **Unattended** (overnight / the Proxmox box): there's no human — the only move is **stop hammering and
  wait** on a long cooldown so CF's flag decays, then probe gently.

## The fix

### Part A — Escalate a *stuck* managed challenge to a human-check (reuse existing machinery)

Today the `isChallenge && !isInteractive` path silently 45 s-times-out forever. Make it escalate:

- In `JcefFetch.fetch` (`JcefFetch.kt:86`), track **consecutive managed-challenge clear-failures per host**
  (a small `ConcurrentHashMap<String,Int>`, reset to 0 on any 2xx for that host).
- After **N=2** failed re-clears in a row, stop treating it as "transient stale cookie" and treat it like
  the interactive path: let the interceptor flag it. `JcefFetchInterceptor` already calls
  `HumanCheckState.needManual(host)` + `Notifier.onHumanCheckNeeded` for interactive challenges — route the
  stuck-managed case through the same flag.

This immediately buys the **existing** "waiting for verification → resume on clear" behaviour we built for
the shape-captcha: the download queue pauses the source and resumes the moment the host clears, instead of
retry-looping into FAILED. It also surfaces the yellow banner so — when attended — you can open the WebView
and clear the managed challenge yourself (a real browser session is the reliable clear).

Note: `maybeAutoSolve` will run and fail (no shapes) — that's fine, it falls through to `needManual`. We
should **not** spam the "solver failed" Discord ping for a managed challenge; gate that ping to the
interactive/shape path only.

### Part B — Long cool-down + gentle probe when there's no human (unattended)

Escalating to a human-check is useless overnight, so pair it with a back-off that lets CF calm down:

- When a host is flagged managed-challenge-stuck (Part A) **and** no one clears it within a short window,
  put the **whole source** to sleep for a **long** cooldown — **30–60 min**, not the current ~10 — via the
  existing `sourceCooldownUntil` in `DownloadQueue`. Escalate the cooldown on repeat (30 → 45 → 60), capped.
- On wake, probe with **one** gentle request (not the whole fan-out). If it clears, resume; if it's still
  challenged, back to the long cooldown. The point is: **never** hammer 45 s-timeout requests back-to-back —
  that's what keeps CF angry.

Together A+B mean: attended, you get a one-click WebView clear; unattended, the source rests until CF's flag
decays instead of burning the queue down.

### Part C — Reduce the load that trips it (smaller, do-later)

The managed challenge is *provoked* by request volume. Two cheap reductions:
- The **root-GET before each `/api` call** (the `200 / … 2135B` lines) roughly doubles MangaFire's request
  rate. Confirm whether that's `SessionWarmup` (should be one-shot per client) or the JCEF per-call
  clearance check firing every time — if the latter, cache "host is currently cleared" for a short TTL so we
  don't re-warm on every request.
- Keep MangaFire concurrency low: **do not** turn on same-source-parallel for it, and consider a gentler
  per-source pace for JCEF-gated sources during mass-downloads.

## Scope / files

- `android-compat/.../webkit/JcefFetch.kt` — per-host managed-challenge failure counter + escalation hook.
- `source-api/.../interceptor/JcefFetchInterceptor.kt` — route stuck-managed → `HumanCheckState.needManual`.
- `server/.../DownloadQueue.kt` (or wherever `sourceCooldownUntil` lives) — long/escalating cooldown +
  single-probe-on-wake for gated sources.
- `server/.../Notifier.kt` — don't fire "solver failed" for managed (non-shape) challenges.
- Part C: `JcefFetch.kt` clearance-check caching (only if it's re-warming per call).

Estimate: Part A ≈ small (a counter + reuse the existing flag). Part B ≈ small–medium (cooldown policy).
Part C ≈ investigate first, then small.

## Verification

1. Force a managed challenge (hammer MangaFire) → after 2 failed re-clears it flags **needs verification**
   (yellow banner + queue pauses) instead of 45 s-looping.
2. Attended: open WebView, clear it → downloads **resume** (existing resume-on-clear path).
3. Unattended: no one clears → source sleeps ~30–60 min, then a **single** probe (not a fan-out); confirm no
   back-to-back 45 s timeouts in the log.
4. No "solver failed" Discord ping for the managed (`Just a moment…`) case; shape-captcha ping still fires.
5. Big MangaFire mass-download runs longer before tripping (Part C), and recovers on its own after a rest.

## Open questions

1. **Escalation threshold N** — flag after 2 failed re-clears, or 3? (2 = faster recovery, 3 = fewer false
   "needs verification" on a genuinely-transient stale cookie.)
2. **Unattended cooldown** — fixed 45 min, or escalating 30 → 45 → 60? (I lean escalating, capped at 60.)
3. **Part C now or later?** — I'd investigate the per-call root-GET first (it may be the biggest lever) but
   ship A+B first since they stop the burn-down regardless.
