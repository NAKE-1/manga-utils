# Anti-detect solver sidecar (Plan #3): SeleniumBase UC-mode + real Google Chrome, headed on Xvfb, with
# an in-page /fetch that pulls XHR-only API data (MangaFire) the way JCEF's same-origin fetch did.
FROM python:3.12-slim

# Real Google Chrome (UC mode is tuned for Chrome, not chromium) + Xvfb + PyAutoGUI's click deps
# (scrot for screenshots, python3-tk/xauth for the X interaction) + tini for clean signals.
RUN apt-get update && apt-get install -y --no-install-recommends \
        wget gnupg ca-certificates fonts-liberation tini \
        xvfb xauth scrot python3-tk python3-dev \
        libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 \
        libpangocairo-1.0-0 libgtk-3-0 libxshmfence1 \
        libgl1-mesa-dri libglx-mesa0 libgl1 libegl1 libgles2 libvulkan1 mesa-vulkan-drivers \
    && wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb \
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
