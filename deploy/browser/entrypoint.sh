#!/bin/sh
# Start a virtual X display, then the Flask app. Chrome runs HEADED against Xvfb (not --headless) —
# headed passes Cloudflare on this box where headless gets stuck (same reason FlareSolverr runs headed).
set -e
rm -f /tmp/.X99-lock 2>/dev/null || true
Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp &
# give Xvfb a moment to come up before Chrome tries to connect
sleep 1
exec python app.py
