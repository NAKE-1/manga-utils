"""
manga-utils worker node — IP-pool image fetcher (Phase C).

A worker is NOT a compute node. The main server does all the orchestration and extension parsing
(getPageList / getImageUrl need the Tachiyomi stack). The only thing dispatched here is the heavy,
ban-prone part: the CDN image GET. The worker fetches that one URL from ITS OWN public IP — via
curl_cffi, so the request carries a real Chrome TLS/JA3 + HTTP2 fingerprint (the same engine the
solver sidecar uses) — validates the bytes are actually an image, and returns them with a SHA-256
so the server can verify integrity end to end.

Endpoints:
  GET  /id      -> {hostname, egress_ip, version}            identity (which IP this node speaks from)
  GET  /health  -> {cpu, mem, temp, disk, net, load, up}     piggyback monitoring, read from /proc,/sys
  POST /fetch   -> raw image bytes + X-Sha256 header          the actual work

Run (standalone, on the Pi or any box):
  pip install flask curl_cffi
  WORKER_PORT=8790 python3 agent.py

Testable today on any machine: /fetch and /id work anywhere; the Linux-only /health fields come back
null off-Linux (each probe is guarded) so the endpoint still responds.
"""
import hashlib
import os
import socket
import time
from urllib.parse import urlparse

from curl_cffi import requests as creq
from flask import Flask, jsonify, request, Response

app = Flask(__name__)
VERSION = "0.1.0"
IMPERSONATE = os.environ.get("WORKER_IMPERSONATE", "chrome")
PORT = int(os.environ.get("WORKER_PORT", "8790"))
FETCH_TIMEOUT = int(os.environ.get("WORKER_FETCH_TIMEOUT", "20"))
# Shared secret: if set, every /fetch must carry X-Worker-Token matching it. Keeps a stray host on the
# tailnet from using this node as an open proxy. Empty = open (fine for a locked-down tailnet test).
TOKEN = os.environ.get("WORKER_TOKEN", "")

_started = time.time()
_egress_ip = None  # looked up once, lazily (needs network); the whole point is which IP we exit from


# ---- image sniff: mirrors core/.../convert/ImageFormat.kt looksLikeImage (keep the two in sync) ------
def _looks_like_image(b: bytes) -> bool:
    if len(b) < 12:
        return False
    x = b[0:12]
    if x[0] == 0xFF and x[1] == 0xD8:                                   # jpeg
        return True
    if x[0] == 0x89 and x[1] == 0x50 and x[2] == 0x4E and x[3] == 0x47:  # png
        return True
    if x[0] == 0x47 and x[1] == 0x49 and x[2] == 0x46:                  # gif
        return True
    if (x[0] == 0x52 and x[1] == 0x49 and x[2] == 0x46 and x[3] == 0x46
            and x[8] == 0x57 and x[9] == 0x45 and x[10] == 0x42 and x[11] == 0x50):  # RIFF..WEBP
        return True
    if x[4] == 0x66 and x[5] == 0x74 and x[6] == 0x79 and x[7] == 0x70:  # ftyp.. avif/heic
        return True
    # First non-whitespace byte '<' / '{' / '[' => an HTML/JSON error page, never an image.
    for c in b:
        if c in (0x20, 0x09, 0x0A, 0x0D, 0xEF, 0xBB, 0xBF):
            continue
        if c in (0x3C, 0x7B, 0x5B):
            return False
        break
    return len(b) >= 1024  # unknown but substantial — accept, as the JVM side does


# ---- health probes: all guarded so a non-Linux box (dev machine) just reports null for that field ----
def _read(path):
    try:
        with open(path, "r") as f:
            return f.read()
    except Exception:
        return None


def _cpu_pct(sample=0.2):
    """Busy% across all cores over a short window, from two /proc/stat reads."""
    def snap():
        line = (_read("/proc/stat") or "").splitlines()
        if not line or not line[0].startswith("cpu "):
            return None
        v = [int(n) for n in line[0].split()[1:]]
        idle = v[3] + (v[4] if len(v) > 4 else 0)
        return sum(v), idle
    a = snap()
    if not a:
        return None
    time.sleep(sample)
    b = snap()
    if not b:
        return None
    dt, di = b[0] - a[0], b[1] - a[1]
    return round(100.0 * (dt - di) / dt, 1) if dt > 0 else None


def _mem():
    txt = _read("/proc/meminfo")
    if not txt:
        return None
    kv = {}
    for ln in txt.splitlines():
        p = ln.split()
        if len(p) >= 2:
            kv[p[0].rstrip(":")] = int(p[1])  # kB
    total, avail = kv.get("MemTotal"), kv.get("MemAvailable")
    if total is None or avail is None:
        return None
    return {"total_mb": total // 1024, "used_mb": (total - avail) // 1024,
            "pct": round(100.0 * (total - avail) / total, 1)}


def _temp():
    """Hottest thermal zone in °C (RK3588 exposes several)."""
    best = None
    for i in range(12):
        v = _read(f"/sys/class/thermal/thermal_zone{i}/temp")
        if v is None:
            continue
        try:
            c = int(v.strip()) / 1000.0
        except ValueError:
            continue
        best = c if best is None else max(best, c)
    return round(best, 1) if best is not None else None


def _disk(path="/"):
    try:
        s = os.statvfs(path)
        total = s.f_blocks * s.f_frsize
        free = s.f_bavail * s.f_frsize
        return {"total_gb": round(total / 1e9, 1), "free_gb": round(free / 1e9, 1),
                "pct": round(100.0 * (total - free) / total, 1) if total else None}
    except Exception:
        return None


def _net():
    """Cumulative rx/tx bytes across real interfaces (skip lo). Server deltas these between polls."""
    txt = _read("/proc/net/dev")
    if not txt:
        return None
    rx = tx = 0
    for ln in txt.splitlines():
        if ":" not in ln:
            continue
        name, rest = ln.split(":", 1)
        if name.strip() in ("lo",):
            continue
        f = rest.split()
        if len(f) >= 9:
            rx += int(f[0]); tx += int(f[8])
    return {"rx_bytes": rx, "tx_bytes": tx}


def _load():
    txt = _read("/proc/loadavg")
    if not txt:
        return None
    p = txt.split()
    return [float(p[0]), float(p[1]), float(p[2])] if len(p) >= 3 else None


def _egress():
    global _egress_ip
    if _egress_ip is not None:
        return _egress_ip
    for url in ("https://api.ipify.org", "https://ifconfig.me/ip"):
        try:
            r = creq.get(url, impersonate=IMPERSONATE, timeout=8)
            if r.status_code == 200 and r.text.strip():
                _egress_ip = r.text.strip()
                return _egress_ip
        except Exception:
            continue
    _egress_ip = ""  # cache the miss too — don't re-probe on every /id
    return _egress_ip


@app.get("/id")
def ident():
    return jsonify(hostname=socket.gethostname(), egress_ip=_egress(), version=VERSION)


@app.get("/health")
def health():
    return jsonify(
        version=VERSION,
        uptime_s=int(time.time() - _started),
        cpu_pct=_cpu_pct(),
        mem=_mem(),
        temp_c=_temp(),
        disk=_disk(),
        net=_net(),
        load=_load(),
    )


@app.post("/fetch")
def fetch():
    """Fetch ONE image URL from this node's IP. Body: {url, headers?, impersonate?}.
    Returns the raw image body with X-Sha256 / X-Content-Type / X-Elapsed-Ms, or 4xx/502 + JSON on error."""
    if TOKEN and request.headers.get("X-Worker-Token", "") != TOKEN:
        return jsonify(error="unauthorized"), 401
    body = request.get_json(silent=True) or {}
    url = body.get("url")
    if not url:
        return jsonify(error="missing url"), 400
    headers = body.get("headers") or {}
    imp = body.get("impersonate") or IMPERSONATE
    t0 = time.time()
    try:
        r = creq.get(url, headers=headers, impersonate=imp, timeout=FETCH_TIMEOUT, allow_redirects=True)
    except Exception as e:  # noqa: BLE001
        return jsonify(error=f"fetch failed: {e}", url=url), 502
    data = r.content or b""
    elapsed = int((time.time() - t0) * 1000)
    if r.status_code != 200:
        return jsonify(error=f"source returned HTTP {r.status_code}", status=r.status_code,
                       bytes=len(data), url=url), 502
    if not _looks_like_image(data):
        # An error page (CF 403, JSON blob) dressed as a 200 — reject so the server retries elsewhere
        # instead of writing a 3 KB HTML "image" to disk.
        return jsonify(error="not an image", bytes=len(data), url=url), 502
    sha = hashlib.sha256(data).hexdigest()
    return Response(data, status=200, headers={
        "X-Sha256": sha,
        "X-Content-Type": r.headers.get("content-type", ""),
        "X-Elapsed-Ms": str(elapsed),
        "X-Bytes": str(len(data)),
    }, mimetype="application/octet-stream")


if __name__ == "__main__":
    print(f"manga-utils worker {VERSION} on :{PORT} (impersonate={IMPERSONATE}, "
          f"auth={'on' if TOKEN else 'off'})", flush=True)
    app.run(host="0.0.0.0", port=PORT, threaded=True)
