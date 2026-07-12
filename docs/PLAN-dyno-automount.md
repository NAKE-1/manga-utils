# Implementation plan — DYNO host auto-mount + app integration

**Status:** planned, not started. Logged 2026-07-12. Complements `dyno md/DYNO-IMPLEMENTATION-SPEC.md`
(which covers the cartridge/export/sync side). This doc is specifically the **host-side auto-mount +
hash-gated trust + the in-app DYNO dev panel & toasts**. Nothing here is implemented until picked.

## The idea (as described)

- A watcher runs on the Debian/Proxmox host. When a USB is inserted that carries a **special marker file
  whose hash matches our format**, the host **auto-mounts** it and exposes it to the Debian dir that runs
  the docker app.
- The app raises a **toast** ("DYNO *name* inserted") and shows **live status + stats + action buttons**
  (verify files, usage stats, eject, …) in a **DYNO dev panel**.
- Testing happens on a weak Debian box first (not final hardware) — the plan must be runnable there.

---

## 1. Topology — how the USB reaches the containerized app

The app runs in a Docker container; the USB is a host device. The chain:

```
USB stick ─▶ host udev event ─▶ verify marker (HMAC) ─▶ mount ro to /srv/dyno/<name>
                                                              │  (host path)
                                              bind-mount ─────┘
                                                              ▼
                                              container sees it at /dyno/<name>
                                                              ▼
                                     app polls /dyno ─▶ toast + dev panel + stats
```

- **Test box (now):** single Debian host running Docker. udev on Debian handles detect→verify→mount into
  `/srv/dyno`; the container gets `-v /srv/dyno:/dyno:ro` (or a shared mount). The app polls `/dyno`.
- **Final (Proxmox):** either (a) **USB passthrough** of the port to the Debian VM — then it's identical
  to the test box, or (b) the Proxmox host mounts and re-shares to the VM. Design for (a) — it generalises,
  and the whole watcher lives *inside* the Debian box either way. Nothing app-side changes between test
  and final.

**Why poll the mount dir instead of a host→app push:** it's decoupled and container-friendly. The host
watcher's only job is *mount/unmount + verify*; the app *discovers* cartridges by scanning `/dyno`. No
privileged socket, no host reaching into the container. Robust to the app or host restarting independently.

---

## 2. Cartridge identity & the "special hash" format

A DYNO cartridge is any drive whose **root** contains two files:

- **`dyno.json`** — the manifest (identity + contents summary):
  ```json
  {
    "dyno": 1,
    "id": "b3f1c2e4-8a1d-5f2c-9e77-2c1a4d9b6f30",   // UUIDv5, stable per cartridge
    "name": "nakano-main",
    "created": "2026-07-12T18:22:04Z",
    "updated": "2026-07-12T18:40:11Z",
    "host": "manga-utils",
    "counts": { "series": 128, "chapters": 3417, "bytes": 41231233123 }
  }
  ```
- **`dyno.sig`** — one line: `hmac-sha256=<hex>` over the **exact bytes** of `dyno.json`.

**Trust rule:** the host holds a secret at `/etc/dyno/secret` (32 random bytes). A cartridge is *trusted*
(auto-mounted) iff `HMAC_SHA256(secret, bytes(dyno.json)) == dyno.sig`. A random or malicious USB can't
forge the signature without the secret, so it's ignored. This is "the specific hash format": an
**HMAC-signed manifest**.

- The **same secret** is bind-mounted read-only into the app container so the app can *sign* cartridges it
  creates (write `dyno.json`, then `dyno.sig`). Secret is generated once: `head -c 32 /dev/urandom | xxd -p -c 64 > /etc/dyno/secret`.
- **id** = `UUIDv5(DYNO_NAMESPACE, created + random-nonce)` — stable across re-mounts, unique per cartridge
  (aligns with the UUIDv5 identity already noted for DYNO).
- **Test-phase fallback:** before the secret flow is wired, allow a `DYNO_INSECURE=1` mode that trusts any
  drive with a well-formed `dyno.json` (no sig check) — for the weak test box only. Off by default.

Optional integrity layer (used by *Verify files*, not by mount-trust): a `dyno.contents.json` listing
`{ path, size, sha256 }` per file, so the app can verify the cartridge wasn't corrupted.

---

## 3. Host side — udev + watcher (examples)

### udev rule — `/etc/udev/rules.d/99-dyno.rules`
Trigger a systemd unit (don't do slow work in udev's RUN):
```
ACTION=="add",    SUBSYSTEM=="block", ENV{DEVTYPE}=="partition", ENV{ID_FS_USAGE}=="filesystem", \
  TAG+="systemd", ENV{SYSTEMD_WANTS}="dyno-check@%k.service"
ACTION=="remove", SUBSYSTEM=="block", ENV{DEVTYPE}=="partition", \
  TAG+="systemd", ENV{SYSTEMD_WANTS}="dyno-unmount@%k.service"
```

### systemd template — `/etc/systemd/system/dyno-check@.service`
```
[Unit]
Description=DYNO cartridge check for %i
[Service]
Type=oneshot
ExecStart=/usr/local/bin/dyno-check /dev/%i
```

### watcher — `/usr/local/bin/dyno-check` (skeleton)
```bash
#!/usr/bin/env bash
set -euo pipefail
DEV="$1"; SECRET=/etc/dyno/secret; BASE=/srv/dyno; TMP=$(mktemp -d)
trap 'umount "$TMP" 2>/dev/null || true; rmdir "$TMP" 2>/dev/null || true' EXIT

mount -o ro,nosuid,nodev,noexec "$DEV" "$TMP" || exit 0     # never exec from the stick
[ -f "$TMP/dyno.json" ] && [ -f "$TMP/dyno.sig" ] || exit 0 # not a DYNO drive → ignore quietly

want=$(sed 's/^hmac-sha256=//' "$TMP/dyno.sig")
have=$(openssl dgst -sha256 -hmac "$(cat "$SECRET")" -hex "$TMP/dyno.json" | awk '{print $2}')
[ "$want" = "$have" ] || { logger "dyno: bad signature on $DEV"; exit 0 }

name=$(jq -r '.name' "$TMP/dyno.json"); dest="$BASE/$name"
umount "$TMP"; mkdir -p "$dest"
mount -o ro,nosuid,nodev,noexec "$DEV" "$dest"             # ro by default; sync remounts rw explicitly
echo "$DEV" > "$dest/.dyno-dev"                            # remember the device for unmount/eject
logger "dyno: mounted $name at $dest"
```

### unmount — `/usr/local/bin/dyno-unmount` (on device remove, and on eject request)
Unmount `/srv/dyno/<name>` for the removed device; clean the dir. A tiny timer/inotify also watches for
`/srv/dyno/*/.eject-request` (written by the app) → `sync && umount` → safe eject.

Notes: `jq`, `openssl`, and `exfat` support (`exfatprogs`) on the host. exFAT is the recommended cartridge
FS (cross-platform, large files). Everything is **read-only + noexec** until a sync explicitly needs rw.

---

## 4. App side — discovery, endpoints, dev panel, toasts

### Discovery (`DynoWatcher`, server)
- Env `MU_DYNO_MOUNT` (default `/dyno`). A daemon scans `MU_DYNO_MOUNT/*/dyno.json` every ~4s.
- Diff against known set → **appeared** / **disappeared**. On change, reads each manifest for stats.
- Emits events the UI consumes (same pattern as the FlareSolverr event stream): appeared → toast
  "DYNO *name* inserted"; disappeared → "DYNO *name* removed".

### Endpoints
- `GET /api/dyno/cartridges` → `[{ id, name, mountPath, sigValid, ro, capacityBytes, usedBytes,
  counts:{series,chapters,bytes}, created, updated, lastSeenMs }]`
- `POST /api/dyno/verify?id=` → start a background checksum of the cartridge against `dyno.contents.json`;
  `GET /api/dyno/verify/progress?id=` → `{done,total,bad:[paths],running}`.
- `GET /api/dyno/usage?id=` → space + size breakdown (per-series bytes, largest, free space).
- `POST /api/dyno/eject?id=` → drop `/dyno/<name>/.eject-request` (host unmounts) + optimistic UI removal.
- `GET /api/dyno/events` → SSE/long-poll for the toasts (or fold into the existing event stream).

### DYNO dev panel (Settings → Developer → DYNO)
- **Inserted cartridges** list: name, id (short), sig ✓/✗, ro/rw, `used / capacity` bar, counts, last-seen.
- Per cartridge buttons: **Verify files** (progress bar, lists any bad files), **Usage stats** (expand:
  free space, per-series size, largest), **Eject** (safe), **Re-scan**.
- **Dev status block:** mount path, mount state, signature status, watcher last-tick, `MU_DYNO_MOUNT`,
  insecure-mode flag — the "dev status and other shit" for debugging on the test box.
- A toast on insert/remove (reuse the Toast bus).

### Example — app discovery loop (pseudocode)
```kotlin
object DynoWatcher {
  private val seen = ConcurrentHashMap<String, Cartridge>()  // id -> cartridge
  fun tick() {
    val now = scan(AppConfig.dynoMount)                       // read */dyno.json
    (now.keys - seen.keys).forEach { toast("DYNO ${now[it]!!.name} inserted", "success") }
    (seen.keys - now.keys).forEach { toast("DYNO ${seen[it]!!.name} removed") }
    seen.clear(); seen.putAll(now)
  }
}
```

---

## 5. Testing on the weak Debian box (step-by-step)

1. **Host prep:** `apt install jq openssl exfatprogs`; generate the secret; drop the udev rule + the two
   systemd units + the two scripts; `udevadm control --reload`.
2. **Make a test cartridge:** extend `dyno md/test suite/generate_fixture.py` to also (a) format a USB as
   exFAT, (b) write a small library + a few chapters, (c) write `dyno.json`, (d) write `dyno.sig` using the
   host secret. (A `--insecure` variant skips the sig for the very first smoke test.)
3. **Run the container** with `-v /srv/dyno:/dyno:ro -v /etc/dyno/secret:/etc/dyno/secret:ro -e MU_DYNO_MOUNT=/dyno`.
4. **Insert the stick** → within seconds: `journalctl -u 'dyno-check@*'` shows "mounted nakano-main"; the
   app toasts "DYNO nakano-main inserted"; the dev panel lists it with sig ✓.
5. **Verify files** → progress runs, reports 0 bad on a clean cartridge; corrupt a file → it's flagged.
6. **Usage stats** → space + counts match the fixture.
7. **Eject** in the app → host unmounts; app toasts "removed"; pulling the stick physically also toasts
   "removed" (udev remove path).
8. **Negative test:** insert a plain USB (no `dyno.json`) → nothing happens, no mount, no toast. Insert one
   with a **wrong signature** → host logs "bad signature", no mount.

Because the test box isn't final hardware, keep everything **env/path-driven** (`MU_DYNO_MOUNT`,
`/srv/dyno`, secret path) so moving to real hardware is just re-pointing paths — no code change.

---

## 6. Security & robustness

- **noexec/nosuid/nodev + read-only** mounts; never autorun anything from the stick. Only `dyno.json` is
  read to decide trust.
- **HMAC gate** stops rogue USBs from auto-mounting. Insecure mode is opt-in and test-only.
- **Unclean removal:** udev `remove` → host unmount; app poll detects the gone mount → toast + clean state.
  No stale entries.
- **Read-only until sync:** writes (sync to/from cartridge) remount rw explicitly and briefly; default ro
  protects the cartridge from accidental corruption.
- **Don't block on a slow/full stick:** verify/usage run in the background with progress, never on the
  request thread.

---

## 7. Build phases

| Phase | What | Where | Size |
|-------|------|-------|------|
| 0 | Cartridge format finalised: `dyno.json` + `dyno.sig` (HMAC) + optional `dyno.contents.json` | spec | S |
| 1 | Host: udev rule + systemd units + `dyno-check`/`dyno-unmount` scripts (+ insecure test mode) | Debian box | M |
| 2 | Test-cartridge tool (extend `generate_fixture.py`) that writes a signed cartridge | tools | S–M |
| 3 | App `DynoWatcher` + `/api/dyno/*` endpoints + toasts | :server | M |
| 4 | DYNO dev panel (list, verify, usage, eject, dev status) | webui | M |
| 5 | Safe eject via request-file + host timer/inotify | Debian box | S |
| 6 | Sync (cartridge ↔ library) | per DYNO main spec | L |

Do 0→1→2 on the test box to prove auto-mount + trust, then 3→4 for the in-app experience, then 5, then 6
(the big sync, which is the main DYNO spec's job). Everything path/env-driven so the weak test box → final
hardware move is config-only.

---

## 8. DYNO Maker — cross-platform Python app (+ .bat launcher)

**Goal:** a small Python app you can run on Windows / Linux / mac to **turn a USB into a DYNO cartridge** —
generate the format, name it, sign it, (optionally) load content — without touching the server. Windows
gets a double-click `.bat`; Linux/mac a `.sh`. GUI (Tkinter) + a CLI for scripting. Modelled on the
existing `tools/backup-inspector/` (Tkinter + `.bat`) and `dyno md/test suite/generate_fixture.py`.

**Location:** `tools/dyno-maker/` — `dyno_maker.py` (core + CLI), `dyno_maker_gui.py` (Tkinter),
`Make DYNO USB.bat` (Windows), `make-dyno-usb.sh` (Linux/mac), `README.md`.

**What it does**
1. **Pick a drive** (lists removable volumes; on Windows drive letters, on Linux `/dev/sd*` + mountpoints).
2. **Name** the cartridge; **generate a random UUID** (identity, `UUIDv5` — see §9 for the code variant).
3. Write **`dyno.json`** (manifest) and **`dyno.sig`** (HMAC using the pairing-code key from §9).
4. Optionally **format** as exFAT first (with a big confirm — this wipes the drive), or just write the
   marker onto an existing drive.
5. Optionally **copy content** (a metadata export + downloads) into the cartridge — or leave that to the
   server's DYNO sync later; the maker's minimum job is *identity + format + signature*.
6. **Verify** an existing cartridge (recompute the sig, check `dyno.contents.json`).
7. **Manage the pairing code** locally (enter once, cached in a config file so you don't retype).

**CLI example**
```
# first time: set the pairing code (cached in ~/.dyno/maker.cfg)
python dyno_maker.py pair --code "DYNO-K7QF-9XM2-4TZP"

# make a cartridge on E:  (Windows)  /  /media/usb (Linux)
python dyno_maker.py make --drive E: --name nakano-main
#  -> writes E:\dyno.json, E:\dyno.sig   (signed with the code-derived key)

python dyno_maker.py verify --drive E:
#  -> signature OK · nakano-main · id b3f1c2e4… · 0 bad files
```

**GUI:** one window — drive dropdown, name field, "pairing code" field (with a *Generate* button), and
**Make cartridge** / **Verify** / **Eject** buttons + a log pane. Same look/feel as the backup-inspector.

---

## 9. Pairing without copying a giant secret — code-derived key

The problem: §2 uses a 64-char host secret; copying that to the Windows maker is annoying. Fix: derive the
signing key from a short **pairing code** typed on *both* sides.

**Scheme (recommended):**
- **Pairing code**: short + human-typable, e.g. `DYNO-K7QF-9XM2-4TZP` (3 groups of 4 Crockford-base32 ≈ 60
  bits) or a 4-word passphrase. Generated once by the maker (or you pick it).
- **KDF**: `key = PBKDF2-HMAC-SHA256(code, salt="dyno-v1", iters=200000, dkLen=32)`. PBKDF2 is in both
  Python (`hashlib.pbkdf2_hmac`) and the JVM (`javax.crypto`), so maker and app agree. The **short code
  never becomes the key directly** — the KDF makes brute-forcing a stolen cartridge expensive.
- **Both sides run the KDF once, then store the 32-byte key** — no KDF per insert:
  - **Maker**: `pair --code …` derives the key, caches it in `~/.dyno/maker.cfg`.
  - **Host**: `dyno-enroll --code …` (a tiny Python/host tool) derives the same key, writes it to
    `/etc/dyno/secret`. The bash watcher just HMACs with that stored key (no KDF in bash needed).
- Result: **you type the same short code twice — once in the maker, once on the host — and every cartridge
  you make auto-mounts.** No giant string, no per-drive step.

**"Generate a UUID from a code" variant (your idea):** if you'd rather the *identity itself* come from the
code, set `id = UUIDv5(DYNO_NAMESPACE, code + ":" + name)` — then the host can compute the expected UUID
for a given code without the drive present. Useful, but the **key**-from-code above is what actually gates
trust; the UUID is just the label. We can do both: code → key (for the signature) **and** code → UUID (for
a recognisable, reproducible id).

**Rotating:** change the code → re-`pair` on the maker + re-`enroll` on the host → old cartridges stop
auto-mounting (they'd fall to the "allow & remember" path in §10). Good for revocation.

---

## 10. "Allow & remember" — trust-on-first-use, the easy/insecure path

For when you don't want to bother with codes (testing, or an ad-hoc drive): the host **remembers drives you
explicitly authorize**, so it's a one-time click per cartridge — not blind trust of every USB.

**How it works**
- Host keeps an allowlist: `/etc/dyno/authorized.json` → `{ "<uuid>": { name, addedAt } }`.
- `dyno-check` trust order: (1) **signature valid** (§9) → mount; else (2) uuid **in allowlist** → mount;
  else (3) **pending** — write `/srv/dyno-pending/<uuid>.json` (name, id, first-seen) and **do not mount**.
- The app polls the pending dir → **toast "Authorize DYNO 'nakano-main'? (id b3f1c2e4)"** + a card in the
  DYNO dev panel with **Allow** / **Deny**.
- **Allow** → app appends the uuid to `authorized.json` (via a host-writable shared path or a small
  host-side `dyno-authorize` helper the app signals) → next insert (or an immediate re-check) auto-mounts.
- **Deny** → uuid added to a denylist so it stops prompting.
- Enable via `DYNO_ALLOW_ENROLL=1`; strict mode (signature only) is the default for real hardware.

So the two paths coexist:
- **Paired (code):** your own cartridges auto-mount, zero prompts — best for daily use.
- **Allow & remember (TOFU):** authorize a drive once with a click — best for testing on the weak box or a
  cartridge made elsewhere. "Remembers drives with correct dyno.json formatting," exactly as described.

**App endpoints for this:** `GET /api/dyno/pending`, `POST /api/dyno/authorize?id=`,
`POST /api/dyno/deny?id=` (all reflected in the dev panel).

---

## 11. Updated build phases

| Phase | What | Where | Size |
|-------|------|-------|------|
| 0 | Cartridge format + **pairing scheme** (dyno.json, dyno.sig, PBKDF2 code→key, UUIDv5) | spec | S |
| 1 | **DYNO Maker** Python app (CLI + Tkinter GUI + .bat/.sh) — make/sign/verify | tools/dyno-maker | M |
| 2 | Host: udev + systemd + `dyno-check`/`dyno-unmount` + `dyno-enroll` (code→/etc/dyno/secret) | Debian box | M |
| 3 | Host: **allow & remember** — allowlist + pending dir + `dyno-authorize` helper | Debian box | S–M |
| 4 | App `DynoWatcher` + `/api/dyno/*` (cartridges, pending, authorize, verify, usage, eject) + toasts | :server | M |
| 5 | DYNO dev panel (list, authorize/deny, verify, usage, eject, dev status) | webui | M |
| 6 | Safe eject via request-file + host timer/inotify | Debian box | S |
| 7 | Sync (cartridge ↔ library) | per DYNO main spec | L |

Order to prove it out on the weak box: **0 → 1 (maker) → 2 (auto-mount + enroll)** gets a signed cartridge
auto-mounting; **3** adds click-to-authorize; **4 → 5** bring the in-app toasts + dev panel; **6** safe
eject; **7** the real sync. All path/env-driven so moving off the test box is config-only.
