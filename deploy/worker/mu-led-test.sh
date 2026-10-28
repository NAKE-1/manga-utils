#!/usr/bin/env bash
# Step through every worker status-LED state so you can eyeball the real front LED and confirm each looks
# right. Pauses the live mu-led driver while testing and restarts it on exit. Enter advances, q quits.
set -u
G=/sys/class/leds/green_led
B=/sys/class/leds/blue_led
g() { echo none > "$G/trigger"; echo "$1" > "$G/brightness"; }
b() { echo none > "$B/trigger"; echo "$1" > "$B/brightness"; }
gbl() { echo timer > "$G/trigger"; echo "$1" > "$G/delay_on"; echo "$2" > "$G/delay_off"; }
bbl() { echo timer > "$B/trigger"; echo "$1" > "$B/delay_on"; echo "$2" > "$B/delay_off"; }
bheart() { echo heartbeat > "$B/trigger"; }
restore() { echo heartbeat > "$G/trigger"; echo heartbeat > "$B/trigger"; systemctl start mu-led.service 2>/dev/null; }
trap restore EXIT

systemctl stop mu-led.service 2>/dev/null
step() { echo; echo ">>> $1"; echo "    expect: $2"; read -r -p "    [Enter]=next, q=quit: " a; [ "$a" = q ] && exit 0; }

echo "=== LED state test - watch the front LED (live driver paused) ==="
g 1; bheart;              step "STANDBY / READY"    "green solid + blue heartbeat pulse"
g 1; bbl 120 120;         step "DOWNLOADING"        "green solid + blue fast blink"
g 1; bbl 600 600;         step "SHIPPING TO MAIN"   "green solid + blue slow blink"
gbl 600 600; b 0;         step "SYNCING EXTENSIONS" "green slow blink, blue off"
gbl 100 100; bbl 100 100; step "OVERHEAT"           "both strobe fast (alarm)"
g 0; b 0;                 step "NO NETWORK"         "solid RED"
g 0; bbl 600 600;         step "CANT REACH MAIN"    "red + blue slow blink"
g 0; bbl 120 120;         step "ERROR / APP DOWN"   "red + blue fast blink"
gbl 400 400; bbl 400 400; step "BOOTING"            "green + blue blink together"
echo; echo "done - restoring + restarting the live LED driver."
