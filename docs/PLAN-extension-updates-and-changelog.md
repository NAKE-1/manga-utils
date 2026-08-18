# Plan — Extension updates: the live-update fix + a "what's new" changelog

Two separate things, written up together because they both live on the Extensions screen and both
came out of the atsumaru `q` incident (2026-08-18). **Nothing here is implemented yet** — this is for you
to read through and poke holes in. Part A is a bug fix; Part B is a new feature.

---

## Part A — Durable fix for "updating a live extension throws until restart"

### What broke (the atsumaru `q` error)

Symptom: right after updating extensions, a source call failed with
`NoClassDefFoundError: eu/kanade/tachiyomi/extension/en/atsumaru/q`.

`q` is a **lazily-loaded Kotlin coroutine class** (`extends kotlin.coroutines.jvm.internal.ContinuationImpl`).
Coroutine continuations are pulled from the jar the **first time a suspend function runs**, not when the
source is instantiated. The jar itself is fine — intact zip, `q.class` present, ordinary class, no ext-lib
gap.

### Why it regressed (it genuinely didn't used to do this)

Two changes stack up:

1. **Commit `5952452` "allow updating a loaded extension (release the jar lock)"** added `close()` to
   `ExtensionLoader.releaseJar()`. That was deliberate — before it, an open `URLClassLoader` kept the jar
   **locked on Windows**, so an "update" silently sat on the old code (the "three-week-old jar" bug the
   installer's own comment calls out). Closing the loader made updates actually apply.
2. **`ExtensionInstaller.download()`** writes the new jar with `dest.outputStream()` — an **in-place
   truncate-and-rewrite of the same file**, not a temp-file swap.

Together: an update **closes the old classloader** *and* **overwrites the bytes it was still reading**. Any
source instance created *before* the update that's still live (an in-flight library check, a running
download, an open browse) can no longer load a not-yet-touched class like `q` → the error. Before `5952452`
neither happened, so live sources never noticed an update until the next restart.

### Immediate workaround (already true today)

**Restart the server.** Everything reloads from the new jars on fresh classloaders; the error is gone. It's
transient, not on-disk corruption.

### The fix (two small, surgical changes)

Goal: on **Linux (the deploy target)**, updating an extension while it's in use is seamless — no error, no
restart. On **Windows (dev)**, keep the honest "restart after updating a live extension" caveat, because
Windows file-locking makes true live replacement impossible.

1. **`ExtensionInstaller.download()` → temp file + atomic move.**
   Write to `"<jarPath>.part"`, then `Files.move(tmp, jarPath, ATOMIC_MOVE, REPLACE_EXISTING)`
   (fall back to `REPLACE_EXISTING` alone if `ATOMIC_MOVE` is unsupported).
   On POSIX this swaps the directory entry to a **new inode**: live sources keep reading the old inode until
   they finish; new loads open the new one. No truncation-under-a-reader, so no `q` error.

2. **`ExtensionLoader.releaseJar()` → only `close()` on Windows.**
   ```
   fun releaseJar(jarPath) {
     val cl = jarLoaderMap.remove(jarPath) ?: return   // evict either way → next load builds fresh
     if (isWindows) runCatching { cl.close() }         // POSIX: leave it open for live instances
   }
   ```
   POSIX: evict-without-close — the next `loadSource` builds a fresh loader on the new jar while in-flight
   instances run out on the old one (GC reclaims it when idle). Windows: still close, because the file lock
   must be broken before the move can overwrite the jar.

Why both are needed: the OS-gated close alone isn't enough while the write truncates in place (the old
inode still gets clobbered). The temp-move alone isn't enough on Windows (the move onto an open file still
fails the lock). Linux needs both to be fully live-safe; the pair also keeps Windows working exactly as now.

### Scope / risk

- **Files:** `core/.../extension/ExtensionInstaller.kt` (the `download` helper) and
  `core/.../extension/internal/ExtensionLoader.kt` (`releaseJar`). ~15 lines + an `isWindows` constant.
- **Risk:** low. The `.part` temp needs cleanup on a failed download (delete on exception) so a half-file
  can't be mistaken for a jar. The existing `check(stampOf(jarPath) != before)` guard still applies after
  the move, so a no-op update still fails loudly.
- **`installLocalJar`** already uses `Files.copy(..., REPLACE_EXISTING)` (a delete+create, new inode on
  POSIX) so it benefits from the same `releaseJar` change with no edit of its own.

### Test

1. Windows: update a loaded extension → succeeds, jar bytes change, version bumps. (Restart still needed
   only if that exact source was mid-call — unchanged from today.)
2. Linux/container: browse/download from an extension, update it **mid-use** → no `NoClassDefFoundError`,
   the in-flight op finishes, and a fresh browse uses the new code.
3. Failed download (kill mid-fetch) → no leftover `.part`, old jar intact, install fails loudly.
4. The stale-jar guard still trips if the repo serves an identical file.

---

## Part B — "What's new" changelog on the update badge

### The idea

When an extension has an update available, let me see **what changed** — the upstream commit/PR text, e.g.:

> **Atsumaru (EN): Fix thumbnail and adjustments (#18491)**
> Fix thumbnails + use Instant · Adjust filters

…shown next to (or expandable from) the "Update" button on the Extensions → Installed tab, with a link out
to the PR on GitHub.

### Where the text has to come from (the awkward part)

Our repo index is `keiyoushi/extensions` branch `repo` → `index.min.json`. That index carries **only**
name / pkg / version / code / sources / nsfw — **no changelog, no commit sha**. So the index can't give us
this; the changelog lives in a **different repo**, `keiyoushi/extensions-source`, as the squash-merge commit
titles (exactly the `Name (LANG): title (#PR)` format you quoted).

**Data source: GitHub commits API, path-filtered.**
```
GET https://api.github.com/repos/keiyoushi/extensions-source/commits?path=src/<lang>/<name>&per_page=5
```
Returns commit objects with `commit.message` (title + body) and `html_url`. Show the top 1–3.

**Mapping our extension → that path.** Our pkg is `eu.kanade.tachiyomi.extension.<lang>.<name>`, and the
source lives at `src/<lang>/<name>/`. So strip the prefix, split `<lang>.<name>`, join as `src/<lang>/<name>`.
- `…extension.en.atsumaru` → `src/en/atsumaru` ✓
- `…extension.all.mangafire` → `src/all/mangafire` ✓
- Multisrc/themed extensions (MangaThemesia/Madara families) live under generated paths and won't map
  cleanly → **fallback**: link to the repo's commit search for that name instead of inlining text.

### The real constraint: GitHub rate limits

Unauthenticated GitHub API = **60 requests/hour per IP**. With ~594 installed extensions, checking all of
them would blow the budget instantly — and on the server every call rides the **shared Mullvad exit IP**,
which may already be near GitHub's limit. So the design is built around *not* spamming it:

- **Lazy only.** Never fetch changelogs during the update *check*. Fetch **only** when I click "what's new"
  on **one** extension that already shows an update. Realistically a handful of clicks, not 594.
- **Cache hard.** Server-side cache keyed by pkg (+ the available version code) with a long TTL
  (say 24h) and persisted to disk so a restart doesn't refetch. A changelog for a given version never
  changes, so it's cache-forever-per-version.
- **Optional GitHub token.** A settings field for a personal access token lifts the limit to 5,000/hr. Off
  by default; only needed if you ever want "fetch changelogs for all updates at once."

### What it can and can't promise

- It shows the **most recent commit(s) touching that extension's path** — which in practice *is* the update,
  since Keiyoushi bumps the version per commit. Label it "recent changes," **not** "exactly your diff": we
  can't map version-code → commit sha (the index doesn't publish shas), so a precise
  installed-version→available-version range isn't reliable in v1.
- Non-Keiyoushi / third-party repos have no GitHub mapping → hide the "what's new" control for those, or
  link the repo root.

### UI placement

- Extensions → **Installed** tab. On a row that has the **Update** badge, add a small **"what's new"** link
  / chevron next to the Update button.
- Click → lazy-fetch → inline expand showing: bold title, the commit body (bulleted), date, and a
  **"view on GitHub"** link to `html_url`. Collapsed by default so the list stays clean.
- Optional later: the same block on the **Browse** tab when an install would be an upgrade.

### Backend

- New endpoint `GET /api/extensions/changelog?pkg=…` →
  1. derive `src/<lang>/<name>` from pkg,
  2. check disk/mem cache (pkg + available code); if fresh, return it,
  3. else GitHub commits API (through the normal OkHttp client → Mullvad on the server),
  4. parse `[{title, body, url, date}]`, cache, return.
- Graceful failures: rate-limited (403 + `X-RateLimit-Remaining: 0`) or path-miss → return an empty list +
  a "view on GitHub" URL so the UI degrades to a plain link instead of erroring.

### Scope / phasing

- **B1 (core):** endpoint + pkg→path mapping + lazy fetch + in-memory cache + the "what's new" expander on
  the Installed tab. This is the whole ask.
- **B2 (nice-to-have):** disk-persisted cache, optional GitHub token setting, Browse-tab upgrades.
- Estimate: B1 ≈ one endpoint (~60 lines) + a small UI expander. B2 additive.

### Edge cases to decide

- Extensions whose path doesn't map (multisrc/themed) → link-out fallback vs hide entirely.
- Whether to show 1 commit (just "the update") or the last 3 (a mini history).
- Token: worth a settings field now, or leave it lazy-only and skip the token until 60/hr actually bites?

---

## Open questions for you (answer whichever, then I'll turn this into code)

**Part A**
1. Implement the two-part fix now, or leave restart-after-update since the box restarts anyway? (I lean:
   implement — it's cheap and kills a real regression on the 24/7 box.)

**Part B**
2. **One commit** ("what changed in this update") or **last ~3** (a short per-extension history)?
3. Path-miss fallback: a **"view on GitHub" link** for extensions we can't map, or **hide** the control?
4. Add the **GitHub token** setting in v1, or ship lazy-only (60/hr) and add a token later if needed?
5. Placement: **Installed tab only** for v1, or also on **Browse** when an install is really an upgrade?
