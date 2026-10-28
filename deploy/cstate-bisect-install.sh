#!/usr/bin/env bash
# Install the C-state crash bisect as a reboot-surviving service and expose all C-states for testing.
# Run on the pve host:  bash deploy/cstate-bisect-install.sh   then reboot. (debug tool)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
install -m 755 "$HERE/cstate-bisect.sh" /usr/local/bin/cstate-bisect.sh
rm -rf /var/lib/cstate-bisect                      # fresh start (clears any stale/garbled progress)
cat >/etc/systemd/system/cstate-bisect.service <<'UNIT'
[Unit]
Description=C-state crash bisect
After=pve-guests.service
[Service]
Type=oneshot
ExecStart=/usr/local/bin/cstate-bisect.sh
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable cstate-bisect.service
# expose all C-states (remove the diagnostic C1 cap) so the bisect can test C6/C8/C10
sed -i -E 's/ *(intel_idle|processor)\.max_cstate=[0-9]+//g' /etc/default/grub
update-grub
echo
echo ">> Installed + armed. C-states exposed. Syntax check:"; bash -n /usr/local/bin/cstate-bisect.sh && echo "   script OK"
echo ">> cmdline after reboot will be: $(grep GRUB_CMDLINE_LINUX_DEFAULT /etc/default/grub)"
echo ">> Now run: reboot    (bisect runs automatically; watch /var/log/cstate-bisect.log)"
