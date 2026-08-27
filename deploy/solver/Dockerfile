# Cloudflare-cleared request replayer (Plan #3 — curl_cffi engine). No browser: curl_cffi impersonates
# Chrome's TLS/JA3 + HTTP2 fingerprint, so with FlareSolverr's cf_clearance it replays MangaFire's XHR API
# and gets real JSON where okhttp (wrong fingerprint) and a bare navigation (wrong request mode) both fail.
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py entrypoint.sh ./
RUN sed -i 's/\r$//' entrypoint.sh && chmod +x entrypoint.sh   # tolerate a CRLF checkout on Windows

EXPOSE 8000
ENTRYPOINT ["tini", "--"]
CMD ["./entrypoint.sh"]
