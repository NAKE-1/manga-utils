# Manga-Utils — Design & Feature Catalog

> A reboot / "best-of" manga reader + downloader, taking the strongest ideas from
> **Suwayomi-Server**, **Mihon (Tachiyomi)**, and **HakuNeko**.
>
> **Status:** Research / brainstorming only. **Nothing is implemented yet.**
> This document is a menu. Read it, then pick the features and the stack you want,
> and we'll turn the selections into a real implementation plan (`/plan`).

---

## 1. What each reference project actually is

Understanding the three lets us cherry-pick the right ideas instead of cloning one.

### Suwayomi-Server
- **What:** A self-hosted **server** that runs Tachiyomi/Mihon extensions headlessly. Client-server model — server holds the library, downloads, and talks to sources; many front-ends (bundled WebUI, desktop, third-party apps) connect to it.
- **Stack:** Kotlin/Java on the JVM (JRE 21+), **Javalin** web framework, **GraphQL** API, Gradle build. Optional Electron bundle for a desktop wrapper. Supports SQLite by default and **PostgreSQL** for larger setups.
- **Key ideas worth stealing:**
  - Headless server you can run on a NAS/Raspberry Pi/Docker, reach from any device.
  - **Reuses the entire Tachiyomi extension ecosystem** (1000s of sources) instead of writing scrapers from scratch.
  - GraphQL API as the single contract between server and any client.
  - **OPDS / OPDS-PSE** endpoint so generic ebook/comic readers can connect.
  - Tachiyomi-compatible **backup/restore** and tracker sync.
  - FlareSolverr integration for Cloudflare-protected sources.

### Mihon (Tachiyomi)
- **What:** The reference **Android app**. Polished mobile reader + library manager. The whole extension/source ecosystem originates here.
- **Stack:** 100% Kotlin, Android 8+, cleanly modularized (`core`, `domain`, `data`, `presentation`, `source-api`, `local`, etc.). Apache-2.0.
- **Key ideas worth stealing:**
  - The **Source API contract** — the single most valuable design artifact across all three (see §3).
  - **Reader UX**: multiple view modes (paged L→R, R→L, vertical, webtoon/continuous), zoom, crop borders, dual-page, color filters, reading direction per-manga.
  - **Library**: categories, per-category update schedules, filters/sorts, unread badges, "update only on Wi-Fi/charging."
  - **Tracking**: MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi — two-way progress sync.
  - **Backup**: protobuf-based, restorable, cloud-storable.
  - Clean module boundaries (domain/data/presentation) — a great architecture template.

### HakuNeko
- **What:** A cross-platform **desktop downloader**. Focus on grabbing chapters for offline reading, not a long-lived library/server.
- **Stack:** Originally JS/Electron; v6 rewrite uses **TypeScript + Web Components + Electron**. ~1200+ site **connectors**. Unlicense.
- **Key ideas worth stealing:**
  - **Connector model** — each site is a self-contained scraper module (often declarative/scriptable), easy to add.
  - **Download queue** with concurrency control.
  - **Output formats**: raw images, **CBZ**, PDF, ebook — with post-processing scripts.
  - "Ad-hoc consumption" philosophy: simple browse → click → download → read offline.

### The synthesis
| Concern | Best reference |
|---|---|
| Self-hosted server + multi-client | Suwayomi |
| Source/extension contract | Mihon `source-api` |
| Reader & library UX | Mihon |
| Download queue + export formats (CBZ/PDF) | HakuNeko |
| Tracking & backup | Mihon / Suwayomi |
| Easy-to-write connectors | HakuNeko |

---

## 2. Product shapes — pick ONE primary direction

These are mutually-exclusive *architectures*. Everything else in this doc plugs into whichever you pick.

### Option A — Self-hosted server + web UI (Suwayomi-style)
A backend daemon (library, downloader, sources) + a browser front-end; reachable from phone/desktop/TV.
- **Pros:** One library everywhere, runs on a home server, no per-device setup, easy to add a mobile client later.
- **Cons:** Most infrastructure; need auth, persistence, deployment story.
- **Best if:** you want "Plex/Jellyfin for manga."

### Option B — Desktop app (HakuNeko-style)
Single installable app (Electron/Tauri) that browses, downloads, and reads locally.
- **Pros:** Simplest to ship, no server, native file access for CBZ/PDF export.
- **Cons:** Library lives on one machine; sync is manual.
- **Best if:** your priority is downloading + offline reading on a PC.

### Option C — CLI / library toolkit ("manga-utils" literal)
A scriptable core (search, download, convert, organize) usable from a terminal and importable as a library; GUI optional later.
- **Pros:** Fastest to a useful MVP, automatable (cron grabs), great foundation other UIs reuse.
- **Cons:** Not consumer-friendly on its own.
- **Best if:** you want a strong engine first, UI later.

### Option D — Hybrid: headless core + thin clients (recommended)
Build a reusable **core engine** (sources, download queue, storage, conversion) with no UI. Then wrap it in:
- a CLI (Option C) as the first deliverable,
- an HTTP/GraphQL server (Option A) next,
- a desktop or web reader on top.

This is the most future-proof: the engine is the asset; every UI is replaceable. **Most of this doc assumes Option D's layered model.**

---

## 3. The Source/Connector system (the heart of the project)

Everything depends on how you talk to manga sites. Three strategies — they can coexist.

### Strategy 1 — Reuse Tachiyomi/Mihon extensions (Suwayomi approach)
- Load existing extension **APKs** (1000s of sources, actively maintained by Keiyoushi & others).
- **Requires a JVM** and an Android-API compatibility shim (this is most of what Suwayomi-Server *is*).
- **Pro:** Instant huge catalog, someone else maintains the scrapers.
- **Con:** Locks you to the JVM/Kotlin and to mimicking Android APIs; heavy.

### Strategy 2 — Native connector model (HakuNeko approach)
- Write your own scraper modules in your chosen language.
- Each connector implements a small interface (see contract below).
- **Pro:** Full control, any language, lightweight.
- **Con:** You maintain every scraper; sites change and break.

### Strategy 3 — Declarative source definitions
- Sources described in data (YAML/JSON: base URL, CSS/XPath selectors, pagination rules) + a generic HTML/JSON engine.
- **Pro:** Non-programmers can add sources; sources are reviewable diffs.
- **Con:** Can't express every weird site; needs a scripting escape hatch.

### The Source contract (distilled from Mihon `source-api`)
Whatever strategy, the interface our engine codes against should be roughly:

```
Source:
  id: stable unique id
  name, lang
  supportsLatest: bool

  getPopular(page)            -> MangaPage      # browse popular
  getLatest(page)             -> MangaPage      # recently updated
  search(page, query, filters)-> MangaPage      # filtered search
  getMangaDetails(manga)      -> Manga          # cover, description, status, authors, genres
  getChapterList(manga)       -> [Chapter]
  getPageList(chapter)        -> [Page]         # image URLs / page descriptors
  getImage(page)              -> bytes/stream

  getFilterList()             -> FilterList      # genre, status, sort options the UI renders
```

Core data models:
- **Manga**: url, title, cover, author, artist, description, genres, status (ongoing/completed/...), source id.
- **Chapter**: url, name, number, volume, date, scanlator.
- **Page**: index, image url (or a two-step "fetch image url" for lazy sources).
- **MangaPage**: list of manga + `hasNextPage`.
- **FilterList**: source-defined filters (genre checkboxes, sort, status) the UI renders generically.

> Decision needed: **Strategy 1 (JVM + reuse) vs 2/3 (native, own connectors).** This single choice drives the language and most of the stack.

---

## 4. Feature catalog (pick what you want)

### 4.1 Library management
- [x ] Add/remove manga to a personal library
- [ ] **Categories** (custom groups; a manga can be in multiple)
- [ ] Per-category sort & filter (unread, downloaded, source, status, alphabetical, last-read, total chapters)
- [ x] Unread/badge counts, "continue reading"
- [x ] Duplicate detection across sources
- [ x] Bulk actions (move, delete, mark read, download)
- [x ] Manga metadata editing / custom cover

### 4.2 Sources & discovery
- [x ] Browse popular / latest per source
- [x ] Global search across all enabled sources at once
- [x ] Source-defined filters (genre, status, sort)
- [x ] Source repository / extension installer & updater
- [x ] Per-source settings (language, login, base-URL override)
- [ x] NSFW source toggle / content filtering
- [ x] Migration: move a manga's tracking/progress from one source to another

### 4.3 Reader (if building a reader UI)
- [x ] View modes: paged L→R, paged R→L, vertical paged, **webtoon/continuous vertical**
- [x ] Zoom / pan / double-tap zoom, fit-width / fit-height / smart-fit
- [x ] **Dual-page** (2-up) for paged mode, with cover handling
- [x ] Crop borders / split wide pages
- [x ] Color filters, brightness, grayscale, custom background
- [x ] Per-manga reading-direction override
- [x ] Page preloading, prefetch next chapter
- [x ] Progress tracking, resume, "mark previous read"
- [x ] Keyboard / volume-key / gesture navigation
- [x ] Long-strip pre-render for webtoons

### 4.4 Downloader (HakuNeko strength)
- [x ] Download queue with **configurable concurrency** + rate limiting
- [x ] Pause/resume/retry, error backoff
- [x ] Download whole manga / range / "all unread" / "next N"
- [x ] **Auto-download new chapters** on library update
- [ x] Storage management: see size, delete by manga/chapter, "keep last N read"
- [x ] Download-only-on-Wi-Fi / scheduled windows
- [x ] Integrity check / re-download corrupt pages

### 4.5 Output / export formats (HakuNeko strength)
- [x ] Raw image folders (per chapter)
- [x ] **CBZ** (zip) — the de-facto comic standard
- [x ] CBR, PDF, EPUB
- [x ] Configurable folder/file naming templates (`{series}/{volume}/{chapter} - {title}`)
- [ x] **ComicInfo.xml** metadata (Komga/Kavita/CBR-reader compatible)
- [x ] Image post-processing: re-encode, resize, webp→jpg, strip, upscale hook

### 4.6 Library updates
- [x ] Manual "update library" + scheduled updates (interval, time-of-day)
- [x ] Per-category update schedules
- [x ] Smart update (skip completed, skip recently checked)
- [x ] Update only on Wi-Fi / charging / metered-off
- [x ] New-chapter notifications

### 4.7 Tracking integrations
- [ ] **MyAnimeList**, **AniList**, **Kitsu**, **MangaUpdates**, Shikimori, Bangumi
- [x ] Two-way progress sync (read chapter → bump tracker, and vice versa)
- [x ] Auto-track on add, set status/score from app
- [ ] OAuth flows + token storage

### 4.8 Backup, sync & interop
- [x ] Full backup (library, categories, tracking, history, settings)
- [ x] **Tachiyomi/Mihon backup compatibility** (protobuf `.tachibk`) for migration
- [ x] Restore + merge
- [x ] **OPDS / OPDS-PSE** server endpoint (read from any OPDS client)
- [x ] Komga/Kavita import/export friendliness (via ComicInfo.xml + folder layout)

### 4.9 Server / multi-client (Option A/D only)
- [x ] **GraphQL** and/or REST API
- [ x] Auth (single-user token, or multi-user accounts)
- [ x] WebSocket/subscriptions for live download/update progress
- [ ] Docker image + compose, ARM support (NAS/Pi)
- [ ] FlareSolverr / proxy support for Cloudflare
- [ ] Per-source rate-limit & retry config

### 4.10 Quality-of-life / nice-to-haves
- [x ] Reading history & statistics
- [x ] Recommendations ("readers also read", from tracker metadata)
- [x ] Themes (light/dark/AMOLED, custom accent)
- [ x] Multi-language UI (i18n)
- [x ] Incognito / private reading mode
- [ x] Local-files source (read your own CBZ/folder library, Mihon "local source" style)
- [x ] Plugin/extension API for community add-ons

---

## 5. Framework & stack options

The Source strategy (§3) constrains this most. Below are coherent stacks per language.

### If reusing Tachiyomi extensions (Strategy 1) → JVM is mandatory
| Layer | Choice |
|---|---|
| Language | **Kotlin** (JVM 21) |
| Server framework | Javalin (like Suwayomi), Ktor, or Spring Boot |
| API | GraphQL (graphql-java / graphql-kotlin) or REST |
| DB / ORM | SQLite/Postgres via **Exposed** or jOOQ |
| Build | Gradle (Kotlin DSL) |
| Desktop wrap | Compose Multiplatform or Electron/Tauri shell |
| HTTP scraping | OkHttp + Jsoup (what extensions already use) |

### If native connectors, **TypeScript** stack (HakuNeko-like, web-friendly)
| Layer | Choice |
|---|---|
| Language | **TypeScript** (Node 20+) |
| Core/CLI | Node + `commander`/`yargs`, `undici`/`got` for HTTP |
| Scraping | `cheerio` (HTML), `playwright`/`puppeteer` (JS-heavy sites, Cloudflare) |
| Server | Fastify or Hono + GraphQL (Pothos/Mercurius) |
| DB / ORM | SQLite via **Prisma** or **Drizzle** |
| Desktop | **Tauri** (light) or Electron |
| Web UI | React/Next, Svelte/SvelteKit, or Vue |
| Export | `archiver` (CBZ), `pdfkit`/`pdf-lib` (PDF), `sharp` (image processing) |

### If native connectors, **Python** stack (fastest to a CLI/toolkit)
| Layer | Choice |
|---|---|
| Language | **Python 3.12** |
| CLI | Typer / Click + Rich |
| Scraping | httpx + selectolax/BeautifulSoup; Playwright for JS sites |
| Server | FastAPI (+ Strawberry GraphQL) |
| DB / ORM | SQLite via SQLModel/SQLAlchemy |
| Async queue | asyncio + aiojobs, or Celery/RQ for heavy jobs |
| Export | `zipfile` (CBZ), `img2pdf`/`reportlab` (PDF), Pillow (image processing) |
| Desktop | optional: PyWebView / Tauri-sidecar / Qt |

### If native connectors, **Rust/Go** stack (performance, single binary)
- **Rust:** axum/actix server, reqwest + scraper, sqlx + SQLite, async-graphql, Tauri front-end. Single static binary, great for self-host.
- **Go:** chi/echo server, colly/goquery scraping, sqlc + SQLite. Trivial cross-compilation, tiny Docker images.
- **Pro:** fast, easy distribution. **Con:** scraper ecosystem less mature than JS/Python; more code.

### Recommendation
- **Want the giant existing source catalog with least scraper maintenance →** Kotlin/JVM (Suwayomi path).
- **Want full control, modern web stack, easiest custom UI →** **TypeScript** (best all-rounder for Option D).
- **Want the quickest powerful CLI engine →** Python.
- **Want a tiny self-hosted binary →** Rust/Go.

> Decision needed: **language/stack** + **server vs desktop vs CLI** (§2) + **source strategy** (§3).

---

## 6. Proposed layered architecture (Option D)

```
┌─────────────────────────────────────────────────────────┐
│  Clients:  CLI  │  Web UI  │  Desktop  │  OPDS/3rd-party  │
└───────────────┬───────────────┬─────────────────────────┘
                │  GraphQL/REST + subscriptions
┌───────────────▼─────────────────────────────────────────┐
│  API layer (server)  — auth, schema, progress events     │
├──────────────────────────────────────────────────────────┤
│  CORE ENGINE (no UI):                                     │
│   • Source manager (load/enable connectors, §3 contract) │
│   • Library service (manga, categories, history)         │
│   • Download manager (queue, concurrency, retry)         │
│   • Converter (CBZ/PDF/EPUB, ComicInfo.xml, naming)      │
│   • Updater/scheduler (new-chapter checks)               │
│   • Tracker sync (MAL/AniList/…)                         │
│   • Backup/restore (+ Tachiyomi compat)                  │
├──────────────────────────────────────────────────────────┤
│  Storage:  DB (SQLite/Postgres)  │  File store (covers,  │
│            metadata, settings    │  downloads, exports)   │
└──────────────────────────────────────────────────────────┘
```

Module breakdown (mirrors Mihon's clean split):
- `core` — shared types, the Source contract, utilities.
- `sources` — connectors / extension loader.
- `data` — DB models, repositories, migrations.
- `domain` — library/download/track business logic.
- `download` — queue + converter.
- `server` — API (optional, Option A/D).
- `cli` — terminal front-end.
- `webui` / `desktop` — later.

---

## 7. Suggested MVP & phasing (for when we plan)

A possible "base first" sequence — refine during `/plan`:

1. **Phase 0 — Skeleton:** repo, language/stack, module layout, Source contract as code (no real source yet), DB schema for manga/chapter/page.
2. **Phase 1 — One source + fetch:** implement 1–2 connectors; search → details → chapter list → page list → fetch image. Prove the contract.
3. **Phase 2 — Download + export:** download queue with concurrency; write CBZ + raw folders + ComicInfo.xml; naming templates.
4. **Phase 3 — Library + persistence:** add to library, categories, history, settings in DB. CLI to drive it all (MVP usable here).
5. **Phase 4 — Updates & scheduling:** check for new chapters, auto-download, notifications.
6. **Phase 5 — API server:** GraphQL/REST over the engine; progress subscriptions.
7. **Phase 6 — UI:** web and/or desktop reader (view modes, progress).
8. **Phase 7 — Tracking, backup, OPDS, more sources.**

---

## 8. Legal / ethical notes (worth deciding up front)
- These tools **don't host content**; they access third-party sites. Keep that model — ship **no** scrapers/sources bundled if you want to mirror the upstream projects' stance (Mihon/Suwayomi ship the *app*, sources are separate community repos).
- Respect `robots.txt`/rate limits to avoid hammering sites (the download-manager rate limiting in §4.4/§4.9).
- Consider a **first-class "local files" source** so the app is fully useful with content the user already owns.
- License choice for your own code (Apache-2.0 like Mihon, MPL, MIT, Unlicense like HakuNeko…).

---

## 9. Open decisions to resolve before `/plan`
1. **Product shape** (§2): server / desktop / CLI / hybrid?
2. **Source strategy** (§3): reuse Tachiyomi extensions (JVM) vs native connectors vs declarative?
3. **Language & stack** (§5).
4. **Reader in scope?** (Are we building a reading UI, or download/library only?)
5. **Export formats** that matter most (CBZ? PDF? EPUB?).
6. **Tracking + backup** in v1 or later?
7. **Single-user or multi-user?** (affects auth/DB).
8. **Target platforms** (Windows-only? Docker/NAS? cross-platform desktop?).

---

### How to use this doc
Mark the checkboxes / circle the options you want in §2, §3, §4, §5, §9.
Then run your slash **/plan** and we'll convert your selections into a concrete,
phased implementation plan with file structure, schemas, and tasks.
