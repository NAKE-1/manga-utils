#!/bin/sh
# nodriver drives a HEADED Chromium (so Cloudflare sees a real window), which needs an X server — the
# container has none, so run a virtual display (Xvfb) and point Chromium at it, same as the main image.
set -e
export DISPLAY=:99
rm -f /tmp/.X99-lock 2>/dev/null || true
Xvfb :99 -screen 0 1920x1080x24 +extension GLX +extension RANDR +render -nolisten tcp >/tmp/xvfb.log 2>&1 &
i=0
while [ ! -e /tmp/.X11-unix/X99 ] && [ "$i" -lt 25 ]; do i=$((i + 1)); sleep 0.2; done
exec uvicorn app:app --host 0.0.0.0 --port 8000
