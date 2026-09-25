# manga-utils browser sidecar (Option D / P2): real headed Chromium in Xvfb, driven over the Chrome
# DevTools Protocol. Streams an interactive WebView to the server WITHOUT in-process JCEF, so a browser
# crash is isolated to this container (restart:unless-stopped revives it) instead of killing the server.
# Same Xvfb+Chrome stack as FlareSolverr, which already runs fine on this box.
FROM python:3.11-slim

# Chromium + its driver + Xvfb (headed, virtual display) + fonts so pages render like a real browser.
RUN apt-get update && apt-get install -y --no-install-recommends \
        chromium chromium-driver xvfb \
        ca-certificates fonts-liberation fonts-noto-cjk fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*

ENV CHROME_BIN=/usr/bin/chromium \
    CHROMEDRIVER_BIN=/usr/bin/chromedriver \
    DISPLAY=:99 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py entrypoint.sh ./
RUN chmod +x entrypoint.sh

EXPOSE 9000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9000/health',timeout=3).status==200 else 1)" || exit 1
CMD ["./entrypoint.sh"]
