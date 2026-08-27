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
from seleniumbase import SB

app = Flask(__name__)
_lock = threading.Lock()
_sb_ctx = None  # the SB() context manager, entered once and kept open for the server's life
_sb = None      # the SB test object (has .uc_* helpers + .driver)
_origin = None  # origin the single tab is parked on (None = fresh)

# JS run IN the page: same-origin fetch, resolved via Selenium's async callback (arguments[-1]).
_FETCH_JS = (
    "var cb = arguments[arguments.length - 1];"
    "fetch(arguments[0], {credentials: 'include', headers: arguments[1]})"
    "  .then(function (r) { return r.text().then(function (t) {"
    "     cb(JSON.stringify({status: r.status, body: t})); }); })"
    "  .catch(function (e) { cb(JSON.stringify({status: 0, error: String(e)})); });"
)


def _get_sb():
    global _sb_ctx, _sb
    if _sb is None:
        print("solver: launching Chrome (SeleniumBase UC, xvfb)…", flush=True)
        # SB(xvfb=True) runs its own correctly-sized virtual display (so uc_gui_click_captcha's PyAutoGUI
        # clicks land on-screen). Enter the context manually and keep it open for the server's lifetime.
        # No cdp_mode — it would break execute_async_script + the captcha click.
        _sb_ctx = SB(uc=True, headless=False, xvfb=True)
        _sb = _sb_ctx.__enter__()
        print("solver: Chrome ready", flush=True)
    return _sb


def _cleared(sb) -> bool:
    try:
        return "just a moment" not in (sb.get_title() or "").lower()
    except Exception:
        return False


@app.get("/health")
def health():
    return jsonify(ok=_sb is not None, origin=_origin)


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
            sb = _get_sb()
            needs_open = _origin != origin
            if not needs_open:
                # cheap webdriver touch is fine once we're already cleared/parked
                needs_open = not _cleared(sb)
            if needs_open:
                # Canonical UC Cloudflare flow: open with the webdriver DISCONNECTED, then click the
                # Turnstile via PyAutoGUI (OS-level — never reconnects, so navigator.webdriver stays hidden).
                # Only AFTER the click + a wait do we touch the driver to check. Retry the whole cycle once.
                for attempt in range(3):
                    print(f"solver: opening {origin}/ (try {attempt + 1})…", flush=True)
                    sb.uc_open_with_reconnect(origin + "/", reconnect_time=5)
                    try:
                        sb.uc_gui_click_captcha()
                        print("solver: uc_gui_click_captcha done", flush=True)
                    except Exception as e:  # noqa: BLE001
                        print(f"solver: uc_gui_click_captcha error: {e}", flush=True)
                    time.sleep(6)  # let the challenge resolve after the click
                    if _cleared(sb):
                        break
                    print(f"solver: still challenged (title={sb.get_title()!r})", flush=True)
                print(f"solver: cleared={_cleared(sb)} title={sb.get_title()!r}", flush=True)
                _origin = origin

            raw = sb.driver.execute_async_script(_FETCH_JS, url, headers)
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
