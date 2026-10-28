#!/usr/bin/env bash
# manga-utils worker status LED driver (Orange Pi 5 Plus).
#
# Reflects the node's state on the two controllable front LEDs (green + blue). The board also has an
# always-on RED element, so "both off" reads as solid red - we use that as the alert/base colour.
#   solid red            = no internet egress
#   red + blue fast      = worker app (container) not responding
#   red + blue slow      = online but can't reach main
#   green + blue fast    = downloading a delegated job
#   green + blue heartbeat = standby / ready (alive)
#
# Runs on the HOST (not in the container) via systemd, so it can still show red/error if the worker
# container itself dies. Install: see deploy/worker/mu-led.service.
set -u
G=/sys/class/leds/green_led
B=/sys/class/leds/blue_led
ENVF=/root/manga-src/deploy/worker/.env
WORKER_TOKEN=""
MAIN_URL="http://100.67.109.13:8080"
# shellcheck disable=SC1090
[ -f "$ENVF" ] && . "$ENVF"
W=http://localhost:8790

g() { echo none > "$G/trigger"; echo "$1" > "$G/brightness"; }
b() { echo none > "$B/trigger"; echo "$1" > "$B/brightness"; }
bbl() { echo timer > "$B/trigger"; echo "$1" > "$B/delay_on"; echo "$2" > "$B/delay_off"; }
bheart() { echo heartbeat > "$B/trigger"; }

STATE=""
apply() {
  [ "$1" = "$STATE" ] && return
  STATE="$1"
  case "$1" in
    nonet)   g 0; b 0 ;;          # solid RED - no internet egress
    error)   g 0; bbl 120 120 ;;  # red + blue fast - worker app down
    nomain)  g 0; bbl 600 600 ;;  # red + blue slow - can't reach main
    dl)      g 1; bbl 120 120 ;;  # green + blue fast - downloading
    standby) g 1; bheart ;;       # green + blue heartbeat - ready, alive
  esac
  logger -t mu-led "state=$1"
}

# Boot sweep so you can see the driver started.
g 1; b 1; sleep 0.4; g 0; b 0; sleep 0.2

while true; do
  if ! curl -sf --max-time 3 -o /dev/null https://1.1.1.1; then
    apply nonet
  elif ! curl -sf --max-time 2 -o /dev/null "$W/api/version"; then
    apply error
  elif ! curl -sf --max-time 3 -o /dev/null "$MAIN_URL/api/version"; then
    apply nomain
  else
    IN=$(curl -s --max-time 3 -H "Authorization: Bearer $WORKER_TOKEN" "$W/worker/health" 2>/dev/null | grep -o '"inflight":[0-9]*' | grep -o '[0-9]*')
    if [ "${IN:-0}" -gt 0 ] 2>/dev/null; then
      apply dl
    else
      apply standby
    fi
  fi
  sleep 2
done
