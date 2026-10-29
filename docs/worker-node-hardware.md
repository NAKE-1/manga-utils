# Worker-node hardware notes (expansion deferred)

Status (2026-10-29): **1 worker node is enough for now** (the Orange Pi 5 Plus = node-1). Scaling to more
x86 worker VMs is **deferred** - revisit when we actually want faster full-library scans / more egress IPs.
This doc saves the research + the hard-won C-state lesson so we don't re-learn it.

---

## The big lesson: deep C-state / idle-power HANG on mini PCs + Proxmox

We burned a lot of hours on a **Dell OptiPlex 7000 Micro (i7-12700T, 32GB DDR5)** that **froze at idle**
(not reboot - froze solid, screen stuck on last frame, no dmesg/dump). What we proved:

- **NOT thermal** (68-92C under load, fan ramps fine), **NOT RAM/CPU** (passed stress + Dell ePSA diagnostic,
  zero MCEs), **NOT power wattage** (180W brick).
- It's the board/firmware mishandling the **deep idle power states** - the CPU (or a device) enters a deep
  low-power state and **doesn't wake** -> hard hang. Happens on **both Linux AND Windows** (so not OS-specific
  tuning; it's the hardware at the deepest states).
- Confirmed via a reboot-surviving C-state bisect (`deploy/cstate-bisect.sh`): on the 12700T the `intel_idle`
  ladder is `POLL(0) / C1E(1) / C6(2) / C8(3) / C10(4)`. **C10 (deepest) = hang.** C1E = stable but hot (~90C).
  C6/C8 (the cool+likely-safe middle) never got cleanly tested before we pivoted.
- On Windows, HWiNFO showed the package only reaching **PC8** normally (no PC10) - i.e. Windows stays
  shallower; Linux's `intel_idle` drove it into C10 and hung.

### The fix (works on ANY mini PC with this problem)
Cap the idle power states via kernel cmdline on first Proxmox boot:
```
GRUB_CMDLINE_LINUX_DEFAULT="quiet intel_idle.max_cstate=3 nvme_core.default_ps_max_latency_us=0 pcie_aspm=off"
```
- `intel_idle.max_cstate=3` -> cap CPU at C8 (deep = cool, but not the C10 that hangs). Drop to `=2` (C6) or
  `=1` (C1E, hot) if C8 still hangs.
- `nvme_core.default_ps_max_latency_us=0` -> disable deep NVMe APST (SSD-won't-wake freeze).
- `pcie_aspm=off` -> disable PCIe link power-down (another idle-wake hang).

Verify after reboot: `cat /sys/module/intel_idle/parameters/max_cstate` (should read 3).
BIOS equivalent (blunt, all-or-nothing, runs hot): **C-States Control = Disabled**.
Caveat: Proxmox **9.x** can reintroduce the freeze after upgrade - re-verify the cap after any major upgrade.

This is a **universal mini-PC + Proxmox gotcha** (N100/N5105/Ryzen all hit it), NOT unique to Dell - so apply
these params to any new worker box as insurance.

---

## Dell OptiPlex 7000 Micro vs HP Elite Mini 800 G9

Near-identical hardware; the difference is firmware quality / reputation.

| | Dell OptiPlex 7000 Micro | HP Elite Mini 800 G9 |
|---|---|---|
| Socket / gen | LGA1700, 12th | LGA1700, 12th-14th |
| Chipset | Q670 | Q670 |
| RAM | DDR5 SODIMM, 2 slots, 64GB | DDR5 SODIMM, 2 slots, 64GB |
| Storage | 2x M.2 | 2x M.2 2280 |
| Power | external brick | external brick |
| **C-state/idle reputation** | **documented problems** (OptiPlex 7000/3000 12th-gen) | **no reports found** (well-reviewed) |

- Dell 7000/3000 12th-gen: documented idle/C-state instability (Level1Techs user RMA'd; Proxmox OptiPlex 3000
  only fixed by disabling C-states). Our unit fit the pattern.
- HP 800 G9: no documented C-state freeze pattern, ServeTheHome top pick. ~85-90% it just works; not *proven*
  immune (universal mini-PC gotcha applies), but the kernel cap fixes it there too.

---

## Transplant plan (if/when we escape the Dell or expand)

Our good parts carry over - only the (possibly bad) **Dell board** gets left behind:
- **i7-12700T** (LGA1700)
- **2x 16GB DDR5 SODIMM** (32GB)
- **2x M.2 NVMe SSDs** (boot + burn)

Target: **HP Elite Mini 800 G9 barebones** (~$140 used, local). HP ships this exact model with the 12700T +
DDR5 + dual M.2, so everything drops in. Before buying, confirm the seller includes the **power brick + heatsink/fan**.
- **Power adapter** if not included: HP 800 G9, **19.5V, 120W**, barrel tip **7.4mm x 5.0mm** (P/N L89695-001).
  90W is enough for the 35W 12700T; 120W for headroom.
- Have **thermal paste + isopropyl** on hand (clean old paste off the CPU, fresh paste on the used cooler).
- First boot BIOS (F10): VT-x ON, VT-d ON, UEFI, "After Power Loss" = Power On.
- Fresh Proxmox install (go **9.2** - newest kernel, and 8.x is EOL). Test clean at full C-states first; if it
  ever freezes, apply the three-param line above.

For a single worker you don't even need Proxmox - bare Debian + `deploy/worker/docker-compose.yml` is simpler.
Use Proxmox VMs when scaling to multiple workers (per-worker Mullvad exit isolation). Full multi-VM flow:
`deploy/mu-vmgen.sh` + `deploy/mu-vm-provision.sh` + `docs/worker-fleet-proxmox.md`.
