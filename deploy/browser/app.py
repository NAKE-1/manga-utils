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
import threading

from flask import Flask, request, jsonify, Response

app = Flask(__name__)

WIDTH, HEIGHT = 440, 780          # keep in lockstep with JcefRemoteView.WIDTH/HEIGHT so the client's
                                  # frame->OSR coordinate math needs zero changes.
_lock = threading.Lock()          # selenium's driver is NOT thread-safe; serialize every op.
_driver = None
_current_url = ""


def _build_driver():
    """Create the one headed Chromium via undetected-chromedriver (headed passes Cloudflare on this box)."""
    import undetected_chromedriver as uc
    opts = uc.ChromeOptions()
    opts.binary_location = "/usr/bin/chromium"
    opts.add_argument("--no-sandbox")             # container: Chrome's sandbox can't start
    opts.add_argument("--disable-dev-shm-usage")  # avoid /dev/shm exhaustion in Docker
    opts.add_argument("--disable-gpu")
    opts.add_argument(f"--window-size={WIDTH},{HEIGHT}")
    # Use the system chromedriver (matches the Debian chromium build); uc patches a copy of it for stealth.
    d = uc.Chrome(options=opts, headless=False, use_subprocess=True,
                  driver_executable_path="/usr/bin/chromedriver")
    d.set_page_load_timeout(45)
    return d


def _ensure_driver():
    global _driver
    if _driver is None:
        print("browser: launching headed Chromium…", flush=True)
        _driver = _build_driver()
        print("browser: Chromium ready", flush=True)
    return _driver


def _pin_viewport(d):
    # Force the tab's rendered size to the OSR viewport so screenshots + tap coordinates line up 1:1.
    d.execute_cdp_cmd("Emulation.setDeviceMetricsOverride",
                      {"width": WIDTH, "height": HEIGHT, "deviceScaleFactor": 1, "mobile": True})


@app.get("/health")
def health():
    return jsonify(ok=True, open=_driver is not None, url=_current_url, w=WIDTH, h=HEIGHT)


@app.post("/webview/open")
def webview_open():
    global _current_url
    url = (request.get_json(silent=True) or {}).get("url") or request.args.get("url") or ""
    if not url.startswith("http"):
        return jsonify(status="failed", detail="a http(s) url is required"), 400
    with _lock:
        try:
            d = _ensure_driver()
            _pin_viewport(d)
            d.get(url)
            _current_url = url
            print(f"browser: opened {url}", flush=True)
            return jsonify(status="ready", w=WIDTH, h=HEIGHT, url=url)
        except Exception as e:
            print(f"browser: open failed: {e}", flush=True)
            return jsonify(status="failed", detail=str(e)), 500


@app.get("/webview/frame")
def webview_frame():
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
    global _driver, _current_url
    with _lock:
        if _driver is not None:
            try:
                _driver.quit()
            except Exception:
                pass
            _driver = None
            _current_url = ""
            print("browser: closed", flush=True)
    return ("", 200)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9000, threaded=True)
