"""
Anti-detect solver sidecar for manga-utils (Plan #3).

Why this exists: MangaFire (and sites like it) put TWO obstacles in the way —
  1. Cloudflare Turnstile (a bot wall), and
  2. an /api that only returns data to a same-origin XHR from within the page.
JCEF-in-a-container fails #1 (its offscreen fingerprint is flagged); FlareSolverr
clears #1 but does a top-level *navigation*, which fails #2 (the API hands a bare
navigation an empty stub). This sidecar does BOTH in one real browser:
  - nodriver (undetected-chromedriver's async successor) drives a headed Chromium
    on Xvfb, whose fingerprint Cloudflare accepts, and
  - /fetch runs `fetch(url, {credentials:'include'})` INSIDE the cleared page via
    CDP — a genuine same-origin XHR, exactly what JCEF did on Windows — returning
    clean JSON.

Endpoints:
  GET  /health          -> {ok, origin}
  POST /fetch {url,headers} -> {status, body, error?}  (in-page same-origin fetch)

One browser, one tab, kept warm. Serialized with a lock (one Chromium, and a
navigation changes shared page state). Good enough for a personal server; scale
later with a tab pool if needed.
"""
import asyncio
import json
import os
from urllib.parse import urlparse

import nodriver as uc
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

CHROME = os.environ.get("CHROME_BIN", "/usr/bin/chromium")
CLEAR_TIMEOUT_S = int(os.environ.get("SOLVER_CLEAR_TIMEOUT", "35"))

app = FastAPI()
_browser = None
_lock = asyncio.Lock()
_origin = None  # the origin the single tab is currently parked on (None = fresh)


async def _get_browser():
    global _browser
    if _browser is None:
        _browser = await uc.start(
            headless=False,  # headed on Xvfb — Cloudflare reads a real window + GL
            browser_executable_path=CHROME,
            browser_args=[
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1920,1080",
                "--disable-blink-features=AutomationControlled",
            ],
        )
    return _browser


async def _has_clearance(b) -> bool:
    try:
        cookies = await b.cookies.get_all()
    except Exception:
        return False
    return any(getattr(c, "name", "") == "cf_clearance" for c in cookies)


async def _ensure_on_origin(b, origin: str):
    """Park the tab on `origin` (navigating clears Cloudflare + sets the site session cookie), so a
    subsequent in-page fetch is same-origin. Reuses the tab if already parked there."""
    global _origin
    if _origin == origin:
        return b.main_tab
    tab = await b.get(origin + "/")
    # Give nodriver + Cloudflare time to clear (nodriver auto-handles the Turnstile). Wait for the
    # cf_clearance cookie, then a beat for the page's own session to settle.
    for _ in range(CLEAR_TIMEOUT_S):
        if await _has_clearance(b):
            break
        await asyncio.sleep(1)
    await asyncio.sleep(1)
    _origin = origin
    return tab


@app.get("/health")
async def health():
    return {"ok": _browser is not None, "origin": _origin}


@app.post("/fetch")
async def fetch(req: Request):
    global _origin
    data = await req.json()
    url = data.get("url")
    headers = data.get("headers") or {}
    if not url:
        return JSONResponse({"status": 0, "error": "no url"}, status_code=400)
    parsed = urlparse(url)
    origin = f"{parsed.scheme}://{parsed.netloc}"

    async with _lock:  # one Chromium; a navigation mutates shared page state
        try:
            b = await _get_browser()
            tab = await _ensure_on_origin(b, origin)
            # Same-origin fetch from inside the cleared page. credentials:'include' sends cf_clearance +
            # the site session cookie; being ON the page supplies the Referer + origin the API checks.
            js = (
                "(async () => { try {"
                f"  const r = await fetch({json.dumps(url)}, {{credentials:'include', headers:{json.dumps(headers)}}});"
                "   const t = await r.text();"
                "   return JSON.stringify({status: r.status, body: t});"
                " } catch (e) { return JSON.stringify({status: 0, error: String(e)}); }"
                " })()"
            )
            raw = await tab.evaluate(js, await_promise=True)
            out = json.loads(raw) if isinstance(raw, str) else (raw or {"status": 0, "error": "no result"})
            return JSONResponse(out)
        except Exception as e:  # noqa: BLE001 — report anything so the caller can fall back
            _origin = None  # force a fresh navigation next time (tab may be wedged)
            return JSONResponse({"status": 0, "error": str(e)}, status_code=500)
