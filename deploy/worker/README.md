# manga-utils worker node (Phase C)

An **IP-pool image fetcher**. The main server keeps all orchestration and extension parsing; a worker
only runs the one ban-prone step — the CDN image GET — from *its own* public IP, via curl_cffi (real
Chrome TLS fingerprint, same engine as the solver sidecar). It returns the image bytes plus a SHA-256
so the server can verify integrity. It is stateless: no library, no extensions, no JVM.

## Run

```bash
pip install -r requirements.txt           # flask + curl_cffi
WORKER_PORT=8790 python3 agent.py
```

Env knobs: `WORKER_PORT` (8790), `WORKER_IMPERSONATE` (chrome), `WORKER_FETCH_TIMEOUT` (20s),
`WORKER_TOKEN` (shared secret; when set, every `/fetch` must send `X-Worker-Token`).

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/id` | `{hostname, egress_ip, version}` — which IP this node exits from |
| GET | `/health` | CPU / RAM / temp / disk / net / load, read from `/proc`,`/sys`,`statvfs` (Linux; null off-Linux) |
| POST | `/fetch` | body `{url, headers?, impersonate?}` → raw image bytes + `X-Sha256`, `X-Content-Type`, `X-Elapsed-Ms`, `X-Bytes`; 502 + JSON if the source errors or the bytes aren't an image |

## Test

```bash
# identity + egress IP (confirms the pool sees a distinct IP)
curl -s localhost:8790/id

# health (full numbers on the Pi; nulls on a non-Linux dev box)
curl -s localhost:8790/health

# fetch a real manga page image from THIS node and keep it
curl -s -D- -o page0.jpg -X POST localhost:8790/fetch \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://<cdn-host>/<path>.jpg","headers":{"Referer":"https://<source>/"}}'
# -> 200 with X-Sha256 header, page0.jpg on disk; or 502 {"error":...} on a bad/blocked URL
```

Get a live image URL + its headers from the main server's dev identity / page endpoints, or copy one
out of a `DOWNLOAD` log line's chapter.

## Not wired into the server yet

Next step is the main-server side: a `WorkerPool` (registry + health gate + per-(source,IP) dispatch
cap) that `DownloadManager.downloadPage` routes the image GET through when a pool is configured. The
worker protocol above is frozen for that: send `{url, headers}`, verify the returned `X-Sha256` against
a re-hash of the body (one of the integrity layers).

## Keep in sync

`_looks_like_image` mirrors `core/.../convert/ImageFormat.kt` `looksLikeImage`. If the magic-byte rules
change there, change them here too.
