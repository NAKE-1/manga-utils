#!/usr/bin/env bash
# Drive manga-utils worker LXCs from the Proxmox HOST via `pct exec` - no SSH, no guest agent,
# no per-container login. Run it from the Proxmox root shell.
#
#   ./mu-lxc-fleet.sh update            # git pull + rebuild manga-worker only + up on EVERY worker CT (fast)
#   ./mu-lxc-fleet.sh update 302        # just CT 302
#   ./mu-lxc-fleet.sh update --all      # also rebuild the solver from source (slow; only if deploy/solver changed)
#   ./mu-lxc-fleet.sh restart           # docker compose restart on all
#   ./mu-lxc-fleet.sh ps                # container status on all
#   ./mu-lxc-fleet.sh logs 301 200      # last 200 compose log lines from CT 301
#   ./mu-lxc-fleet.sh prune             # reclaim build cache + dangling images on all (safe; keeps live images)
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
    # Default: rebuild ONLY manga-worker (the app image, which changes every commit) and bring the
    # sidecars up on their existing images - never recompiling the solver's Chromium, which pins this
    # CPU to load ~150 and is the real reason a rebuild drags. `up -d` (no --build) still builds a
    # sidecar whose image is missing (first setup), so this is safe to run on a fresh CT too.
    # Pass --all to also rebuild the solver from source (slow) - only needed when deploy/solver changed.
    build_all=0
    if [ "${1:-}" = "--all" ]; then build_all=1; shift; fi
    targets=("$@"); [ ${#targets[@]} -eq 0 ] && targets=("${WORKERS[@]}")
    for ct in "${targets[@]}"; do
      if [ "$build_all" = 1 ]; then
        echo "== CT $ct: git pull + rebuild ALL (incl solver/Chromium - slow) + up =="
        in_ct "$ct" "cd $GIT_DIR && git pull --ff-only && cd $COMPOSE_DIR && docker compose up -d --build"
      else
        echo "== CT $ct: git pull + rebuild manga-worker only + up (sidecars kept) =="
        in_ct "$ct" "cd $GIT_DIR && git pull --ff-only && cd $COMPOSE_DIR && docker compose up -d --build manga-worker && docker compose up -d"
      fi
    done ;;
  restart)
    for ct in "${WORKERS[@]}"; do echo "== CT $ct =="; in_ct "$ct" "cd $COMPOSE_DIR && docker compose restart"; done ;;
  ps)
    for ct in "${WORKERS[@]}"; do echo "== CT $ct =="; in_ct "$ct" "docker ps --format 'table {{.Names}}\t{{.Status}}'"; done ;;
  logs)
    ct=${1:?need CTID}; n=${2:-100}; in_ct "$ct" "cd $COMPOSE_DIR && docker compose logs --tail=$n" ;;
  prune)
    targets=("$@"); [ ${#targets[@]} -eq 0 ] && targets=("${WORKERS[@]}")
    for ct in "${targets[@]}"; do
      echo "== CT $ct: prune build cache + dangling images (live images kept) =="
      in_ct "$ct" "docker builder prune -af; docker image prune -f; docker system df"
    done ;;
  exec)
    ct=${1:?need CTID}; shift; [ "${1:-}" = "--" ] && shift; in_ct "$ct" "$*" ;;
  *) echo "usage: $0 update [--all] [CTID...] | restart | ps | logs CTID [N] | prune [CTID...] | exec CTID -- cmd..."; exit 1 ;;
esac
