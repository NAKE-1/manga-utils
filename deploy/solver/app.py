"""
Cloudflare + MangaFire-WAF request replayer for manga-utils (Plan #3 — curl_cffi engine).

Two walls, both handled here without a browser:
  1. Cloudflare — curl_cffi impersonates Chrome's TLS/JA3 + HTTP2 fingerprint (which okhttp/JSSE can't), so
     with FlareSolverr's cf_clearance (passed in by the JVM) Cloudflare waves the request through.
  2. MangaFire /@waf shapes captcha — when the API answers {"error":"captcha_required"}, we solve it IN the
     same curl_cffi session: GET /@waf/generate -> YOLO (captcha.py, the same model as the JVM) picks the
     ordered click points -> POST /@waf/verify {captcha_id, dots} -> the WAF cookie lands in the session ->
     retry the original request -> real JSON.

A persistent Session per host keeps cf_clearance + the WAF cookie warm, so only the first request per host
(per clearance window) pays the solve. Serialized with a lock (one session per host, mutated per call).
"""
import os
import threading
import time
from urllib.parse import urlparse

from curl_cffi import requests as creq
from flask import Flask, jsonify, request

import captcha

app = Flask(__name__)
IMPERSONATE = os.environ.get("SOLVER_IMPERSONATE", "chrome")
WAF_TRIES = int(os.environ.get("SOLVER_WAF_TRIES", "6"))
FS_URL = os.environ.get("SOLVER_FLARESOLVERR_URL", "http://flaresolverr:8191").rstrip("/")
CF_TTL = 600  # seconds to reuse a FlareSolverr clearance before re-solving

_lock = threading.Lock()
_sessions = {}  # host -> curl_cffi Session (persists cf_clearance + WAF cookie)
_clearance = {}  # host -> (cf_clearance, user_agent, ts)


def _get_clearance(host, origin):
    """Ask FlareSolverr to clear Cloudflare (browsers can't on this box) → (cf_clearance, UA). Cached."""
    c = _clearance.get(host)
    if c and time.time() - c[2] < CF_TTL:
        return c[0], c[1]
    try:
        r = creq.post(
            FS_URL + "/v1",
            json={"cmd": "request.get", "url": origin + "/", "maxTimeout": 60000,
                  "session": "mangautils", "returnOnlyCookies": True},
            timeout=90,
        ).json()
        sol = r.get("solution") or {}
        cf = next((ck["value"] for ck in sol.get("cookies", []) if ck.get("name") == "cf_clearance"), None)
        ua = sol.get("userAgent")
        if cf:
            _clearance[host] = (cf, ua, time.time())
            print(f"solver: got cf_clearance for {host} via FlareSolverr", flush=True)
        else:
            print(f"solver: FlareSolverr returned no cf_clearance for {host}", flush=True)
        return cf, ua
    except Exception as e:  # noqa: BLE001
        print(f"solver: FlareSolverr clearance error: {e}", flush=True)
        return None, None


def _session(host):
    s = _sessions.get(host)
    if s is None:
        s = creq.Session(impersonate=IMPERSONATE)
        _sessions[host] = s
    return s


def _xhr_headers(origin, ua, referer, extra=None):
    h = {
        "Accept": "application/json, text/plain, */*",
        "X-Requested-With": "XMLHttpRequest",
        "Referer": referer,
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Dest": "empty",
    }
    if ua:
        h["User-Agent"] = ua
    if extra:
        h.update(extra)
    return h


def _is_captcha(status, body):
    return status == 403 and "captcha_required" in body and "@waf" in body


def _solve_waf(sess, origin, ua):
    """GET generate -> YOLO -> POST verify, until success or WAF_TRIES exhausted. Cookies persist in sess."""
    ref = origin + "/@waf/challenge"
    for attempt in range(1, WAF_TRIES + 1):
        try:
            gen = sess.get(origin + "/@waf/generate", headers=_xhr_headers(origin, ua, ref), timeout=30).json()
        except Exception as e:  # noqa: BLE001
            print(f"solver: /@waf/generate failed: {e}", flush=True)
            return False
        cid = gen.get("captcha_id", "")
        a_uri, b_uri = gen.get("thumb_base64", ""), gen.get("image_base64", "")
        if not cid or not a_uri or not b_uri:
            print(f"solver: bad generate (try {attempt})", flush=True)
            continue
        clicks, missing = captcha.solve(captcha.decode_data_uri(a_uri), captcha.decode_data_uri(b_uri))
        if missing or not clicks:
            print(f"solver: incomplete solve (try {attempt}) missing={missing} clicks={len(clicks)} — refresh", flush=True)
            continue
        dots = ",".join(f"{int(round(cx))},{int(round(cy))}" for cx, cy in clicks)
        try:
            ver = sess.post(
                origin + "/@waf/verify",
                json={"captcha_id": cid, "dots": dots},
                headers=_xhr_headers(origin, ua, ref, {"Content-Type": "application/json"}),
                timeout=30,
            ).json()
        except Exception as e:  # noqa: BLE001
            print(f"solver: /@waf/verify failed: {e}", flush=True)
            continue
        if ver.get("success"):
            print(f"solver: /@waf solved in {len(clicks)} clicks (try {attempt})", flush=True)
            return True
        print(f"solver: verify rejected (try {attempt}) — refresh", flush=True)
    print(f"solver: /@waf gave up after {WAF_TRIES} tries", flush=True)
    return False


@app.get("/health")
def health():
    return jsonify(ok=True, impersonate=IMPERSONATE, sessions=list(_sessions.keys()))


@app.post("/reset")
def reset():
    """Egress reset (VPN/exit-node switch): drop every session + cached clearance so the next request
    re-solves fresh on the new IP (cf_clearance is IP-bound)."""
    with _lock:
        n = len(_sessions)
        _sessions.clear()
        _clearance.clear()
    print(f"solver: reset — cleared {n} session(s) + clearances", flush=True)
    return jsonify(ok=True, cleared=n)


@app.post("/fetch")
def fetch():
    data = request.get_json(force=True, silent=True) or {}
    url = data.get("url")
    if not url:
        return jsonify(status=0, error="no url"), 400
    extra_headers = dict(data.get("headers") or {})
    p = urlparse(url)
    origin = f"{p.scheme}://{p.netloc}"
    host = p.netloc

    with _lock:  # one session per host, mutated per call
        try:
            cf, ua = _get_clearance(host, origin)
            if not cf:
                return jsonify(status=0, error="no cf_clearance (FlareSolverr)"), 502
            sess = _session(host)
            sess.cookies.set("cf_clearance", cf, domain="." + host)  # keep the clearance fresh
            headers = _xhr_headers(origin, ua, origin + "/", extra_headers)

            r = sess.get(url, headers=headers, timeout=30)
            body = r.text or ""
            waf_solved = False
            if _is_captcha(r.status_code, body):
                print(f"solver: {url} -> captcha_required, solving /@waf…", flush=True)
                if _solve_waf(sess, origin, ua):
                    waf_solved = True
                    r = sess.get(url, headers=headers, timeout=30)  # retry with the WAF cookie now set
                    body = r.text or ""

            tail = "" if r.status_code == 200 else f" snippet={body[:160]!r}"
            print(f"solver: {url} -> {r.status_code} {len(body)}B{tail}", flush=True)
            return jsonify(status=r.status_code, body=body, waf_solved=waf_solved, host=host)
        except Exception as e:  # noqa: BLE001 — report anything so the caller can fall back
            _sessions.pop(host, None)  # drop a possibly-wedged session
            print(f"solver: error: {e}", flush=True)
            return jsonify(status=0, error=str(e)), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, threaded=False)  # lock serializes anyway
