# Deploy cheatsheet

One command per box. Copy-paste. Run `git pull` first (baked in below).

## Main (the server VM @ 100.67.109.13)

```bash
cd ~/manga-src && git pull && \
docker build -f deploy/Dockerfile -t manga-utils:local . && \
docker compose -f ~/manga/docker-compose.yml up -d --force-recreate manga-utils
```

Three steps on purpose: pull the code, rebuild the `manga-utils:local` image from it, then
recreate ONLY the `manga-utils` service — leaving flaresolverr/solver/browser/autoheal running.

Gotchas that bit us:
- The live compose file is `~/manga/docker-compose.yml` (project `manga`, data volume
  `manga_mu-data`), NOT the repo's `deploy/docker-compose.yml`. Using the repo file makes a
  separate project (`deploy`) with an empty `deploy_mu-data` volume and collides on the hardcoded
  `container_name`s. Always use `~/manga/docker-compose.yml`.
- `--force-recreate manga-utils` (not plain `up -d --build`) so the sidecars aren't recreated —
  that's what avoids the "container name /browser already in use" conflict.

## Node-1 (Orange Pi worker)

Two `-f` flags so the burn-drive override loads (it's a Pi-local file, not in the repo):

```bash
cd ~/manga-src && git pull && docker compose \
  -f deploy/worker/docker-compose.yml \
  -f deploy/worker/docker-compose.override.yml up -d --build < /dev/null
```

Verify the burn-drive bind survived:
```bash
docker volume inspect worker_mu-worker-data --format '{{json .Options}}'   # must show device:/mnt/ssd/manga-worker
```

## Node-3 (Lenovo M720q, Proxmox — worker LXCs 301 + 302)

From the **Proxmox host** root shell (handles git pull + rebuild in every CT):

```bash
/root/mu-lxc-fleet.sh update        # both CTs
/root/mu-lxc-fleet.sh update 301    # just one
/root/mu-lxc-fleet.sh ps            # status after
```

---

Note: the scan-stall fixes (`76f5e06`, `2965bea`) are **main-side only** — deploying main is
what matters. Updating workers keeps them in git-sync but doesn't change the worker binary's behaviour.
