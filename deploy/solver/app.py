"""
Anti-detect solver sidecar for manga-utils (Plan #3) — SeleniumBase UC engine.

Two obstacles, both handled in ONE real browser:
  1. Cloudflare Turnstile — SeleniumBase UC mode (real Google Chrome, headed on Xvfb) passes it,
     actively clicking the checkbox via uc_gui_click_captcha when the managed challenge lingers. (Plain
     nodriver + Debian chromium got stuck on "Just a moment..." forever — this is the fix.)
  2. MangaFire's /api is XHR-only — /fetch runs fetch(url,{credentials:'include'}) INSIDE the cleared
     page via execute_async_script, a genuine same-origin XHR (what JCEF did on Windows), returning clean
     JSON that a bare navigation can't get.

Endpoints:
  GET  /health              -> {ok, origin}
  POST /fetch {url,headers} -> {status, body, error?}

One Chrome, one tab, kept warm. Serialized with a lock (Selenium isn't thread-safe; a nav mutates shared
page state). Good enough for a personal server.
"""
import json
import threading
import time
from urllib.parse import urlparse

from flask import Flask, jsonify, request
from seleniumbase import Driver

app = Flask(__name__)
_lock = threading.Lock()
_driver = None
_origin = None  # origin the single tab is parked on (None = fresh)

# JS run IN the page: same-origin fetch, resolved via Selenium's async callback (arguments[-1]).
_FETCH_JS = (
    "var cb = arguments[arguments.length - 1];"
    "fetch(arguments[0], {credentials: 'include', headers: arguments[1]})"
    "  .then(function (r) { return r.text().then(function (t) {"
    "     cb(JSON.stringify({status: r.status, body: t})); }); })"
    "  .catch(function (e) { cb(JSON.stringify({status: 0, error: String(e)})); });"
)


def _get_driver():
    global _driver
    if _driver is None:
        print("solver: launching Chrome (SeleniumBase UC, xvfb)…", flush=True)
        # xvfb=True → SeleniumBase runs its own correctly-sized virtual display (so uc_gui_click_captcha's
        # PyAutoGUI clicks land on-screen). No cdp_mode — it would break execute_async_script + the click.
        _driver = Driver(uc=True, headless=False, xvfb=True)
        print("solver: Chrome ready", flush=True)
    return _driver


def _cleared(d) -> bool:
    try:
        return "just a moment" not in (d.title or "").lower()
    except Exception:
        return False


@app.get("/health")
def health():
    return jsonify(ok=_driver is not None, origin=_origin)


@app.post("/fetch")
def fetch():
    global _origin
    data = request.get_json(force=True, silent=True) or {}
    url = data.get("url")
    headers = data.get("headers") or {}
    if not url:
        return jsonify(status=0, error="no url"), 400
    p = urlparse(url)
    origin = f"{p.scheme}://{p.netloc}"

    with _lock:  # one Chrome; a navigation mutates shared page state
        try:
            d = _get_driver()
            if _origin != origin or not _cleared(d):
                print(f"solver: opening {origin}/ …", flush=True)
                d.uc_open_with_reconnect(origin + "/", reconnect_time=6)
                for attempt in range(4):
                    if _cleared(d):
                        break
                    print(f"solver: challenge up (title={d.title!r}) — click captcha (try {attempt + 1})", flush=True)
                    try:
                        d.uc_gui_click_captcha()
                    except Exception as e:  # noqa: BLE001
                        print(f"solver: uc_gui_click_captcha error: {e}", flush=True)
                    time.sleep(4)
                print(f"solver: cleared={_cleared(d)} title={d.title!r}", flush=True)
                _origin = origin

            raw = d.execute_async_script(_FETCH_JS, url, headers)
            out = json.loads(raw) if isinstance(raw, str) else (raw or {"status": 0, "error": "no result"})
            st, body = out.get("status"), (out.get("body") or "")
            tail = "" if st == 200 else f" snippet={body[:200]!r}"
            print(f"solver: /fetch {url} -> {st} {len(body)}B{tail}", flush=True)
            return jsonify(out)
        except Exception as e:  # noqa: BLE001 — report anything so the caller can fall back
            _origin = None  # tab may be wedged → force a fresh nav next time
            print(f"solver: error: {e}", flush=True)
            return jsonify(status=0, error=str(e)), 500


if __name__ == "__main__":
    # Single worker; the lock serializes anyway. No reloader (it would double-launch Chrome).
    app.run(host="0.0.0.0", port=8000, threaded=False, use_reloader=False)
