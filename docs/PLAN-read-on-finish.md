# PLAN — "read on finish", not "read on open"

Brainstorm / design doc. **No code yet.** Goal: stop treating *opening* a chapter as
*reading* it. A chapter should count as read when you actually finish it (reach the end
or advance forward), not the instant it mounts.

---

## 1. How it works today (verified in code)

Three independent server stores back reading state:

| Store | File | Shape | Meaning |
|---|---|---|---|
| `ReadStore` | `read.json` | `manga → Set<chapterUrl>` | boolean "read" per chapter |
| `PositionStore` | `positions.json` | `manga → chapter → fraction 0..1` | mid-chapter scroll resume point |
| `HistoryStore` | `history.json` | recent entries w/ chapter + cover | "Continue reading" carousel |

### The trigger that causes the complaint
`Reader.tsx:303`, inside the chapter-mount effect:

```ts
api.setRead(sourceId, manga, chapter, true)   // fires UNCONDITIONALLY on open
```

So **the moment ch.20 mounts, it's marked read.** Nothing about finishing it is checked.

### How that turns into "it jumps me to ch.21"
- **Detail "Resume" / the Read button** (`Detail.tsx:222 openContinue`, `:309 resumeUrl`):
  target = `readingOrder(chapters).find(c => !read(c)) ?? last`. First *unread* ascending.
  Once ch.20 is flagged read, first-unread = ch.21 → Resume/▶ opens ch.21.
- **Reader chapter list** shows a ✓ on ch.20 immediately (`Reader.tsx:398,415`).
- **Home "Continue reading" card** is driven by *history* (`recordHistory`, fires on open too),
  so the card still appears — it just points at the series, and tapping through runs
  `openContinue`, which now lands on ch.21.

### The position store already knows what "finished" means
`PositionStore.set` (`PositionStore.kt:72-73`):
```
<=2% = "hasn't started", >=98% = "finished" → both drop the resume point (open at top).
```
The reader saves progress on scroll (`Reader.tsx:439`) and on leave (`:311`). So the server
**already** distinguishes "peeked" (a stored mid fraction) from "finished" (no fraction, hit ≥98%).
We're just not using that signal to drive the read flag — `setRead(true)` on open overrides it.

### Prev/next buttons
`Reader.tsx:612/614` → `openChapter(prevCh|nextCh)`; that only navigates. The read-marking is a
side effect of the *destination* chapter mounting, not of the button. Neither button marks the
chapter you're *leaving*.

---

## 2. What "correct" should mean

A chapter is **read** when either:
- **(a) you reached the end** — progress ≥ ~95–98% (the same "finished" signal positions already use), **or**
- **(b) you pressed Forward** (▶ next) — an explicit "I'm done with this one."

Opening, peeking, scrolling partway, then leaving (or hitting Back) = **not read**, resume point kept,
so Continue/Resume returns you to *that* chapter where you left off.

---

## 3. Options considered

### Option A — mark read on finish (RECOMMENDED)
Delete the on-open `setRead(true)`. Add two triggers:
1. progress crosses ~0.97 → `setRead(current, true)` once (guarded by `settledFor === chapter`,
   same guard the preloader uses so the first 500 ms of stale progress can't fire it);
2. Forward button → `setRead(current, true)` before navigating.

- **Pros:** matches the mental model exactly; read flag, resume point and history finally agree;
  tiny diff (one deletion + ~2 small additions); reuses the existing 0.98 "finished" concept.
- **Cons / must-handle:**
  - **Last/newest chapter has no Forward** → can only be marked via trigger (a). Fine as long as (a)
    exists — this is *why* pure "forward only" (Option B) is rejected.
  - Reading to ~95% and bailing 30 px short of the bottom → stays unread. Acceptable per the intent
    ("didn't finish"); tune the threshold (0.95 vs 0.98) if it feels too strict.

### Option B — forward-button ONLY (the literal request)
Mark read *only* on Forward. **Rejected:** the newest chapter (no next button) could never be marked
read; reaching the end and exiting wouldn't count either. Collapses back into needing trigger (a) anyway.

### Option C — progress threshold only (no button)
Mark read purely at ≥90–95%, drop the button trigger. Clean single rule, device-agnostic.
Slightly worse UX: tapping Forward through a short chapter you skimmed wouldn't mark it read until
you'd scrolled it. Option A = C plus the explicit forward signal; keep the signal.

### Option D — don't touch read-marking; make Resume prefer an in-progress chapter
`resumeUrl = chapterWithSavedPosition ?? firstUnread`. Smallest change, but ch.20 shows as **read ✓
AND "RESUME"** at once — muddy, and breaks the unread filter / "mark all read" semantics. Rejected.

**→ Go with Option A.**

---

## 4. Exact touch points (for when we build)

- `Reader.tsx:303` — **delete** the unconditional `api.setRead(..., true)`.
  Keep `recordHistory` (line 305) so the Continue card still shows after a peek.
- `Reader.tsx` mark-on-finish effect — new, small: when `settledFor === chapter && progress >= 0.97`
  and not already marked, call `api.setRead(sourceId, manga, chapter, true)` once and update
  `readUrls` locally so the ✓ appears live.
- `Reader.tsx:614` Forward button — mark current read, then `openChapter(nextCh)`.
  (Leave Prev button as-is; going back must not mark read.)
- No backend changes. `ReadStore.setRead` / `/api/read` already exist and are idempotent.

**Nothing else needs to change** — Detail resume, Home carousel, unread filter, notifications,
bulk mark-read, cross-device sync all read from `ReadStore` and Just Work once the flag is set at
the right time.

---

## 5. Effects on every surface

| Surface | Before | After |
|---|---|---|
| Reader ✓ on current chapter | on open | on finish |
| Detail ▶ / "RESUME" marker | next chapter | the in-progress chapter |
| Home "Continue reading" | appears (history) | unchanged — still appears |
| `positions.json` resume point | already kept for peeks | now actually honored (not shadowed by read flag) |
| Unread tab / "mark all read" | unaffected | unaffected |
| New-chapter / library update badges | unaffected | unaffected |

---

## 6. Edge cases & risks

1. **Newest chapter, no Forward** → relies on the ≥97% trigger. Verify the reader can actually scroll
   to ≥0.97 (bottom chrome padding). `onScroll` computes `p = y/(scrollHeight-innerHeight)` → ~1.0 at
   true bottom, so fine.
2. **Fast skim to bottom** → marks read. Acceptable (all pages mounted, you saw the end).
3. **Broken/blank chapter** you can't scroll → never auto-marks. These are gated anyway; manual
   "mark read" still available.
4. **Jump around via chapter list** (open ch.50 directly) → not marked on open; marked only if you
   finish it. Matches intent.
5. **Behavior change for existing muscle memory** — some people "mark read by opening." Mitigation:
   a Reader setting *"Mark chapter read: when opened / when finished"*, default **when finished**.
   Ship behind the hidden dev toggle first to A/B it before making it the default.
6. **Very short / single-tall-image chapters** — width-scaled long strip can be short in scroll terms;
   ≥97% still reachable. No special case needed.
7. **Double-fire guard** — the progress effect runs on every scroll; guard with a ref
   (`markedRead.current === chapter`) so we call `setRead` once per chapter, not per frame.

---

## 7. Rollout

1. Dev-toggle (`localStorage app.readOnFinish`) wrapping the new triggers; old path when off.
2. Live-test: peek a chapter → leave → Continue returns to it; finish → Continue advances; newest
   chapter marks read at the bottom; Prev never marks.
3. Promote to a real Reader setting, default **on finish**.
4. Remove the dev toggle once confident.

Smallest correct change is Option A; everything downstream already keys off `ReadStore`, so this is
a reader-timing fix, not a data-model change.
