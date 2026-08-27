"""
Cloudflare-cleared request replayer for manga-utils (Plan #3 — curl_cffi engine).

Browsers on this Linux box can't pass MangaFire's Cloudflare managed challenge (nodriver + SeleniumBase-UC
both stuck forever on "Just a moment..."), but FlareSolverr can. So split the two jobs:

  1. FlareSolverr (in the JVM interceptor) earns cf_clearance + the User-Agent it's bound to.
  2. This sidecar replays the ACTUAL request with curl_cffi impersonating Chrome's real TLS/JA3 + HTTP2
     fingerprint (which okhttp/JSSE cannot), carrying the cf_clearance cookie + the headers a genuine in-page
     XHR sends (X-Requested-With, Sec-Fetch-Mode: cors, Referer). Real Chrome fingerprint + valid clearance
     + same egress IP => Cloudflare accepts it; the XHR headers => MangaFire's /api returns real JSON.

No browser, no Xvfb — just a TLS-impersonating HTTP client. Set SOLVER_IMPERSONATE to tune the Chrome
target (e.g. chrome, chrome131, chrome124) if the JA3 needs to match FlareSolverr's more closely.
"""
import os
from urllib.parse import urlparse

from curl_cffi import requests as creq
from flask import Flask, jsonify, request

app = Flask(__name__)
IMPERSONATE = os.environ.get("SOLVER_IMPERSONATE", "chrome")


@app.get("/health")
def health():
    return jsonify(ok=True, impersonate=IMPERSONATE)


@app.post("/fetch")
def fetch():
    data = request.get_json(force=True, silent=True) or {}
    url = data.get("url")
    if not url:
        return jsonify(status=0, error="no url"), 400
    headers = dict(data.get("headers") or {})
    cf = data.get("cf_clearance")
    ua = data.get("user_agent")
    p = urlparse(url)
    origin = f"{p.scheme}://{p.netloc}"

    # Present as the site's own in-page XHR (what MangaFire's /api serves data to). The UA MUST match the
    # one cf_clearance was issued for, so use FlareSolverr's; the rest mimic a same-origin fetch().
    if ua:
        headers["User-Agent"] = ua
    headers.setdefault("Referer", origin + "/")
    headers.setdefault("X-Requested-With", "XMLHttpRequest")
    headers.setdefault("Accept", "application/json, text/plain, */*")
    headers.setdefault("Sec-Fetch-Mode", "cors")
    headers.setdefault("Sec-Fetch-Site", "same-origin")
    headers.setdefault("Sec-Fetch-Dest", "empty")
    cookies = {"cf_clearance": cf} if cf else {}

    try:
        r = creq.get(url, headers=headers, cookies=cookies, impersonate=IMPERSONATE, timeout=30)
        body = r.text or ""
        tail = "" if r.status_code == 200 else f" snippet={body[:200]!r}"
        print(f"solver: {url} -> {r.status_code} {len(body)}B{tail}", flush=True)
        return jsonify(status=r.status_code, body=body)
    except Exception as e:  # noqa: BLE001 — report anything so the caller can fall back
        print(f"solver: error: {e}", flush=True)
        return jsonify(status=0, error=str(e)), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, threaded=True)
