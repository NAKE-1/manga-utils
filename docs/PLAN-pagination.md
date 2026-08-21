# Plan — Pagination for Library, Continue reading & Home (faster loads)

Goal: cut load time on Library, Continue-reading and Home, with a dev setting for **entries per page**
(a `< N >` stepper in the app's style) plus on-screen paging. **Not implemented yet** — read + question first.

## Measured reality (this is what shapes the plan — don't skip)

I measured the live endpoints rather than guessing:

| Endpoint | Payload | Time | Feeds | Where the cost actually is |
|---|---|---|---|---|
| `GET /api/library` | **96 KB** (261 entries) | 42 ms | Library, Home grid | **Client render** of 261 cover cards + 261 image requests. The wire is cheap. |
| `GET /api/history` | **1.5 MB** | 41 ms | Continue-reading, Home carousel | **The 1.5 MB download itself**, re-fetched on every Home/Continue load. |

Key correction: the 10 MB is `library.json` *on disk* (it stores `knownChapters`); the **API already sends
trimmed DTOs** (96 KB). So "server-side pagination for Library" would add a lot of complexity to shrink a
payload that's already small. The real levers are different per screen:

- **History (Home + Continue):** the 1.5 MB download is the cost → **server-side** limiting/pagination is the
  genuine win. Home only needs the recent handful, yet pulls all 1.5 MB today.
- **Library:** the wire is 96 KB; the cost is rendering 261 cards + firing 261 image loads → **client-side
  grid pagination** (render only the current page → only that page's images load) fixes it, and keeps the
  instant client-side sort/filter/search that Library relies on today.

So the honest architecture is a **hybrid**, driven by where each screen's time actually goes — not
server-side everywhere. Both screens still get the same `< N >` pager UI and honor the same dev page-size
setting, so it *feels* uniform.

## The dev setting + pager UI

- **Dev setting: "Entries per page"** — a `−  N  +` stepper (same component as the Parallel-downloads
  stepper), in the Developer-tools/Settings dev section. Default e.g. **60**.
- **Storage:** a `localStorage` dev flag (`dev.pageSize`), matching the existing `dev.continueRemove`
  pattern — it's a device-local dev knob, no server round-trip, no settings-schema change. (Alternative:
  a real server setting if you want it shared across devices — say so and I'll do that instead.)
- **On-screen pager:** a small reusable `<Pager>` — `‹  page N of M  ›` in the app style (accent chevrons,
  disabled at ends). Reused by Library and Continue.

## Part 1 — History: Home + Continue (server-side, the big win)

**API:** `GET /api/history?offset=<n>&limit=<n>` → `{ items: HistoryItem[], total: number }`, **deduped by
manga server-side** and ordered most-recent-first. Dedup must move server-side: it's currently done in the
client after downloading everything, and you can't paginate correctly if page 1 might be five reads of the
same manga. No-arg call stays backward compatible (returns all) so nothing else breaks.

- **Home** (`Home.tsx`): fetch just the recent slice for the carousel (e.g. `limit = pageSize`) instead of
  the full 1.5 MB. This single change is the biggest load-time win on the app's landing screen. A carousel
  doesn't page, so Home just *limits* — no pager needed there.
- **Continue-reading page** (`ListPage.tsx`, `kind==='continue'`): real paging with the `<Pager>` over
  `offset/limit`, `total` from the response.

## Part 0 — Quick win: stop the Library view blocking on 1.5 MB of history (a real bug)

`ListPage.tsx` gates `ready` behind `Promise.all([api.library(), api.history()])` for **every** kind — so
the Library and Updates views won't paint until the **1.5 MB `/api/history`** finishes, even though only the
Continue view uses history. On a phone over Tailscale that's the reported **~4 s** to "view all" despite
`/api/library` being 40 ms.

**Fix:** fetch history only when `kind==='continue'`; don't gate `ready` on it otherwise. Library paints as
soon as its 96 KB arrives. This is a ~3-line change, independent of the rest of the plan, and kills most of
the 4 s on its own. Do it first.

## Part 2 — Library: client-side grid pagination

96 KB is already cheap, so keep the single fetch and **paginate the rendered grid**:
- After the existing client-side search/sort/filter produces the list, slice it to the current page
  (`pageSize`) and render only that slice → only that page's cover images load.
- `<Pager>` under the grid. Changing search/sort/filter resets to page 1.
- Zero API/server change; preserves the instant client-side toolbar Library has now.

(If you'd rather Library also be server-side, it's doable but means moving search+sort+filter to the server —
a much bigger change for a 96 KB payload. I don't recommend it; noting it so the choice is explicit.)

## Scope / files

- **Server (`Main.kt`):** add `offset`/`limit` + server-side dedup to `/api/history`; return `{items,total}`.
  `/api/library` untouched.
- **Client:**
  - `api.ts`: `history(offset?, limit?)` → `{items,total}` (keep old callers working).
  - new `components/Pager.tsx` (+ a few CSS rules).
  - `Home.tsx`: limited history fetch.
  - `ListPage.tsx`: Continue (server pager) + Library (client grid pager).
  - dev page-size stepper in the Settings/Dev screen + a `dev.pageSize` helper.
- Estimate: ~1 small server change + ~1 component + edits to 3 screens. Medium, not large.

## Verification

1. Home loads without pulling 1.5 MB of history (network tab shows a small history request); carousel still
   shows most-recent-per-manga.
2. Continue page: pager walks pages, dedup correct across pages, `total` right.
3. Library: grid shows `pageSize` per page; changing sort/filter/search resets to page 1; only the current
   page's images load (network tab).
4. Dev "Entries per page" stepper changes all three; persists across reload.
5. Backward-compat: nothing that called `/api/history` with no args broke.

## Open questions

1. **Page-size storage:** `localStorage` dev flag (recommended, matches existing dev flags) or a real
   server setting shared across devices?
2. **Library:** client-side grid pagination (recommended — fixes the real cost, keeps instant sort/filter)
   or force it server-side too (bigger, not worth it for 96 KB)?
3. **Home carousel:** just *limit* the fetch (recommended — no pager on a carousel), or actually page it?
4. **Pager style:** numbered `‹ page N of M ›`, or infinite/"Load more" button? (I assumed a numbered pager.)
