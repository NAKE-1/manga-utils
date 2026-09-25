"""
manga-utils browser sidecar — P2 (interactive WebView only).

Drives ONE headed Chromium (Xvfb) over the Chrome DevTools Protocol, exposing the same interactive
surface the in-process JCEF WebView had, but in its own process/container so a browser crash can't take
the server down:

  POST /webview/open   {url}      -> navigate, pin the 440x780 viewport         -> {status, w, h}
  GET  /webview/frame             -> Page.captureScreenshot (jpeg) of the tab   -> image/jpeg
  POST /webview/input  ?x&y       -> Input.dispatchMouseEvent press+release (a tap)
  POST /webview/scroll ?x&y&dy    -> Input.dispatchMouseEvent mouseWheel
  POST /webview/close             -> quit the browser
  GET  /health                    -> {ok, open}

Frames are pulled per request (the server already polls /frame ~7x/s), so no async CDP event plumbing is
needed. Autosolve / cookie sharing / CF fetch come in later phases; this phase is just "does it stream".
"""
import base64
import logging
import threading
import time

from flask import Flask, request, jsonify, Response

app = Flask(__name__)
# Quiet Flask/werkzeug's per-request access log — it spams one line per /frame, /scroll, /input (dozens/sec).
# We keep our own meaningful prints (open/close/ready/solve) instead.
logging.getLogger("werkzeug").setLevel(logging.WARNING)

IDLE_QUIT_SEC = 300   # close idle Chromium after 5 min to free RAM (a warm reopen is instant; a cold one ~2-3s)
_last_activity = time.time()


def _touch():
    global _last_activity
    _last_activity = time.time()


def _idle_reaper():
    """Quit Chromium after IDLE_QUIT_SEC of no activity so it doesn't hold ~400MB while nobody's using it."""
    global _driver
    while True:
        time.sleep(30)
        with _lock:
            if _driver is not None and (time.time() - _last_activity) > IDLE_QUIT_SEC:
                try:
                    _driver.quit()
                except Exception:
                    pass
                _driver = None
                print("browser: idle — Chromium closed to free RAM", flush=True)

WIDTH, HEIGHT = 440, 780          # keep in lockstep with JcefRemoteView.WIDTH/HEIGHT so the client's
                                  # frame->OSR coordinate math needs zero changes.
_lock = threading.Lock()          # selenium's driver is NOT thread-safe; serialize every op.
_driver = None                    # the ready driver, or None while cold/warming
_current_url = ""
_warming = False                  # a background launch is in flight
_warm_error = ""                  # last launch failure, surfaced as status=failed


def _build_driver():
    """Create the one headed Chromium via undetected-chromedriver (headed passes Cloudflare on this box)."""
    import undetected_chromedriver as uc
    opts = uc.ChromeOptions()
    opts.binary_location = "/usr/bin/chromium"
    opts.add_argument("--no-sandbox")             # container: Chrome's sandbox can't start
    opts.add_argument("--disable-dev-shm-usage")  # avoid /dev/shm exhaustion in Docker
    opts.add_argument("--disable-gpu")
    opts.add_argument(f"--window-size={WIDTH},{HEIGHT}")
    # Don't block get() until the whole page finishes: an interactive view streams the load via frames,
    # and a Cloudflare-gated page (MangaFire) never "finishes" — a normal strategy hangs open() forever.
    opts.page_load_strategy = "none"
    # Use the system chromedriver (matches the Debian chromium build); uc patches a copy of it for stealth.
    d = uc.Chrome(options=opts, headless=False, use_subprocess=True,
                  driver_executable_path="/usr/bin/chromedriver")
    d.set_page_load_timeout(45)
    return d


def _warm():
    """Launch Chromium off the request thread (cold start ~15-30s) so /open returns 'starting' immediately
    and the client's existing auto-retry drives it, instead of blocking the request for 30s."""
    global _driver, _warming, _warm_error
    try:
        d = _build_driver()
        with _lock:
            _driver = d
        print("browser: Chromium ready", flush=True)
    except Exception as e:
        _warm_error = str(e)
        print(f"browser: warm failed: {e}", flush=True)
    finally:
        _warming = False


def _start_warm():
    global _warming, _warm_error
    if _driver is None and not _warming:
        _warming = True
        _warm_error = ""
        threading.Thread(target=_warm, daemon=True).start()


def _pin_viewport(d):
    # Force the tab's rendered size to the OSR viewport so screenshots + tap coordinates line up 1:1.
    d.execute_cdp_cmd("Emulation.setDeviceMetricsOverride",
                      {"width": WIDTH, "height": HEIGHT, "deviceScaleFactor": 1, "mobile": True})


@app.get("/health")
def health():
    state = "ready" if _driver is not None else ("failed" if _warm_error else ("starting" if _warming else "cold"))
    return jsonify(ok=True, open=_driver is not None, state=state, url=_current_url, w=WIDTH, h=HEIGHT)


@app.post("/webview/open")
def webview_open():
    global _current_url
    url = (request.get_json(silent=True) or {}).get("url") or request.args.get("url") or ""
    if not url.startswith("http"):
        return jsonify(status="failed", detail="a http(s) url is required"), 400
    _touch()
    # Not up yet → kick off the (lazy, background) launch and tell the client to retry — same "Starting…"
    # UX the CEF path uses. Chromium only runs after the first WebView open (no RAM when unused).
    if _driver is None:
        if _warm_error:
            return jsonify(status="failed", detail=_warm_error), 202
        _start_warm()
        return jsonify(status="starting", url=url), 202
    with _lock:
        try:
            _pin_viewport(_driver)
            _driver.get(url)
            _current_url = url
            print(f"browser: opened {url}", flush=True)
            return jsonify(status="ready", w=WIDTH, h=HEIGHT, url=url)
        except Exception as e:
            print(f"browser: open failed: {e}", flush=True)
            return jsonify(status="failed", detail=str(e)), 500


@app.get("/webview/frame")
def webview_frame():
    _touch()
    with _lock:
        if _driver is None:
            return ("", 204)
        try:
            res = _driver.execute_cdp_cmd("Page.captureScreenshot", {"format": "jpeg", "quality": 55})
            return Response(base64.b64decode(res["data"]), mimetype="image/jpeg")
        except Exception as e:
            print(f"browser: frame failed: {e}", flush=True)
            return ("", 204)


@app.post("/webview/input")
def webview_input():
    x = int(request.args.get("x", 0)); y = int(request.args.get("y", 0))
    with _lock:
        if _driver is None:
            return ("", 409)
        try:
            for t in ("mousePressed", "mouseReleased"):
                _driver.execute_cdp_cmd("Input.dispatchMouseEvent",
                                        {"type": t, "x": x, "y": y, "button": "left", "clickCount": 1})
            return ("", 200)
        except Exception as e:
            print(f"browser: input failed: {e}", flush=True)
            return ("", 500)


@app.post("/webview/scroll")
def webview_scroll():
    x = int(request.args.get("x", 0)); y = int(request.args.get("y", 0))
    dy = int(request.args.get("dy", 0))
    with _lock:
        if _driver is None:
            return ("", 409)
        try:
            _driver.execute_cdp_cmd("Input.dispatchMouseEvent",
                                    {"type": "mouseWheel", "x": x, "y": y, "deltaX": 0, "deltaY": dy})
            return ("", 200)
        except Exception as e:
            print(f"browser: scroll failed: {e}", flush=True)
            return ("", 500)


@app.post("/webview/close")
def webview_close():
    # Keep Chromium WARM (like JCEF): just blank the page so the next open is instant, instead of quitting
    # and paying the ~2-3s relaunch every time. The idle reaper quits it later if nobody reopens.
    global _current_url
    _touch()
    with _lock:
        if _driver is not None:
            try:
                _driver.get("about:blank")
            except Exception:
                pass
            _current_url = ""
            print("browser: page closed (Chromium kept warm)", flush=True)
    return ("", 200)


if __name__ == "__main__":
    threading.Thread(target=_idle_reaper, daemon=True).start()
    app.run(host="0.0.0.0", port=9000, threaded=True)
