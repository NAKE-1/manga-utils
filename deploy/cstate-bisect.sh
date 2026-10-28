#!/usr/bin/env bash
# Find the deepest CPU C-state that does NOT crash this box. Reboot-surviving auto-bisect (debug tool).
# Each round caps the deepest allowed C-state via cpuidle sysfs, then IDLES $SOAK sec (idle triggers the
# crash, not load). Survive -> that state is safe, done. Crash -> box reboots -> next boot marks it the
# culprit and tries the next-shallower state. Result + the max_cstate to use land in /var/log/cstate-bisect.log
# Installed + armed by cstate-bisect-install.sh. Read progress: cat /var/log/cstate-bisect.log
set -uo pipefail
LOG=/var/log/cstate-bisect.log; DIR=/var/lib/cstate-bisect; mkdir -p "$DIR"
ROUNDF=$DIR/round; PENDF=$DIR/pending; SOAK=${SOAK:-900}
log(){ echo "$(date '+%F %T')  $*" >>"$LOG"; }
C=/sys/devices/system/cpu; mapfile -t ST < <(ls -d "$C"/cpu0/cpuidle/state* | sort -V); LAST=$((${#ST[@]}-1))
nm(){ cat "${ST[$1]}/name"; }
cap(){ local K=$1 i d; for i in $(seq 0 "$LAST"); do for d in "$C"/cpu*/cpuidle/state"$i"/disable; do
        { [ "$i" -le "$K" ] && echo 0 || echo 1; } >"$d" 2>/dev/null; done; done; }
if [ -f "$PENDF" ]; then r=$(cat "$ROUNDF"); log "ROUND $r CRASHED  <-- culprit: '$(cat "$PENDF")'"; rm -f "$PENDF"; echo $((r-1)) >"$ROUNDF"; fi
round=$(cat "$ROUNDF" 2>/dev/null || echo "$LAST")
log "=== boot: states[$(for i in $(seq 0 "$LAST"); do printf '%s=%s ' "$i" "$(nm "$i")"; done)] SOAK=${SOAK}s round=$round ==="
for id in $(qm list 2>/dev/null | awk 'NR>1{print $1}'); do qm stop "$id" 2>/dev/null; done   # idle the host
if [ "$round" -lt 1 ]; then
  log "RESULT: only C1 survived -> no deep C-state safe. Use intel_idle.max_cstate=1 + cooling (or RMA)."
  systemctl disable cstate-bisect.service; exit 0
fi
echo "$round" >"$ROUNDF"; echo "$(nm "$round")" >"$PENDF"; cap "$round"
u0=$(cat "${ST[$round]}/usage")
log "ROUND $round: deepest allowed = state$round '$(nm "$round")'; idling ${SOAK}s (expect a reboot if this state is the culprit)..."
sleep 60
log "   entry check: state$round used +$(( $(cat "${ST[$round]}/usage") - u0 )) times in 60s (>0 = really entering it)"
sleep $((SOAK-60)); rm -f "$PENDF"
log "ROUND $round SURVIVED: '$(nm "$round")' is STABLE = deepest safe C-state. PERMANENT FIX: intel_idle.max_cstate=$round"
systemctl disable cstate-bisect.service
