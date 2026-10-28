#!/usr/bin/env bash
# mu-fleet - drive the manga-utils worker VMs from the Proxmox HOST, by VMID, so you never have to
# SSH into each worker to run docker compose. Uses the QEMU guest agent (qm guest exec).
#
# One-time per worker VM:
#   apt install -y qemu-guest-agent && systemctl enable --now qemu-guest-agent
#   Proxmox -> VM -> Options -> QEMU Guest Agent -> Enabled, then reboot the VM.
# On the Proxmox host: apt install -y jq
#
# Customize WORKERS (your VM IDs), the paths, and the VMID->exit-node map, then:
#   ./mu-fleet.sh update              # git pull + rebuild + up on EVERY worker
#   ./mu-fleet.sh update 202          # just VM 202
#   ./mu-fleet.sh restart             # restart the worker container on all
#   ./mu-fleet.sh logs 201 200        # last 200 log lines from VM 201
#   ./mu-fleet.sh exec 203 -- df -h   # arbitrary command in VM 203
#   ./mu-fleet.sh exit-node           # apply each VM's Tailscale exit node (egress IP)
set -uo pipefail

WORKERS=(201 202 203)                 # <-- your worker VM IDs
GIT_DIR=/root/manga-src               # repo path INSIDE each worker VM
COMPOSE_DIR=$GIT_DIR/deploy/worker
declare -A EXIT_NODE=( [201]=us-mia-wg-001 [202]=us-atl-wg-001 [203]=us-nyc-wg-001 )  # VMID -> tailscale exit node (distinct = distinct IP)

# Run a shell command inside guest <vmid> and print its stdout+stderr. Long timeout covers the docker build.
run() {
  local id=$1; shift
  local j
  j=$(qm guest exec "$id" --timeout 1800 -- bash -lc "$*" 2>&1) \
    || { echo "[$id] guest exec failed - is qemu-guest-agent running in the VM?"; echo "$j"; return 1; }
  echo "$j" | jq -r '(."out-data" // "") + (if (."err-data" // "") != "" then "\n"+."err-data" else "" end)' 2>/dev/null || echo "$j"
}
pick() { if [ "$#" -gt 0 ]; then printf '%s\n' "$@"; else printf '%s\n' "${WORKERS[@]}"; fi; }

act=${1:-help}; shift || true
case "$act" in
  update)  for id in $(pick "$@"); do echo "== $id: pull + rebuild =="; run "$id" "cd $GIT_DIR && git pull && cd $COMPOSE_DIR && docker compose up -d --build manga-worker"; done ;;
  up)      for id in $(pick "$@"); do echo "== $id: up =="; run "$id" "cd $COMPOSE_DIR && docker compose up -d manga-worker"; done ;;
  restart) for id in $(pick "$@"); do echo "== $id: restart =="; run "$id" "cd $COMPOSE_DIR && docker compose restart manga-worker"; done ;;
  down)    for id in $(pick "$@"); do echo "== $id: down =="; run "$id" "cd $COMPOSE_DIR && docker compose down"; done ;;
  ps)      for id in $(pick "$@"); do echo "== $id =="; run "$id" "docker ps --format '{{.Names}}  {{.Status}}'"; done ;;
  logs)    id=${1:?usage: mu-fleet logs <vmid> [lines]}; run "$id" "cd $COMPOSE_DIR && docker compose logs --tail=${2:-120} manga-worker" ;;
  exec)    id=${1:?usage: mu-fleet exec <vmid> -- <cmd>}; shift; [ "${1:-}" = "--" ] && shift; run "$id" "$*" ;;
  exit-node) for id in $(pick "$@"); do n=${EXIT_NODE[$id]:-}; [ -z "$n" ] && { echo "[$id] no exit node mapped"; continue; }; echo "== $id: exit-node $n =="; run "$id" "tailscale set --exit-node=$n --exit-node-allow-lan-access=true"; done ;;
  *) sed -n '2,20p' "$0" ;;  # print the header/usage
esac
