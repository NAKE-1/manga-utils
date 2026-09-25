"""
manga-utils browser sidecar — P2 (interactive WebView only).

Drives ONE headed Chromium (Xvfb) over the Chrome DevTools Protocol, exposing the same interactive
surface the in-process JCEF WebView had, but in its own process/container so a browser crash can't take
the server down:

  POST /webview/open   {url}      -> navigate, pin the 440x780 viewport         -> {status, w, h}
  GET  /webview/frame             -> latest screencast JPEG (pushed by Chrome)  -> image/jpeg
  POST /webview/input  ?x&y       -> Input.dispatchMouseEvent press+release (a tap)
  POST /webview/scroll ?x&y&dy    -> Input.dispatchMouseEvent mouseWheel
  POST /webview/close             -> quit the browser
  GET  /health                    -> {ok, open}

Frames are event-driven: a background thread opens its OWN websocket to Chromium's DevTools and runs
Page.startScreencast, so Chrome PUSHES a JPEG on every repaint (the CDP analog of JCEF's onPaint). This
replaced the old Page.captureScreenshot poll, whose synchronous "render a frame now" call held the driver
lock and, when the renderer lagged, starved every other op until the thread pool drained.
"""
import base64
import json
import logging
import os
import threading
import time
import urllib.request

import websocket
from flask import Flask, request, jsonify, Response

app = Flask(__name__)
# MU_BROWSER_VERBOSE=1 un-quiets werkzeug's per-request access log for diagnosing. Off by default (it spams
# one line per /frame etc.); we keep our own meaningful prints (open/close/ready/solve) regardless.
VERBOSE = os.environ.get("MU_BROWSER_VERBOSE", "") not in ("", "0", "false", "False")
logging.getLogger("werkzeug").setLevel(logging.INFO if VERBOSE else logging.WARNING)

IDLE_QUIT_SEC = 900   # close idle Chromium after 15 min (5 min was too aggressive — constant cold relaunches)
_last_activity = time.time()


def _touch():
    global _last_activity
    _last_activity = time.time()


def _idle_reaper():
    """Quit Chromium after IDLE_QUIT_SEC of no activity so it doesn't hold ~400MB while nobody's using it.
    CRITICAL: null the reference under the lock, then quit() OUTSIDE it — a hung quit() must NOT hold the
    lock (that deadlocks every /open behind it), and _driver must already be None so the next open re-warms."""
    global _driver
    while True:
        time.sleep(30)
        victim = None
        with _lock:
            if _driver is not None and (time.time() - _last_activity) > IDLE_QUIT_SEC:
                victim = _driver
                _driver = None
        if victim is not None:
            try:
                victim.quit()
            except Exception:
                pass
            print("browser: idle — Chromium closed to free RAM", flush=True)

DEVTOOLS_PORT = 9222              # fixed Chromium DevTools port the screencast websocket connects to
WIDTH, HEIGHT = 440, 780          # keep in lockstep with JcefRemoteView.WIDTH/HEIGHT so the client's
                                  # frame->OSR coordinate math needs zero changes.
_lock = threading.Lock()          # selenium's driver is NOT thread-safe; serialize every op.
_driver = None                    # the ready driver, or None while cold/warming
_current_url = ""
_warming = False                  # a background launch is in flight
_warm_error = ""                  # last launch failure, surfaced as status=failed
_frame_cache = None               # latest JPEG, pushed by _screencast_loop; /frame serves this WITHOUT the lock
_frame_wanted_until = 0.0         # capture only while a client is actively polling /frame
_nav_gen = 0                      # bumped on each navigate so the screencast re-arms after a page change
_fps = 0.0                        # frames/sec pushed by Chrome, updated ~every 5s (surfaced on /health)


def _build_driver_once():
    """Create the one headed Chromium via undetected-chromedriver (headed passes Cloudflare on this box)."""
    import undetected_chromedriver as uc
    opts = uc.ChromeOptions()
    opts.binary_location = "/usr/bin/chromium"
    opts.add_argument("--no-sandbox")             # container: Chrome's sandbox can't start
    opts.add_argument("--disable-dev-shm-usage")  # avoid /dev/shm exhaustion in Docker
    opts.add_argument("--disable-gpu")
    opts.add_argument(f"--window-size={WIDTH},{HEIGHT}")
    # Pin a known DevTools port so the screencast thread can always reach it, even if the driver doesn't
    # report debuggerAddress as a capability (undetected-chromedriver sometimes doesn't).
    opts.add_argument(f"--remote-debugging-port={DEVTOOLS_PORT}")
    # Don't block get() until the whole page finishes: an interactive view streams the load via frames,
    # and a Cloudflare-gated page (MangaFire) never "finishes" — a normal strategy hangs open() forever.
    opts.page_load_strategy = "none"
    # Use the system chromedriver (matches the Debian chromium build); uc patches a copy of it for stealth.
    d = uc.Chrome(options=opts, headless=False, use_subprocess=True,
                  driver_executable_path="/usr/bin/chromedriver")
    d.set_page_load_timeout(45)
    # CRITICAL: cap how long any command waits on chromedriver. A Cloudflare challenge page can pin the
    # renderer's main thread, and chromedriver's DEFAULT wait is 600s — that command holds our lock the whole
    # time and wedges the sidecar ("unreachable"). Fail in ~12s instead: a hung page becomes a recoverable
    # blip, the lock frees, and the pump/clicks resume once the renderer settles.
    try:
        d.command_executor.set_timeout(8)
    except Exception as e:
        print(f"browser: couldn't set command timeout: {e}", flush=True)
    return d


def _build_driver():
    """Launch with one retry — undetected-chromedriver's cold start is occasionally flaky (driver/version
    race, Xvfb timing). A single retry turns most of those transient failures into a clean start instead
    of a failed warm the user sees as 'unreachable'."""
    last = None
    for i in range(2):
        try:
            return _build_driver_once()
        except Exception as e:
            last = e
            print(f"browser: Chromium launch attempt {i + 1}/2 failed: {e}", flush=True)
            time.sleep(2)
    raise last


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
    return jsonify(ok=True, open=_driver is not None, state=state, url=_current_url, w=WIDTH, h=HEIGHT,
                   fps=round(_fps, 1))


@app.post("/webview/open")
def webview_open():
    global _current_url, _driver, _frame_cache, _nav_gen
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
            # CDP Page.navigate returns immediately after issuing the navigation — unlike driver.get(), which
            # blocks until the page "loads" (undetected-chromedriver ignores page_load_strategy, and a
            # Cloudflare page never finishes). The page then streams in via /frame.
            _driver.execute_cdp_cmd("Page.navigate", {"url": url})
            _current_url = url
            _nav_gen += 1        # tell the screencast loop to re-arm so frames resume on the new page
            _frame_cache = None  # drop the previous page's frame so the view doesn't show stale content
            print(f"browser: opened {url}", flush=True)
            return jsonify(status="ready", w=WIDTH, h=HEIGHT, url=url)
        except Exception as e:
            # The driver died (crashed / was reaped mid-flight). Drop it so the next open cold-launches
            # fresh, and tell the client to retry — don't wedge on a dead handle.
            print(f"browser: open failed ({e}) — dropping driver, will re-warm", flush=True)
            _driver = None
            return jsonify(status="starting", detail=str(e)[:200]), 202


@app.get("/webview/frame")
def webview_frame():
    # HOT PATH (~7/s): serve the cached frame with NO driver lock. _screencast_loop fills _frame_cache from
    # Chrome's pushed frames, so a burst of frame polls can never contend for the lock or block on a busy
    # Chromium — the thing that made the sidecar time out and read as "unreachable" under load.
    global _frame_wanted_until
    _touch()
    _frame_wanted_until = time.time() + 2.0  # keep the pump capturing while we're polling
    if _frame_cache is None:
        return ("", 204)
    return Response(_frame_cache, mimetype="image/jpeg")


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
                _driver.execute_cdp_cmd("Page.navigate", {"url": "about:blank"})  # non-blocking, like open
            except Exception:
                pass
            _current_url = ""
            print("browser: page closed (Chromium kept warm)", flush=True)
    return ("", 200)


# ---- YOLO shape-captcha autosolve (P3) — reads MangaFire's live challenge, runs the ONNX detector, clicks
# the shapes via CDP. Same read JS + A->B match + viewport-coordinate math as the JVM's autoSolveLiveCaptcha.
AUTOSOLVE_TRIES = 6
# Same DOM read the JVM uses: #main = the grid image (B), #thumb = the order strip (A).
CAPTCHA_READ_JS = (
    "(function(){var m=document.getElementById('main'),t=document.getElementById('thumb');"
    "if(!m||!t||!m.naturalWidth)return JSON.stringify({error:'no shape-captcha on this page'});"
    "var r=m.getBoundingClientRect();return JSON.stringify({a:t.src,b:m.src,"
    "rect:{left:r.left,top:r.top,width:r.width,height:r.height},nw:m.naturalWidth,nh:m.naturalHeight});})()"
)

_captcha = None


def _captcha_mod():
    """Lazy-load the ONNX detector (heavy) only when autosolve is first used."""
    global _captcha
    if _captcha is None:
        import captcha as c
        _captcha = c
    return _captcha


def _cdp(cmd, params=None):
    """One CDP command under the lock. Locking PER-OP (not around the whole solve) lets /frame interleave
    so the streamed view keeps updating while we click, and never holds the lock for the ~seconds a solve
    takes."""
    with _lock:
        if _driver is None:
            raise RuntimeError("no driver")
        return _driver.execute_cdp_cmd(cmd, params or {})


def _eval_js(expr):
    return (_cdp("Runtime.evaluate", {"expression": expr, "returnByValue": True}) or {}).get("result", {}).get("value")


def _cdp_click(x, y):
    for t in ("mousePressed", "mouseReleased"):
        _cdp("Input.dispatchMouseEvent", {"type": t, "x": x, "y": y, "button": "left", "clickCount": 1})


def _on_challenge():
    p = _eval_js("(function(){return location.pathname;})()") or ""
    return "@waf" in p or "challenge" in p


@app.post("/webview/autosolve")
def webview_autosolve():
    import json as _json
    import random
    import time as _time
    if _driver is None:
        return jsonify(solved=False, detected=0, clicked=0, tries=0, message="open the challenge first"), 200
    _touch()
    cap = _captcha_mod()
    detected = 0
    # Log what the page actually is, once — the usual "failure" is the shape grid not being present.
    try:
        loc = _eval_js("(function(){return location.href+' | title='+document.title;})()") or ""
    except Exception as e:
        print(f"browser: autosolve — page unresponsive ({e})", flush=True)
        return jsonify(solved=False, detected=0, clicked=0, tries=0,
                       message="page is unresponsive (renderer busy) — can't autosolve"), 200
    print(f"browser: autosolve on {loc}", flush=True)
    # Cloudflare's Turnstile interstitial ("Security check" / "Just a moment") comes BEFORE the shape captcha
    # and pins the renderer — there's nothing to click, and looping/hammering it wedges the sidecar. Bail now.
    low = loc.lower()
    if any(s in low for s in ("security check", "just a moment", "checking your", "attention required", "verify you are human")):
        print("browser: autosolve — Cloudflare interstitial, not the shape captcha — bailing", flush=True)
        return jsonify(solved=False, detected=0, clicked=0, tries=0,
                       message="Cloudflare check (not the shape captcha) — MangaFire is handled by the solver, the browser can't pass this"), 200
    for attempt in range(1, AUTOSOLVE_TRIES + 1):
        _touch()
        try:
            raw = _eval_js(CAPTCHA_READ_JS)
        except Exception as e:
            print(f"browser: autosolve — read timed out ({e})", flush=True)
            return jsonify(solved=False, detected=0, clicked=0, tries=attempt,
                           message="page unresponsive — can't read the captcha"), 200
        if not raw:
            print("browser: autosolve — DOM read returned nothing", flush=True)
            return jsonify(solved=False, detected=0, clicked=0, tries=attempt, message="couldn't read the page"), 200
        try:
            dom = _json.loads(raw)
        except Exception:
            print(f"browser: autosolve — non-JSON read: {str(raw)[:120]}", flush=True)
            return jsonify(solved=False, detected=0, clicked=0, tries=attempt, message="page returned no JSON"), 200
        if dom.get("error"):
            print(f"browser: autosolve — {dom['error']}", flush=True)
            return jsonify(solved=False, detected=0, clicked=0, tries=attempt, message=dom["error"]), 200
        a, b = dom.get("a", ""), dom.get("b", "")
        nw, nh, rect = dom.get("nw", 0), dom.get("nh", 0), dom.get("rect", {})
        if not a or not b or nw <= 0 or nh <= 0:
            _cdp("Page.reload", {}); _time.sleep(1.5); continue
        try:
            clicks, missing = cap.solve(cap.decode_data_uri(a), cap.decode_data_uri(b))
        except Exception as e:
            print(f"browser: autosolve detect failed: {e}", flush=True)
            return jsonify(solved=False, detected=0, clicked=0, tries=attempt, message="detect/solve failed — see log"), 200
        detected = len(clicks) + len(missing)
        if not clicks or missing:
            print(f"browser: autosolve try {attempt}: incomplete (missing {missing}) — refreshing", flush=True)
            _cdp("Page.reload", {}); _time.sleep(1.5); continue
        for (cx, cy) in clicks:
            vx = rect["left"] + (cx / nw) * rect["width"] + random.randint(-2, 2)
            vy = rect["top"] + (cy / nh) * rect["height"] + random.randint(-2, 2)
            _cdp_click(vx, vy)
            _time.sleep(random.uniform(1.0, 1.3))  # human pacing; lock is free here so frames keep flowing
        # wait up to 8s for the page to navigate off the challenge (= passed). The verify/redirect can itself
        # trigger a fresh Cloudflare check that FREEZES the renderer — catch that so it fails cleanly instead
        # of crashing the request, and report it honestly.
        deadline = _time.time() + 8
        passed = False
        froze = False
        while _time.time() < deadline:
            try:
                if not _on_challenge():
                    passed = True
                    break
            except Exception:
                froze = True
                break
            _time.sleep(0.5)
        if passed:
            print(f"browser: autosolve SOLVED in {len(clicks)} clicks (try {attempt})", flush=True)
            return jsonify(solved=True, detected=detected, clicked=len(clicks), tries=attempt,
                           message=f"solved in {len(clicks)} clicks"), 200
        if froze:
            print(f"browser: autosolve clicked {len(clicks)} but the verify/redirect froze (Cloudflare)", flush=True)
            return jsonify(solved=False, detected=detected, clicked=len(clicks), tries=attempt,
                           message=f"clicked {len(clicks)} shapes, but MangaFire's verify step froze the browser (Cloudflare) — this is why MangaFire uses the solver, not the WebView"), 200
        print(f"browser: autosolve try {attempt}: clicked {len(clicks)} but didn't pass — refreshing", flush=True)
        try:
            _cdp("Page.reload", {})
        except Exception:
            return jsonify(solved=False, detected=detected, clicked=len(clicks), tries=attempt,
                           message="page froze after the clicks (Cloudflare) — MangaFire uses the solver"), 200
        _time.sleep(1.5)
    return jsonify(solved=False, detected=detected, clicked=0, tries=AUTOSOLVE_TRIES,
                   message="gave up after retries — try solving manually"), 200


def _devtools_addr():
    """host:port of Chromium's DevTools endpoint. chromedriver already runs Chromium with a debug port and
    reports it here, so we don't have to pin one ourselves."""
    caps = getattr(_driver, "capabilities", None) or {}
    return caps.get("goog:chromeOptions", {}).get("debuggerAddress") or f"127.0.0.1:{DEVTOOLS_PORT}"


def _page_ws_url():
    """The page target's raw CDP websocket URL (from DevTools /json), or None if not up yet."""
    addr = _devtools_addr()
    if not addr:
        return None
    raw = urllib.request.urlopen(f"http://{addr}/json", timeout=3).read()
    for t in json.loads(raw):
        if t.get("type") == "page" and t.get("webSocketDebuggerUrl"):
            return t["webSocketDebuggerUrl"]
    return None


def _screencast_loop():
    """Event-driven frame channel — the CDP analog of JCEF's onPaint, and the fix for the poll model that
    wedged the sidecar. We open our OWN websocket to Chromium's DevTools and Page.startScreencast; Chrome
    then PUSHES a JPEG each time the page actually repaints. A static or hung page simply sends nothing —
    no lock held, no thread pinned, the sidecar stays responsive. Runs only while a client is polling /frame
    (_frame_wanted_until) and re-arms after each navigation (_nav_gen)."""
    global _frame_cache, _fps
    ws = None
    started = False
    seen_gen = -1
    msg_id = 0
    fcount = 0
    window = time.time()
    last_warn = 0.0   # throttle connect/error logging so a down endpoint doesn't spam the log

    def _send(method, params=None):
        nonlocal msg_id
        msg_id += 1
        ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))

    def _arm():
        _send("Page.startScreencast",
              {"format": "jpeg", "quality": 55, "maxWidth": WIDTH, "maxHeight": HEIGHT, "everyNthFrame": 1})

    while True:
        # No browser, or nobody watching → tear the screencast down and idle cheaply.
        if _driver is None or time.time() >= _frame_wanted_until:
            if ws is not None:
                try: ws.close()
                except Exception: pass
                ws, started = None, False
            _fps = 0.0
            time.sleep(0.2)
            continue
        try:
            if ws is None:
                url = _page_ws_url()
                if not url:
                    if time.time() - last_warn > 5:
                        print(f"browser: screencast — no page target yet (addr={_devtools_addr()})", flush=True)
                        last_warn = time.time()
                    time.sleep(0.5); continue
                ws = websocket.create_connection(url, timeout=5)
                ws.settimeout(1.0)
                started = False
                print("browser: screencast connected", flush=True)
            if not started:
                _send("Page.enable")
                _arm()
                started, seen_gen = True, _nav_gen
            elif seen_gen != _nav_gen:
                _arm()                      # page changed → re-arm so frames resume
                seen_gen = _nav_gen
            try:
                raw = ws.recv()
            except websocket.WebSocketTimeoutException:
                continue  # no repaint since last frame = normal (static page); loop re-checks driver/wanted
            if not raw:
                continue
            m = json.loads(raw)
            if m.get("method") == "Page.screencastFrame":
                p = m["params"]
                _frame_cache = base64.b64decode(p["data"])
                _send("Page.screencastFrameAck", {"sessionId": p["sessionId"]})
                fcount += 1
                dt = time.time() - window
                if dt >= 5.0:
                    _fps = fcount / dt
                    print(f"browser: screencast {_fps:.1f} fps", flush=True)
                    fcount, window = 0, time.time()
        except Exception as e:
            # ws died (driver recreated/quit, endpoint down, or the target went away) → drop it and
            # reconnect next pass. Log throttled (not just under VERBOSE) so a persistent failure is visible.
            if time.time() - last_warn > 5:
                print(f"browser: screencast reconnect ({type(e).__name__}: {e})", flush=True)
                last_warn = time.time()
            try:
                if ws is not None: ws.close()
            except Exception: pass
            ws, started = None, False
            time.sleep(0.5)


if __name__ == "__main__":
    threading.Thread(target=_idle_reaper, daemon=True).start()
    threading.Thread(target=_screencast_loop, daemon=True).start()
    print(f"browser: sidecar starting on :9000 (waitress, verbose={VERBOSE})", flush=True)
    # waitress = a real WSGI server. The Flask dev server (app.run) buckles under the sustained frame-poll
    # load and drops connections ("unreachable"); waitress handles the concurrency properly.
    from waitress import serve
    serve(app, host="0.0.0.0", port=9000, threads=16)
