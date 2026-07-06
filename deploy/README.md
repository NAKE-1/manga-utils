# Dockerizing & publishing manga-utils

Everything here is self-contained — it **builds the existing code, doesn't modify it**. The image
runs the `:server` web UI (phone/Tailscale reader) with a sibling FlareSolverr container.

Files:
- `Dockerfile` — multi-stage build (Gradle → slim JRE + Chromium libs for JCEF WebView sources).
- `Dockerfile.dockerignore` — keeps the build context small.
- `docker-compose.yml` — manga-utils + flaresolverr, one `/data` volume.
- `github-workflow-docker-publish.yml` — optional CI to auto-publish to ghcr.io.

---

## 1. Build & run locally (test it first)

From the **repo root** (the build context must be the root so `gradlew` + all modules are visible):

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

First build takes a while (downloads Gradle, JVM deps, and npm packages, then compiles). When it's
up:

- Open `http://localhost:8080` (or `http://<host-ip>:8080` from another device on the tailnet).
- Go to **Settings → FlareSolverr URL** and set it to `http://flaresolverr:8191`. That's the sibling
  container; the setting persists in the `mu-data` volume, so it's a one-time step.
- Install a source or two under **Extensions**, then search. The first WebView source will spend a
  moment downloading the JCEF/Chromium native into `/data/bin/jcef` (persisted, so only once).

Useful commands:
```bash
docker compose -f deploy/docker-compose.yml logs -f manga-utils   # watch logs
docker compose -f deploy/docker-compose.yml down                  # stop (keeps the volume/data)
docker compose -f deploy/docker-compose.yml up -d --build         # rebuild after code changes
```

Your data lives in the named volume `mu-data` — `docker volume inspect manga-utils_mu-data` shows
where. `down` keeps it; `down -v` **deletes** it.

---

## 2. Publish the image (so installing = `docker compose up`)

Two ways. Pick one.

### Option A — one-off manual push (fastest to try)

Uses GitHub Container Registry (ghcr.io). Replace `OWNER` with your GitHub username (lowercase).

```bash
# 1) Create a Personal Access Token (classic) with the `write:packages` scope:
#    GitHub → Settings → Developer settings → Tokens (classic). Copy it.

# 2) Log in (paste the token when prompted for a password):
echo "$GHCR_TOKEN" | docker login ghcr.io -u OWNER --password-stdin

# 3) Build for the registry (from repo root) and push:
docker build -f deploy/Dockerfile -t ghcr.io/OWNER/manga-utils:latest .
docker push ghcr.io/OWNER/manga-utils:latest
```

Then on the Proxmox box (or anywhere with Docker), people install with just:

```bash
# Edit docker-compose.yml: set image: ghcr.io/OWNER/manga-utils:latest and comment out the build: block
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
```

Make the package public once (ghcr.io → your package → Package settings → Change visibility) so
others don't need to log in to pull.

### Option B — automate with GitHub Actions (recommended once it works)

```bash
mkdir -p .github/workflows
cp deploy/github-workflow-docker-publish.yml .github/workflows/docker-publish.yml
git add .github/workflows/docker-publish.yml && git commit -m "ci: publish docker image" && git push
```

Now every push to `main` publishes `:main`, and tagging a release publishes a version + `:latest`:

```bash
git tag v0.1.0 && git push --tags
```

The image lands at `ghcr.io/<owner>/<repo>`. No secrets needed — Actions' `GITHUB_TOKEN` handles
auth (just confirm **Settings → Actions → Workflow permissions = Read and write**).

---

## 3. Notes / gotchas

- **Architecture:** the Dockerfile builds for the host's architecture. Proxmox is x86-64, so build
  on an x86-64 machine (or add `platforms: linux/amd64` + QEMU in CI for cross-build).
- **FlareSolverr URL:** defaults to `localhost:8191` in the app; inside compose it must be
  `http://flaresolverr:8191`. (A future `MU_FLARESOLVERR_URL` env override would remove this manual
  step — small change in `:core`, deferred since we're not touching existing files here.)
- **Memory:** both containers run headless Chromium. `shm_size: 1gb` each; add
  `mem_limit: 1500m` under a service if you want a hard cap. On native Docker (Proxmox) memory is
  reclaimed normally — none of the Windows/WSL `vmmemWSL` weirdness.
- **First WebView hit** downloads ~100–150 MB of JCEF native into `/data` once; keep the volume.
