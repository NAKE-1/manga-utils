#!/usr/bin/env bash
# mu-vmgen - build a Debian cloud-init TEMPLATE once, then clone+configure the worker VMs, on the Proxmox HOST.
# Each VM gets: OS disk on the BOOT storage (OS + Docker live here), a blank BURN disk on the burn-SSD storage
# (downloads churn only, like the Orange Pi's /mnt/ssd), cloud-init (user + your SSH key + DHCP), guest agent.
#
# PREREQS (one-time, see docs/worker-fleet-proxmox.md):
#   - A Proxmox storage pointing at the burn SSD. Verify the drive with:  lsblk -o NAME,SIZE,MODEL,SERIAL,MOUNTPOINT
#     then Datacenter -> Storage -> Add -> LVM-Thin (or Directory) on that drive, named e.g. "burn".
#   - Your SSH public key on the host (e.g. /root/.ssh/id_ed25519.pub).
#   - Debian cloud image downloaded (this script fetches it if missing).
# Then:  ./mu-vmgen.sh            # builds the template if needed, then creates every VM in WORKERS
#        ./mu-vmgen.sh 201        # just (re)create VM 201
set -euo pipefail

# ---- config -------------------------------------------------------------------------------------
TEMPLATE_ID=9000
STORAGE=local-lvm                 # BOOT storage: VM OS disk + Docker
BURN_STORAGE=burn                 # storage you created on the burn SSD (downloads churn)
BRIDGE=vmbr0
CIUSER=mu                         # cloud-init login user
SSHKEY=/root/.ssh/id_ed25519.pub  # your pubkey (change to id_rsa.pub if that's what you have)
IMG_URL=https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.qcow2
IMG=/var/lib/vz/template/iso/debian-12-genericcloud-amd64.qcow2

# vmid:name:cores:ramMB:osGB:burnGB   (3 roomy workers; edit to taste)
WORKERS=(
  "201:mu-worker-1:4:8192:20:80"
  "202:mu-worker-2:4:8192:20:80"
  "203:mu-worker-3:4:8192:20:80"
)
# -------------------------------------------------------------------------------------------------

[ -f "$SSHKEY" ] || { echo "no SSH pubkey at $SSHKEY - generate one (ssh-keygen -t ed25519) or fix SSHKEY"; exit 1; }

make_template() {
  qm status "$TEMPLATE_ID" &>/dev/null && { echo "template $TEMPLATE_ID exists - skipping build"; return; }
  [ -f "$IMG" ] || { echo "downloading Debian cloud image..."; mkdir -p "$(dirname "$IMG")"; wget -qO "$IMG" "$IMG_URL"; }
  echo "building cloud-init template $TEMPLATE_ID"
  # --cpu host is REQUIRED (libcef/JCEF + numpy need x86-64-v2; generic kvm64 SIGILLs). See memory/manga-utils-vm-cpu-host.
  qm create "$TEMPLATE_ID" --name mu-worker-tmpl --machine q35 --cpu host \
    --memory 2048 --cores 2 --net0 virtio,bridge="$BRIDGE" \
    --scsihw virtio-scsi-single --ostype l26 --agent enabled=1
  qm set "$TEMPLATE_ID" --scsi0 "$STORAGE:0,import-from=$IMG"   # import+attach the cloud image in one step (PVE 7.2+)
  qm set "$TEMPLATE_ID" --ide2 "$STORAGE:cloudinit"
  qm set "$TEMPLATE_ID" --boot order=scsi0 --serial0 socket --vga serial0
  qm set "$TEMPLATE_ID" --ciuser "$CIUSER" --sshkeys "$SSHKEY" --ipconfig0 ip=dhcp
  qm template "$TEMPLATE_ID"
}

make_vm() {  # make_vm vmid name cores ram osGB burnGB
  local id=$1 name=$2 cores=$3 ram=$4 osgb=$5 burngb=$6
  qm status "$id" &>/dev/null && { echo "VM $id exists - skipping (destroy it first to recreate)"; return; }
  echo "== cloning $id ($name): ${cores}c/${ram}MB, ${osgb}G boot + ${burngb}G burn =="
  qm clone "$TEMPLATE_ID" "$id" --name "$name" --full 1 --storage "$STORAGE"
  qm set "$id" --cores "$cores" --memory "$ram"
  qm disk resize "$id" scsi0 "${osgb}G"
  qm set "$id" --scsi1 "$BURN_STORAGE:${burngb}"          # blank download-churn disk on the burn SSD
  qm set "$id" --ciuser "$CIUSER" --sshkeys "$SSHKEY" --ipconfig0 ip=dhcp --onboot 1
  qm start "$id"
}

make_template
if [ "$#" -gt 0 ]; then
  for want in "$@"; do for w in "${WORKERS[@]}"; do IFS=: read -r id n c r o b <<<"$w"; [ "$id" = "$want" ] && make_vm "$id" "$n" "$c" "$r" "$o" "$b"; done; done
else
  for w in "${WORKERS[@]}"; do IFS=: read -r id n c r o b <<<"$w"; make_vm "$id" "$n" "$c" "$r" "$o" "$b"; done
fi
echo "done. VMs are booting with DHCP. Find IPs: qm guest cmd <vmid> network-get-interfaces (after agent is up),"
echo "then run deploy/mu-vm-provision.sh inside each (ssh ${CIUSER}@<ip>), or via mu-fleet once the agent is up."
