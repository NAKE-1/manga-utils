# ! DOCKER SERVER PLAN — MAIN

**Status:** DECIDED (architecture) / NOT STARTED (build). Last updated 2026-07-31.
**Scope:** Deploy `:server` (the phone-first web UI + source engine) as a Docker container on a
Proxmox-hosted Debian VM, with MangaFire working via in-container JCEF (real Chromium), source egress
behind Mullvad, and web access over Tailscale.

This is the single source of truth for the deploy. Companion docs:
`docs/PLAN-vpn-split-mullvad-tailscale.md` (network detail), memory `deploy-plan` (VM sizing).

---

## 1. Decisions (locked)

| # | Decision | Why |
|---|----------|-----|
| D1 | **Target A: JCEF runs INSIDE the `:server` container** | MangaFire is a HARD requirement; JCEF (offscreen Chromium) is the only thing that fakes Chrome's JA3/H2 for Cloudflare. okhttp/JSSE can't. |
| D2 | **FlareSolverr = FALLBACK, not primary** | JCEF is now the main CF path. Some lighter sources still work on FlareSolverr alone; keep it as a sibling for those + as a JCEF fallback. |
| D3 | **Debian VM + Docker** (not LXC) | A real kernel lets Chromium's `--no-sandbox` + user namespaces work without privileged/nesting LXC pain. |
| D4 | **Pin JCEF native + Docker base-image digest** | Freezes the whole stack → low maintenance. The Chromium binary is a first-boot download; only OS libs live in the image. |
| D5 | **Source egress → gluetun/Mullvad (kill switch). Web UI → Tailscale.** | The split is automatic: web UI is inbound-initiated (replies over Tailscale regardless of default route); source reqs are outbound-initiated (follow gluetun→Mullvad). No per-request routing. |
| D5b | **Tailscale runs on the Debian VM HOST, not a container** | You asked for it on the VM; simpler than a sidecar. gluetun publishes 8080 to host loopback; `tailscale serve 8080` exposes it tailnet-only. |
| D6 | **OpenWRT = plain NAT/firewall gate** (VM or container, negligible diff) | WAN↔interior boundary only; VPN does NOT live on OpenWRT unless multiple VMs later share one tunnel. |

---

## 2. Topology

**Mental model:** gluetun owns ONE network stack; `:server` + `flaresolverr` plug into it
(`network_mode: service:gluetun`), so to each other they're on `localhost` and their only way out is
gluetun's Mullvad tunnel. **Tailscale runs on the Debian VM host** (not a container — per decision D5b)
and exposes the UI via `tailscale serve`.

```
┌───────────────────────────────────────────────────────────────────────────┐
│  DEBIAN VM  (Proxmox, behind OpenWRT gate)   ── tailscaled runs HERE ──      │
│                                                                             │
│  ┌─────────────────────────  gluetun's network namespace  ───────────────┐ │
│  │  (ONE shared network stack — server + flaresolverr plug into it)       │ │
│  │                                                                        │ │
│  │   ┌──────────────┐   ┌──────────────┐                                  │ │
│  │   │   gluetun    │   │   :server    │   ── server reaches FS at ──      │ │
│  │   │  (owner)     │   │  (JCEF app)  │      localhost:8191               │ │
│  │   │  WireGuard   │   │  :8080       │                                   │ │
│  │   │  + killswitch│   │  ▲ :8191     │   ┌──────────────┐                │ │
│  │   └──────┬───────┘   └──┼───────────┘   │ flaresolverr │ (shares netns) │ │
│  │         │              └────────────────│  :8191       │                │ │
│  │         │  gluetun publishes 8080 →     └──────────────┘                │ │
│  │         │  127.0.0.1:8080 on the VM host                                │ │
│  └─────────┼──────────────────────────────────────────────────────────────┘ │
│            │ ALL outbound (source reqs, JCEF fetch, FS solves, 1st-boot dl)   │
│            ▼                                    ▲ VM host loopback :8080       │
│     ══ Mullvad WireGuard ══> Internet          │                              │
│        (killswitch drops if VPN down)   `tailscale serve 8080` (host) ────────┤
│                                                │ tailnet                      │
│  volumes: ./data→/data (library.db, extensions, bin/jcef, cache/jcef)         │
│           /mnt/library→/library (downloaded chapters, separate disk)          │
└──────────────────────────────────────────────────┼──────────────────────────┘
   Internet (WAN) → [ OpenWRT gate: 2 NICs,         │
   WAN in ─ LAN out to switch/PC, plain NAT ] → VM  ▼  your phone/PC ══ Tailscale ══ (reading, web UI)
```

**How each path flows:**
```
Source request  (search / view-undownloaded / JCEF fetch / FS solve)
   :server ─localhost→ (FS? localhost:8191) → gluetun ══Mullvad══> source site
                                              ▲ killswitch here

Web UI / reading downloaded manga
   phone ══Tailscale══> VM host → tailscale serve → 127.0.0.1:8080 → :server → reads /library
   (inbound-initiated → reply never touches Mullvad; works even if VPN is down)

First boot (one time)
   compose waits until  gluetun = healthy, THEN server starts
   :server → CEF needs native → downloads ~100MB ══Mullvad══> GitHub → extracts to /data/bin/jcef
   (persisted on the volume → never re-downloads on recreate)
```

- **Reading DOWNLOADED manga** = served off `/library` on local disk → no egress → Tailscale-only.
- **Viewing UNDOWNLOADED / downloading / extension reqs** = outbound → gluetun → Mullvad.
- **Access is Tailscale-only:** gluetun publishes 8080 to **loopback** (`127.0.0.1:8080`), not the LAN, so
  nothing on the interior LAN can reach it directly — only `tailscale serve` exposes it, on the tailnet.

---

## 3. The container's hard part: headless Chromium (JCEF)

JCEF is a **real Chromium**. Two separable pieces:

### 3a. The Chromium binary — first-boot download (already implemented)
`CefManager.initBlocking()` downloads the JB JCEF native (~100 MB) from the JetBrains Runtime GitHub
release on first boot and extracts it. **We do NOT bake this into the image.**
- Pinned constants (keep in lockstep): `JCEF_VERSION` + `JBR_RELEASE` in
  `android-compat/.../webkit/CefManager.kt`.
- Installs to `<root>/bin/jcef`; runtime cache (cookies incl. `cf_clearance`, profile) to `<root>/cache/jcef`.
- **Optional improvement:** mirror the native tarball on our own host and point the downloader at it to
  drop the GitHub-API dependency + rate limits. Downloads fine through gluetun/Mullvad either way.

### 3b. The OS shared libraries — MUST be in the image (apt)
The Chromium binary dynamically links these at load; without them it won't even start. These are NOT
downloadable at runtime (need root apt, wouldn't persist). This is the actual Dockerfile work — one
`apt-get` line, ~30 packages:

```
libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0
libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2
libasound2 libatspi2.0-0 libgtk-3-0 libx11-6 libxcb1 libxext6 libxi6 libxtst6
libxrender1 libxshmfence1 libglib2.0-0 libgdk-pixbuf-2.0-0 dbus dbus-x11
fonts-liberation fonts-noto-color-emoji fontconfig ca-certificates xvfb xauth
```

### 3c. Gotchas that WILL bite (validate early)
1. **AWT is NOT headless here.** `JcefRemoteView` uses `JPanel` + `MouseEvent` + `BufferedImage`, and
   CEF inits GTK. So **do NOT set `-Djava.awt.headless=true`**, and **run under `xvfb`** (a virtual X
   display). Entrypoint: `xvfb-run -a --server-args="-screen 0 1280x1024x24" <launcher>`.
2. **`--no-sandbox` is required** in a container (already partially eased by
   `--change-stack-guard-on-fork=disable`; add `--no-sandbox` to JCEF appArgs behind an env flag, or via
   compose `security_opt`). Prefer keeping the sandbox off at the CEF arg level, VM already isolates.
3. **Two different roots — RECONCILE.** `CefManager` uses `-Dsuwayomi.tachidesk.config.server.rootDir`
   *or* `user.home` for `bin/jcef` + `cache/jcef`. `AppConfig` uses `MU_DATA_DIR` *or*
   `~/.local/share/manga-utils` for `library.db`, extensions, downloads. **In the container, set BOTH to
   the same persisted volume** so the 100 MB native + cookies survive `docker recreate` and don't
   re-download every deploy:
   - `-Dsuwayomi.tachidesk.config.server.rootDir=/data`
   - `MU_DATA_DIR=/data` (and point downloads at `/library` via the in-app download-dir override).
4. **`/dev/shm`** — Chromium wants shared memory; we already pass `--disable-dev-shm-usage`, but also give
   the container `shm_size: 1g` in compose as belt.
5. **`jcef_helper` cleanup** — our hardened `CefManager.shutdown()` kills strays; container stop sends
   SIGTERM → JVM shutdown hook runs → CEF dispose. Ensure the entrypoint execs the JVM as PID 1 (or use
   `--init` / tini) so signals propagate and zombies get reaped.
6. **First boot needs network + writable `/data`.** The native download happens through gluetun/Mullvad;
   confirm the kill switch allows the GitHub (or mirror) host before CEF is ready.

---

## 4. Build artifact

`:server` uses the Gradle **`application`** plugin (mainClass `mangautils.server.MainKt`).
- Build a runnable dist: `./gradlew :server:installDist` → `server/build/install/server/{bin,lib}`.
  (The web UI is built into resources by the `:server:webBuild` task the run/dist already depends on.)
- Multi-stage Dockerfile: stage 1 builds the dist (JDK + Node for Vite); stage 2 is the slim runtime
  (JRE + Chromium libs + xvfb) that copies `build/install/server`. Keeps the final image lean.

---

## 5. Dockerfile (draft — validate 3c before trusting)

```dockerfile
# ---- stage 1: build ----
FROM eclipse-temurin:21-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :server:installDist

# ---- stage 2: runtime ----
FROM eclipse-temurin:21-jre AS runtime           # pin by DIGEST for D4: @sha256:...
RUN apt-get update && apt-get install -y --no-install-recommends \
      libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 \
      libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 \
      libasound2 libatspi2.0-0 libgtk-3-0 libx11-6 libxcb1 libxext6 libxi6 libxtst6 \
      libxrender1 libxshmfence1 libglib2.0-0 libgdk-pixbuf-2.0-0 dbus dbus-x11 \
      fonts-liberation fonts-noto-color-emoji fontconfig ca-certificates xvfb xauth tini \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
ENV MU_DATA_DIR=/data \
    MANGA_WEB_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -Dsuwayomi.tachidesk.config.server.rootDir=/data"
VOLUME ["/data", "/library"]
EXPOSE 8080
ENV MU_JCEF_NO_SANDBOX=1                          # gate: CefManager adds --no-sandbox only when set (D5/#2)
ENTRYPOINT ["/usr/bin/tini","--","/app/entrypoint.sh"]   # tini=PID1 reaps zombies; entrypoint execs the JVM
```
`entrypoint.sh` (Xvfb in background, then `exec` the app so java gets SIGTERM directly — clean CEF teardown, #5):
```bash
#!/usr/bin/env bash
set -e
Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp &   # background virtual display
export DISPLAY=:99
exec /app/bin/server                              # exec → java is the process, gets signals directly
```
*(Confirm the app-plugin launcher honors `JAVA_OPTS` — the `application` plugin's `bin/server` reads it.
If not, fold the `-D`/`-XX` flags into `applicationDefaultJvmArgs` in `server/build.gradle.kts`.)*

---

## 6. docker-compose (draft)

Tailscale is NOT here — it runs on the **VM host** (D5b). Only gluetun/server/flaresolverr are containers.

```yaml
services:
  gluetun:
    image: qmcgaw/gluetun                      # pin by digest
    cap_add: [NET_ADMIN]
    devices: ["/dev/net/tun:/dev/net/tun"]
    environment:
      VPN_SERVICE_PROVIDER: mullvad
      VPN_TYPE: wireguard
      # WIREGUARD_PRIVATE_KEY / WIREGUARD_ADDRESSES / SERVER_CITIES from Mullvad (put in .env)
    ports:
      - "127.0.0.1:8080:8080"                  # LOOPBACK only → tailnet-only access (tailscale serve reaches it)
    volumes: ["./gluetun:/gluetun"]
    restart: unless-stopped
    healthcheck:                               # server waits for this before first-boot download
      test: ["CMD", "wget", "-qO-", "https://am.i.mullvad.net/connected"]
      interval: 15s
      timeout: 10s
      retries: 10

  server:
    build: .
    network_mode: "service:gluetun"            # ALL egress via Mullvad; killswitch protects it
    depends_on:
      gluetun: { condition: service_healthy }  # don't start until the tunnel is up (#6)
    shm_size: 1g                               # belt for Chromium (#4)
    environment:
      MU_DATA_DIR: /data                        # AppConfig root (D5/#3a)
      # JCEF root is set via JAVA_OPTS in the image: -Dsuwayomi.tachidesk.config.server.rootDir=/data
      TZ: America/Chicago
    volumes:
      - "./data:/data"                          # library.db, extensions, bin/jcef native, cache/jcef cookies
      - "/mnt/library:/library"                 # downloads (separate disk)
    restart: unless-stopped                     # in-app "Restart server" = exitProcess → compose relaunches (#7)

  flaresolverr:
    image: ghcr.io/flaresolverr/flaresolverr:latest  # pin by digest
    network_mode: "service:gluetun"            # solves also egress via Mullvad; server reaches it at localhost:8191
    depends_on:
      gluetun: { condition: service_healthy }
    mem_limit: 3g
    environment:
      BROWSER_TIMEOUT: "40000"
    restart: unless-stopped
```
Then in the app **Settings → FlareSolverr URL = `http://localhost:8191`** (same netns = localhost).
**Resolved (was 6a):** Tailscale ingress = `tailscale serve --bg 8080` on the VM host → tailnet-only UI.

---

## 7. State / volumes

| Path | Holds | Backup |
|------|-------|--------|
| `/data` | `library.db`, `extensions/`, `repos.json`, `beta-repos.json`, `mangafire-vrf.json`, `bin/jcef` (native), `cache/jcef` (cookies incl. cf_clearance, profile), `logs/`, `restart.flag` | snapshot regularly |
| `/library` | downloaded chapters (separate ~1–2 TB disk/mount) | grows independently; own snapshot cadence |

- Set the in-app **download dir override** to `/library` (or `MU_DATA_DIR` stays `/data` and downloads go
  to `/library` via Settings).
- `restart.flag`: the in-app dev "Restart server" writes it. In Docker, there's no `start.bat` loop — so
  **restart = container restart policy**. Change `initiateRestart(restart=true)` to still `exitProcess(0)`
  and rely on compose `restart: unless-stopped` to relaunch. (Flag file is a no-op in-container; harmless.)

---

## 8. Proxmox VM sizing (from memory `deploy-plan`, decided 2026-07-16)

Host: 12c / 32 GB / gigabit / 4 TB. VM for **app + FlareSolverr + gluetun + tailscale**:
- **vCPU 4** (burst 6). All I/O-bound; Chromium bursty 1–2 cores/solve.
- **RAM 8 GB fixed, ballooning OFF.** JVM ~3–4 GB, FlareSolverr ~2–3 GB, JCEF Chromium spikes ~1 GB/solve,
  ~1 GB OS/Docker. 6 GB floor.
- **System disk ~50 GB**; **library disk ~1–2 TB** on its own mount.

Tuning:
- **Pin JVM heap:** `-XX:MaxRAMPercentage=50` (or `-Xmx3g`) — else JVM sizes from HOST RAM inside the
  container and over-grabs.
- **Cap FlareSolverr:** `mem_limit: 3g` + `BROWSER_TIMEOUT`; modest concurrency (Chromium can OOM-kill
  the app container under a solve burst — but here they're in separate containers, still cap it).
- Note: with JCEF now primary, FlareSolverr load is LOW (fallback only) — its churn is cosmetic.

---

## 9. Build & bring-up order

**The one thing to do BEFORE the VM:** prove the Dockerfile on any Linux Docker (even your desktop) —
`docker build`, run with a throwaway `/data`, and confirm CEF downloads, logs `CEF runtime ready`, and a
WebView-tester open of `mangafire.to` streams a frame. That validates §3c (xvfb, no-sandbox,
AWT-not-headless, roots) — the riskiest, highest-uncertainty part — before you invest in the VM.

The full, ordered VM procedure lives in **§13 (Setup Runbook)** — Phases 1→9 with a CHECK gate each.
Do not `docker compose up` everything at once; bring services up one at a time as §13 Phase 7 shows.

---

## 10. Verification / success criteria

- [ ] `docker build` succeeds; final image is JRE + libs + app (no JDK/Node in runtime layer).
- [ ] First boot downloads JCEF, logs `CEF runtime ready`, persists to `/data/bin/jcef` (survives recreate).
- [ ] MangaFire: search → open → read an undownloaded chapter works (JCEF path; `JCEF[mangafire.to]: 200`).
- [ ] Interactive WebView (captcha) streams frames + accepts taps in the container.
- [ ] Popup block active (`JCEF: blocked popup` with verbose on); no stray Chromium windows/processes.
- [ ] Source egress shows the **Mullvad** IP; kill switch verified (VPN down → no source traffic leaks).
- [ ] Web UI reachable **only** over Tailscale; reading downloaded manga works with VPN down.
- [ ] Container stop → clean CEF teardown (no lingering `jcef_helper`), restart policy relaunches cleanly.
- [ ] Discord notifications reach the webhook from inside the container (egress/DNS through Mullvad OK).

---

## 11. Open decisions / TODO

- ~~**6a** Tailscale wiring~~ — **RESOLVED (D5b):** tailscaled on the VM host + `tailscale serve 8080`;
  gluetun publishes 8080 to loopback for tailnet-only access.
- **3a** Mirror the JCEF native on our own host, or keep GitHub? (leaning: keep GitHub first, mirror only
  if rate limits bite).
- **7** Confirm `restart.flag` is a harmless no-op in-container and dev "Restart" = `exitProcess` +
  compose restart policy (small code note, not a blocker).
- **5** Confirm `application`-plugin launcher passes `JAVA_OPTS`; else move `-D`/`-XX` into
  `applicationDefaultJvmArgs`.
- Base-image digest to pin (D4) — choose a Temurin 21 JRE Debian bookworm digest.
- Does JCEF OSR truly need xvfb, or does `--off-screen-rendering-enabled` + software GL suffice on this
  base? **Test in step 9.1** — this is the highest-uncertainty item.

---

## 12. Why this shape (one-liner rationale)

Full-fat sources (MangaFire) need real Chrome → JCEF in-container (A). A VM gives Chromium a real kernel.
gluetun wraps ALL app egress in one shot (no routing code) while Tailscale ingress rides its own
inbound-initiated path — so "source traffic via Mullvad, reading via Tailscale" is free. Everything is
pinned, so it's build-once, run-forever.

---

## 13. SETUP RUNBOOK — step by step (do this in order, no skipping)

> Goal: bare Proxmox box → running, Tailscale-reachable server with MangaFire working.
> Each phase ends with a **CHECK** you must pass before moving on. Commands are Debian 12 (bookworm).
> `<angle brackets>` = fill in yourself.

### Phase 0 — Gather FIRST (so you're not hunting mid-setup)
- [ ] Proxmox host reachable; you can create VMs.
- [ ] The interior Proxmox bridge that sits **behind OpenWRT** (e.g. `vmbr1`) — NOT the WAN bridge.
- [ ] **Mullvad** account → generate a **WireGuard** key; note: private key, assigned
      `WIREGUARD_ADDRESSES` (e.g. `10.x.x.x/32`), and a server city.
- [ ] **Tailscale** account + an **auth key** (Settings → Keys), optional tag `tag:manga`.
- [ ] **Discord webhook** URL (optional).
- [ ] Disk sizes: system ~50 GB, library ~1–2 TB.

### Phase 1 — Create the Debian VM on Proxmox
1. Upload the **Debian 12 netinst ISO** (Datacenter → Storage → ISO Images → Upload).
2. Create VM:
   - **General:** name `manga-server`.
   - **OS:** the Debian ISO; type Linux 6.x.
   - **System:** Machine `q35`; BIOS SeaBIOS (simplest) or OVMF/UEFI (add EFI disk); SCSI `VirtIO SCSI single`.
   - **Disk:** system **50 GB**, `Discard` on. (Library disk added in Phase 6.)
   - **CPU:** **4 cores**, Type **`host`** (Chromium needs the real instruction set).
   - **Memory:** **8192 MB**, **Ballooning OFF** (fixed RAM).
   - **Network:** Bridge = your **interior bridge behind OpenWRT** (e.g. `vmbr1`), model `VirtIO`.
3. Start → console → install Debian: minimal, **no desktop**, **yes SSH server**, create your user.
4. `ip a` to get the IP; SSH in for the rest.

**CHECK 1:** `ssh <user>@<vm-ip>` works and `ping -c1 1.1.1.1` succeeds (OpenWRT routing you out).

### Phase 2 — Debian base prep
```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install ca-certificates curl gnupg lsb-release qemu-guest-agent git ufw
sudo systemctl enable --now qemu-guest-agent
sudo timedatectl set-timezone America/Chicago
```
**CHECK 2:** Proxmox summary panel shows the VM's IP (guest agent working).

### Phase 3 — Docker Engine + Compose plugin (official repo)
```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/debian $(lsb_release -cs) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"     # then log out/in
```
**CHECK 3:** after re-login, `docker run --rm hello-world` succeeds.

### Phase 4 — Tailscale on the VM host (D5b)
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --authkey <TS_AUTHKEY> --hostname manga-server --advertise-tags=tag:manga
tailscale ip -4       # note this tailnet IP
```
**CHECK 4:** the VM appears in the Tailscale admin console; `ping <tailnet-ip>` from your phone works.

### Phase 5 — Project layout + secrets
```bash
mkdir -p ~/manga && cd ~/manga
git clone <this-repo-url> src
mkdir -p data gluetun
printf 'WIREGUARD_PRIVATE_KEY=%s\nWIREGUARD_ADDRESSES=%s\nSERVER_CITIES=%s\n' \
  '<mullvad-wg-private-key>' '<10.x.x.x/32>' '<Chicago IL>' > .env
chmod 600 .env
```
Place `Dockerfile`, `entrypoint.sh`, `compose.yaml` (from §5/§6) in `~/manga` (compose `build: ./src`).

**CHECK 5:** `ls ~/manga` → `src/ data/ gluetun/ .env Dockerfile entrypoint.sh compose.yaml`.

### Phase 6 — Library disk (separate, for downloads)
1. Proxmox → VM → Hardware → Add → Hard Disk (**1–2 TB**).
2. In the VM:
```bash
lsblk                                  # find it, e.g. /dev/sdb
sudo mkfs.ext4 -L manga-library /dev/sdb
sudo mkdir -p /mnt/library
echo 'LABEL=manga-library /mnt/library ext4 defaults,nofail 0 2' | sudo tee -a /etc/fstab
sudo mount -a && sudo chown -R "$USER":"$USER" /mnt/library
```
**CHECK 6:** `df -h /mnt/library` shows the big disk.

### Phase 7 — Bring it up (INCREMENTAL — do not `up` everything at once)
```bash
cd ~/manga
# 7a. gluetun alone — prove VPN + killswitch
docker compose up -d gluetun
docker compose logs -f gluetun                                   # wait for healthy
docker compose exec gluetun wget -qO- https://am.i.mullvad.net/json   # must show a MULLVAD ip

# 7b. build + start the app (first boot downloads JCEF ~100MB via Mullvad)
docker compose build server
docker compose up -d server
docker compose logs -f server
#   expect: "data dir: /data" → CEF download % → "CEF runtime ready" → online banner
#   ./data/bin/jcef and ./data/cache/jcef get populated

# 7c. FlareSolverr
docker compose up -d flaresolverr

# 7d. expose the UI on the tailnet (host side)
sudo tailscale serve --bg 8080
tailscale serve status
```
Then in **Settings → FlareSolverr URL = `http://localhost:8191`**.

**CHECK 7 (the real test):**
- [ ] UI loads over Tailscale from your phone.
- [ ] MangaFire: search → open → **read an undownloaded chapter** (`JCEF[mangafire.to]: 200`); captcha
      solve flow works if hit.
- [ ] A download lands in `/mnt/library`.
- [ ] `docker compose exec server wget -qO- https://am.i.mullvad.net/json` → **Mullvad IP** (egress VPN'd).
- [ ] Stop gluetun → source reqs fail but reading a **downloaded** chapter still works. Restart gluetun.

### Phase 8 — Durability
- `restart: unless-stopped` (in compose) survives reboots. Test `sudo reboot`; confirm the stack +
  `tailscale serve` return (if serve doesn't persist, add a `@reboot` root cron running `tailscale serve --bg 8080`).
- **Backups:** Proxmox VM snapshot/backup; plus periodic copy of `/mnt/library` and `~/manga/data`
  (especially `library.db` + `data/cache/jcef` cookies).
- **Update app:** `git -C src pull && docker compose build server && docker compose up -d server`. JCEF
  native persists (no re-download). To bump Chromium: change `JCEF_VERSION`/`JBR_RELEASE` in code +
  `rm -rf data/bin/jcef` so it re-fetches.

### Phase 9 — VM firewall (optional hardening)
```bash
sudo ufw default deny incoming
sudo ufw allow in on tailscale0
sudo ufw allow from <interior-LAN-cidr> to any port 22
sudo ufw --force enable
```
(gluetun's 8080 is loopback-only, so it's already not LAN-exposed.)

---

## 14. Failure playbook (when a CHECK fails)
- **CEF never "runtime ready" / crashes at boot** (the §3c risk) → `docker compose logs server` for
  GTK/`HeadlessException`/X errors. Confirm `Xvfb` started (`DISPLAY=:99`), `MU_JCEF_NO_SANDBOX=1` is set,
  and no apt lib is missing (`ldd` the `libcef.so` in the container to find the gap).
- **First-boot download hangs/fails** → gluetun not healthy yet, or GitHub rate-limited via Mullvad.
  Test `docker compose exec server wget -qO- https://api.github.com`; if blocked on that exit IP, switch
  Mullvad city or stand up the native mirror (TODO 3a).
- **UI not reachable on tailnet** → `tailscale serve status`; confirm gluetun published `127.0.0.1:8080`
  and the app listens (`docker compose exec server wget -qO- localhost:8080`).
- **Source reqs fail but VPN up** → FlareSolverr URL = `http://localhost:8191`; MangaFire should use JCEF,
  not FlareSolverr; check the API circuit breaker isn't stuck open.
- **Stray `jcef_helper` / "stuck on initializing" on restart** → signal didn't reach the JVM. Confirm tini
  is PID 1 and `entrypoint.sh` uses `exec`. `docker compose exec server ps -ef` → java should be a direct
  child of tini, no orphaned helpers after stop.
