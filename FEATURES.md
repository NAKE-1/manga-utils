# manga-utils — Feature backlog & ideas

Pick by number — tell me e.g. "do 3, 14, 27" to implement, or "look into 31" to investigate first.

**Legend:** 🔸 queued (already discussed) · 💡 proposed (new idea) · ✅ done ·
Effort: **S** ≈ <1h · **M** ≈ 1–3h · **L** ≈ half-day+ · ⛰️ big/ops.
"needs atsu" = can't be judged until the source is up · "needs deploy" = for the eventual server host.

---

## A. Reader
1. 💡 **Resume mid-chapter** — remember scroll position per chapter so reopening drops you where you left off, not the top. **M**
2. 💡 **Keep screen awake while reading** (Wake Lock API) so the phone doesn't sleep mid-chapter. **S**
3. 💡 **Brightness / dim overlay** slider in the reader chrome (night reading). **S**
4. 💡 **Go-to-page** — tap the progress pill to jump to a page number. **S**
5. 💡 **Pinch-to-zoom / double-tap zoom** on a page. **M**
6. 💡 **Reading-direction support** (LTR / RTL) for non-webtoon manga, in reader settings. **M**
7. ✅ **Auto-scroll-to-current** in the chapter list + restyled to match the detail list (name·meta, CH. groups, read markers).
8. 💡 **"Retry all failed" button** in the reader trouble banner (reload every failed page at once). **S**
9. 💡 **Volume-key / tap-zone paging** as an *option* (still long-strip by default). **M**

## B. Library & Home
10. 💡 **Library categories / collections** (Reading, Plan-to-read, Done…) with tabs. **M**
11. ✅ **Sort & filter the library** (A–Z / recently updated / unread first / latest chapter · filter all/new/downloaded).
12. ✅ **Search within your library** (live title filter on the Library page).
13. 💡 **Unread-count badges** on library covers. **S**
14. 💡 **Bulk actions** — long-press to select, then mark-read / download / remove several at once. **M**
15. 🔸 **Continue-reading polish** — per-item remove already exists; add "clear all" + reorder by most-recent. **S**

## C. Downloads & Offline
16. ✅ **"Download whole manga"** — already implemented as "download missing" on the detail screen.
17. 💡 **Server-side page cache** — pages you've already streamed get kept on disk, so re-reads survive source outages (like today's atsu 502s) and load instantly. **M**
18. 💡 **Auto-download on add-to-library** (optional toggle). **S**
19. 🔸 **Deep-verify downloads** — check each downloaded chapter's page count vs the source, flag short/corrupt ones (complements Repair). **M** · needs atsu
20. 🔸 **Download-manager polish** — sort by size, "delete all partials", total disk-usage bar. **M**
21. ✅ **Storage overview** — total size / series / chapters / largest series on the Download manager.
22. 💡 **Offline indicator** — when a source is down, surface a hint pointing to what you *have* downloaded. **S**

## D. Search & Browse
23. 🔸 **Global search across all sources** — one query, per-source result rows, "has results" filter. (Partly exists — verify/finish.) **M** · needs atsu
24. 💡 **Recent searches / search history.** **S**
25. 💡 **Genre / tag browse** — tap a genre to see more like it. **M**

## E. Sources & Extensions
26. 💡 **In-app extension manager** — install / update / remove source extensions from Settings (no manual jar drops). **L**
27. 💡 **Per-source enable/disable** so Browse only lists the ones you use. **S**
28. 💡 **Source migration** — move a library entry from a dead/changed source to another. **L**

## F. Speed & Resilience
29. 🔸 **Downscaling / "Data Saver"** — optional server-side resize of pages to screen width (~1290px, high quality), originals kept for downloads. The real fix for slow remote reads over the capped Tailscale relay. **M** · needs atsu
30. 🔸 **Library-polling throttle** — Home hits `/api/library` ~30×/10s; debounce / refetch-on-focus only. **S** (low priority — you're not hitting it)
31. 💡 **Adaptive image concurrency** — cap simultaneous fetches so the visible page isn't starved on weak links (tune per measured speed). **M** · needs atsu
32. ✅ **Source health check** — Settings → Developer live panel: green ok / orange images-down / red unreachable; tracks image health separately from API (catches the atsu case).

## G. Trackers & Sync
33. 💡 **AniList / MyAnimeList tracking** — link an account, auto-update progress as you read. (Original roadmap priority.) **L**
34. 🔸 **Backup & restore** — export/import library + history + settings as a file. *(later)* **M**
35. 💡 **Reading stats** — chapters read, streak, per-series progress. **M**

## H. Notifications & Updates
36. 🔸 **Scheduled updates + auto-download** — built; still want a **push/web notification** on new chapters. **M**
36b. ✅ **Global toasts** — bottom-left, for download-complete / failure / "all downloads complete" + errors.
37. 🔸 **"Updates" digest** — a screen listing everything new since last visit, grouped by manga. *(maybe)* **S**

## I. UX & Polish
38. 🔸 **Installable PWA** — add-to-homescreen, app icon, offline app shell. *(queued)* **M**
39. 🔸 **Pull-to-refresh** on Home / Library / detail. *(queued)* **S**
40. 💡 **Theme options** — accent color picker, optional light theme. **S**
41. 🔸 **Loading skeletons** instead of bare spinners. *(discuss / later)* **S**
42. 💡 **Haptic feedback** on key taps (mobile). **S**
43. 🔸 **JVM-warning silence** — add `--enable-native-access=ALL-UNNAMED` so Java 25 stops printing the harmless red startup warnings. **S**

## J. Dev & Insight
44. 🔸 **Startup warm-up** — ✅ done for downloads; could also warm covers/library. **S**
45. 🔸 **Download / traffic graph** — dated bytes-downloaded / images-served chart in Settings. **M**
46. 🔸 **Server-status polish** — peak RSS, GC count/pauses, RAM/CPU sparkline on the dev card. **M**
47. 🔸 **File logging** — rolling `latest.log` + dated files (server has a logback.xml now; just add a file appender). **S**
48. ✅ **Client↔host speed test** — done (Settings → Developer).
49. ✅ **In-app version + commit + changelog + tech stack** — done (Settings → About).

## K. Ops & Deploy (far later)
50. 🔸 **Pretty URLs** — `/manga/<slug>/chapter-12` instead of opaque IDs (shareable/bookmarkable). **M**
51. 🔸 **Dockerize the server** for the Proxmox host. **L** · needs deploy
52. 🔸 **FlareSolverr sidecar** — sibling container for Cloudflare-protected sources, via container-to-container networking. **L** · needs deploy
53. 🔸 **Network storage for downloads** on the Proxmox/TrueNAS setup. **M** · needs deploy

---

### My suggested "do these next" (while atsu's flaky)
- **17** (page cache — outage resilience + speed, and it *stacks* with downscaling)
- **16** (download whole manga — makes offline actually convenient)
- **43** (JVM-warning silence — 5 min, fully clean console)
- **1** (resume mid-chapter — big everyday quality-of-life)
- Then **29** (downscaling) once atsu is stable enough to A/B.
