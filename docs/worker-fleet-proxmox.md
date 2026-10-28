# Worker fleet on the Dell Proxmox box (i7 13/14-gen, 32GB DDR5)

Runbook for turning the spare Dell + Proxmox host into **3 roomy manga-utils worker VMs**, each with
its own Tailscale exit node (distinct egress IP), driven from the host with `deploy/mu-fleet.sh`.

Main stays where it is (`100.67.109.13`). This box only adds workers.

---

## 0. Why 3 workers on one box (the one rule that makes it worth it)

The payoff isn't CPU parallelism - it's **egress diversity**. Each worker MUST leave through a
**different IP**, or MangaFire sees 3x the hits from one address and rate-limits you (exactly what we
fight now). So: **one distinct Mullvad-via-Tailscale exit node per worker**. Reusing a city = reusing an
IP = no gain. That also speeds up MangaFire scans: each exit is a separate CF/@waf session, so the
solver work runs genuinely in parallel instead of serialized on one session.

RAM is NOT the limit (a worker uses ~2-3GB); the i7's cores and the number of distinct exits are.
8GB/VM is generous headroom - fine, just don't allocate all 32 (leave ~4GB for the host).

---

## 1. Dell BIOS config (do this first, at the machine)

Enter BIOS (F2 at the Dell splash). Settings vary slightly by model (OptiPlex/Precision), names in parens:

- **Auto power-on after a power outage** - Power Management -> **AC Recovery** (a.k.a. "AC Behavior")
  -> set to **Power On** (or "Last Power State" if you'd rather it resume whatever it was).
  This is the "comes back after the power blips" setting.
- **Deep Sleep Control** -> **Disabled** - so it actually powers on from AC instead of staying in deep
  sleep. (Power Management section.)
- **(optional) Auto On Time** - Power Management -> schedule a daily power-on if you ever want it.
- **Virtualization for Proxmox passthrough** - Virtualization Support ->
  - **Virtualization (VT-x)** = Enabled
  - **VT for Direct I/O (VT-d)** = Enabled   (needed if you ever pass a device straight to a VM)
- **Boot** - UEFI mode, boot order points at the **boot NVMe** (the OS drive, NOT the 512 burn drive).
  Secure Boot can stay on for Proxmox 8; turn it off only if the installer complains.
- **(optional) Wake-on-LAN** - Enabled if you want to wake it remotely.

Save + exit. After this it'll auto-return after any power loss, which is what you want for an always-on host.

---

## 2. Storage layout (keep download churn off the boot drive)

- **Boot drive** = Proxmox OS + VM OS disks only. No download traffic.
- **The spare 512GB 2280 NVMe = the "burn" drive** = all the transient worker download/staging churn.
  In the full-node model each worker downloads a chapter, verifies, **ships it to main over Tailscale,
  then deletes its local copy** - so this drive just churns and empties. It's expendable (hence "burn").

Proxmox setup for the burn drive:
```bash
# identify it (NOT the boot disk)
lsblk -o NAME,SIZE,MODEL,MOUNTPOINT
# wipe + add as an LVM-thin or directory storage for VM disks
# easiest: Datacenter -> Storage -> Add -> LVM-Thin (or Directory) on /dev/nvmeXn1
```
Then give **each worker VM its scratch/OS-churn disk on this storage**, keeping the boot drive clean.
Keep the per-VM disks modest (staging is transient) - ~40-60GB each is plenty.

---

## 3. Create the 3 worker VMs

Per VM (clone one template, then repeat):
- **8GB RAM**, 2-3 vCPU, disk on the **burn drive** storage.
- Debian 12 (or 13) minimal.
- Inside the guest, once:
  ```bash
  # docker
  curl -fsSL https://get.docker.com | sh
  # qemu guest agent (lets mu-fleet drive it from the host by VMID)
  apt install -y qemu-guest-agent && systemctl enable --now qemu-guest-agent
  # tailscale
  curl -fsSL https://tailscale.com/install.sh | sh
  tailscale up            # join the tailnet
  # repo
  git clone <repo> /root/manga-src
  ```
- In Proxmox: **VM -> Options -> QEMU Guest Agent -> Enabled**, then reboot the VM.
- Bring up the worker stack: `cd /root/manga-src/deploy/worker && docker compose up -d --build manga-worker`
  (plus its `solver` + `flaresolverr` sidecars per the compose).

### Per-worker egress (the important bit)
Give **each** VM a **different** Mullvad-via-Tailscale exit node:
```bash
tailscale set --exit-node=<us-mia-...> --exit-node-allow-lan-access=true   # VM A
tailscale set --exit-node=<us-atl-...> --exit-node-allow-lan-access=true   # VM B
tailscale set --exit-node=<us-nyc-...> --exit-node-allow-lan-access=true   # VM C
```
`--exit-node-allow-lan-access` keeps the tailnet reachable so **main can still call the worker's
`/worker/*` API** while its internet egress goes out the exit node. List available exits with
`tailscale exit-node list`.

### Register each as a node
In the app's **Workers** screen, add each VM: URL `http://<vm-tailscale-ip>:8790`, same `WORKER_TOKEN`.
Turn on **"Use workers to check for updates."** The round-robin scan then fans across main + the Pi +
these 3, each a separate egress IP.

---

## 4. Drive them all from the Proxmox host: `deploy/mu-fleet.sh`

No more SSH-into-each. From the **Proxmox host** (needs `apt install -y jq`):

```bash
cd /root/manga-src/deploy && chmod +x mu-fleet.sh

./mu-fleet.sh update            # git pull + rebuild + up on EVERY worker
./mu-fleet.sh update 202        # just VM 202
./mu-fleet.sh restart           # restart the worker container on all
./mu-fleet.sh ps                # container status on all
./mu-fleet.sh logs 201 200      # last 200 log lines from VM 201
./mu-fleet.sh exec 203 -- df -h # arbitrary command in VM 203
./mu-fleet.sh exit-node         # (re)apply each VM's mapped Tailscale exit node
```

Edit the top of the script first: set `WORKERS=(…)` to your real VM IDs, the `GIT_DIR`/`COMPOSE_DIR`
paths if different, and the `EXIT_NODE` map (VMID -> exit node). It works by `qm guest exec <vmid>`
(hence the guest-agent requirement); output is buffered, so for a live `logs -f` just SSH that one VM.

---

## 5. Honest caveats

- **Single point of failure** - a host reboot drops all 3 workers at once. Main's local fallback keeps
  the scan correct (no title dropped), you just lose the speed until they're back.
- **Need enough distinct Mullvad exits** for the worker count - 3 workers = 3 different cities.
- **Watch total Chromium RAM** - each `flaresolverr` is ~0.5-1GB; 3 of them is fine at 32GB, don't pile on.
- This replaces buying phones/SBCs: zero new hardware, x86 (Conscrypt works), strong CPU for the solver.
