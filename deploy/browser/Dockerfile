# manga-utils browser sidecar (Option D / P2): real headed Chromium in Xvfb, driven over the Chrome
# DevTools Protocol. Streams an interactive WebView to the server WITHOUT in-process JCEF, so a browser
# crash is isolated to this container (restart:unless-stopped revives it) instead of killing the server.
# Same Xvfb+Chrome stack as FlareSolverr, which already runs fine on this box.
FROM python:3.11-slim

# Chromium + its driver + Xvfb (headed, virtual display) + fonts so pages render like a real browser.
RUN apt-get update && apt-get install -y --no-install-recommends \
        chromium chromium-driver xvfb \
        ca-certificates fonts-liberation fonts-noto-cjk fonts-noto-color-emoji \
        curl unzip \
    && rm -rf /var/lib/apt/lists/*

# uBlock Origin Lite (MV3) — the only ad/popup blocker that ships a ready-to-load unpacked build, and MV2 is
# dead on Chromium 153 so full uBO isn't an option. Loaded via --load-extension in app.py. Fetched at build
# time from the official uBOL release; if the download fails the build still succeeds (adblock just absent).
# NOTE: uBOL releases are marked pre-release, so /releases/latest 404s — query the releases LIST (newest
# first, includes pre-releases) and take the first chromium.mv3 asset.
RUN mkdir -p /app/ublock && \
    URL="$(curl -fsSL 'https://api.github.com/repos/uBlockOrigin/uBOL-home/releases?per_page=10' \
        | grep -oE 'https://[^\"]*chromium\.mv3\.zip' | head -n1)" && \
    if [ -n "$URL" ]; then \
        echo "browser: fetching uBO Lite from $URL" && \
        curl -fsSL "$URL" -o /tmp/ubol.zip && unzip -q /tmp/ubol.zip -d /app/ublock && rm /tmp/ubol.zip && \
        echo "browser: uBO Lite unpacked:" && find /app/ublock -name manifest.json ; \
    else echo "browser: WARN could not resolve uBO Lite release — adblock will be absent" ; fi

ENV CHROME_BIN=/usr/bin/chromium \
    CHROMEDRIVER_BIN=/usr/bin/chromedriver \
    DISPLAY=:99 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py entrypoint.sh captcha.py classes.json best.onnx ./
RUN chmod +x entrypoint.sh

EXPOSE 9000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9000/health',timeout=3).status==200 else 1)" || exit 1
CMD ["./entrypoint.sh"]
