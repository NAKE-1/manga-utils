# PLAN — VPN split: Mullvad for sources, Tailscale for the user

**Status: deferred — circle back near the end (deploy-time, after Proxmox move).**

## Goal

Two independent tunnels, cleanly separated:

- **Egress (source-facing):** everything the app does to *reach a source* — extension scraping,
  chapter/page downloads, future streaming, FlareSolverr's headless browser — exits through **Mullvad**.
- **Ingress (user-facing):** the webUI and its proxied content reach *me* over **Tailscale**.

Why it "just works" with no app code: the client never talks to a source. `:server`'s `/img/page`
(and future stream endpoints) fetch from the source, then re-serve to the device. So "fetch via
Mullvad, deliver over Tailscale" falls straight out of the existing server-proxied content path — the
user only ever sees the Tailscale side.

## Target box

Proxmox host → OpenWRT (router VM) + Debian VM (Docker) + other VMs.

## Recommended: gluetun beside the container + Tailscale sidecar. No code, no OpenWRT PBR.

In the **Debian Docker** VM:

- Run `:server` **and** FlareSolverr behind **gluetun** (`network_mode: service:gluetun`, Mullvad
  WireGuard). All source egress goes through Mullvad; gluetun's **kill switch** means a VPN drop can't
  leak the real IP. Self-contained in one `docker-compose.yml`; OpenWRT stays out of it.
- Run **Tailscale** as its own container (sidecar) or on the Debian host, advertising only the webUI
  port. That ingress traffic never enters gluetun. Two tunnels, independent.

OpenWRT's job stays minimal: plain NAT/firewall for the VM. No policy routing needed.

Sketch:

```yaml
services:
  gluetun:
    image: qmcgaw/gluetun
    cap_add: [NET_ADMIN]
    environment:
      VPN_SERVICE_PROVIDER: mullvad
      VPN_TYPE: wireguard
      # WIREGUARD_PRIVATE_KEY / ADDRESSES / SERVER_CITIES from Mullvad
    # gluetun default firewall = kill switch
  server:
    image: manga-utils
    network_mode: "service:gluetun"      # egress via Mullvad
  flaresolverr:
    image: ghcr.io/flaresolverr/flaresolverr
    network_mode: "service:gluetun"      # MUST also be behind Mullvad
  tailscale:
    image: tailscale/tailscale
    # ingress only — NOT behind gluetun; serves the webUI over the tailnet
```

## Alternative: OpenWRT owns the Mullvad tunnel (more moving parts)

Put Mullvad WireGuard on OpenWRT and policy-route only the Docker VM's subnet/VLAN out `wg-mullvad`,
everything else out normal WAN. Tailscale on the Debian host serves the webUI over LAN, untouched.
Works, but you hand-maintain PBR + a kill-switch firewall rule on the router. Only worth it if you
later want *several* VMs sharing one centralized Mullvad tunnel.

## Non-negotiables (privacy boundary — do not skip)

- **FlareSolverr behind Mullvad too** — its browser makes its own outbound requests; untunneled, a
  Cloudflare-gated source leaks the real IP.
- **DNS out through Mullvad**, not the ISP (gluetun handles it; if OpenWRT owns the tunnel, force the
  VM/VLAN DNS through `wg-mullvad`).
- **Don't route Tailscale through Mullvad** (no Tailscale Mullvad *exit node* for the server) — keep
  the tunnels independent so a Mullvad drop can't cut webUI access, and the kill switch only gates
  source traffic.

## App-level proxy (rejected unless surgical control is needed)

Point the OkHttp client in `source-api/.../network/NetworkHelper.kt` at a SOCKS5/HTTP proxy egressing
via Mullvad, bind Ktor to the Tailscale interface. More precise, but you own the plumbing and must
route FlareSolverr + the downloader through it or they leak. Not worth it over gluetun.

## Verdict

**gluetun in the Debian Docker VM (egress) + Tailscale sidecar/host (ingress); OpenWRT stays a plain
router.** Move the VPN onto OpenWRT only if multiple VMs later need to share one Mullvad tunnel.
