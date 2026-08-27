# Anti-detect solver sidecar (Plan #3): nodriver-driven headed Chromium on Xvfb, with an in-page /fetch
# that pulls XHR-only API data (MangaFire) the way JCEF's same-origin fetch did on Windows.
FROM python:3.12-slim

# chromium + the X/GL libs a headed browser needs, + tini for clean signal handling.
RUN apt-get update && apt-get install -y --no-install-recommends \
        chromium xvfb tini ca-certificates fonts-liberation \
        libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 \
        libpangocairo-1.0-0 libgtk-3-0 libxshmfence1 \
    && rm -rf /var/lib/apt/lists/*

ENV CHROME_BIN=/usr/bin/chromium \
    DISPLAY=:99 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py entrypoint.sh ./
RUN sed -i 's/\r$//' entrypoint.sh && chmod +x entrypoint.sh   # tolerate a CRLF checkout on Windows

EXPOSE 8000
ENTRYPOINT ["tini", "--"]
CMD ["./entrypoint.sh"]
