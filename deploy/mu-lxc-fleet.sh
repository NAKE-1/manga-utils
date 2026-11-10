#!/usr/bin/env bash
# Drive manga-utils worker LXCs from the Proxmox HOST via `pct exec` - no SSH, no guest agent,
# no per-container login. Run it from the Proxmox root shell.
#
#   ./mu-lxc-fleet.sh update            # git pull + rebuild + up on EVERY worker CT
#   ./mu-lxc-fleet.sh update 302        # just CT 302
#   ./mu-lxc-fleet.sh restart           # docker compose restart on all
#   ./mu-lxc-fleet.sh ps                # container status on all
#   ./mu-lxc-fleet.sh logs 301 200      # last 200 compose log lines from CT 301
#   ./mu-lxc-fleet.sh exec 302 -- df -h # arbitrary command in CT 302
set -euo pipefail

WORKERS=(301 302)                          # worker CT IDs - add 303... to scale
GIT_DIR=/root/manga-src                     # repo clone inside each CT
COMPOSE_DIR=/root/manga-src/deploy/worker   # worker compose inside each CT
SVCS="manga-worker flaresolverr solver autoheal"

in_ct(){ pct exec "$1" -- bash -lc "$2"; }

cmd=${1:-}; shift || true
case "$cmd" in
  update)
    targets=("$@"); [ ${#targets[@]} -eq 0 ] && targets=("${WORKERS[@]}")
    for ct in "${targets[@]}"; do
      echo "== CT $ct: git pull + rebuild + up =="
      in_ct "$ct" "cd $GIT_DIR && git pull --ff-only && cd $COMPOSE_DIR && docker compose up -d --build $SVCS"
    done ;;
  restart)
    for ct in "${WORKERS[@]}"; do echo "== CT $ct =="; in_ct "$ct" "cd $COMPOSE_DIR && docker compose restart"; done ;;
  ps)
    for ct in "${WORKERS[@]}"; do echo "== CT $ct =="; in_ct "$ct" "docker ps --format 'table {{.Names}}\t{{.Status}}'"; done ;;
  logs)
    ct=${1:?need CTID}; n=${2:-100}; in_ct "$ct" "cd $COMPOSE_DIR && docker compose logs --tail=$n" ;;
  exec)
    ct=${1:?need CTID}; shift; [ "${1:-}" = "--" ] && shift; in_ct "$ct" "$*" ;;
  *) echo "usage: $0 update [CTID...] | restart | ps | logs CTID [N] | exec CTID -- cmd..."; exit 1 ;;
esac
