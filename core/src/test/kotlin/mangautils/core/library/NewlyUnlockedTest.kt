package mangautils.core.library

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers LibraryService.computeNewlyUnlocked: detecting a locked (future-dated) chapter that has unlocked. */
class NewlyUnlockedTest {
    private val lastCheck = 1_000L
    private val now = 2_000L
    private fun ref(url: String, date: Long) = ChapterRef(url, url, 1f, null, date)

    @Test fun catchesChapterThatUnlockedSinceLastCheck() {
        // date 1500 was in the future at lastCheck(1000) and is now past (now=2000) -> unlocked.
        val prev = listOf(ref("c1", 1500))
        val cur = listOf(ref("c1", 1500))
        assertEquals(listOf("c1"), LibraryService.computeNewlyUnlocked(prev, cur, lastCheck, now).map { it.url })
    }

    @Test fun ignoresChapterStillLocked() {
        // date 3000 is still in the future (> now) -> not yet unlocked.
        val prev = listOf(ref("c1", 3000))
        val cur = listOf(ref("c1", 3000))
        assertEquals(emptyList(), LibraryService.computeNewlyUnlocked(prev, cur, lastCheck, now))
    }

    @Test fun ignoresAlreadyFreeChapter() {
        // date 500 was already past at lastCheck(1000) -> was free before, not newly unlocked.
        val prev = listOf(ref("c1", 500))
        val cur = listOf(ref("c1", 500))
        assertEquals(emptyList(), LibraryService.computeNewlyUnlocked(prev, cur, lastCheck, now))
    }

    @Test fun ignoresBrandNewChapterNotSeenBefore() {
        // c2 wasn't in prev -> it's a new chapter (handled elsewhere), not a newly-unlocked one.
        val prev = listOf(ref("c1", 500))
        val cur = listOf(ref("c1", 500), ref("c2", 1500))
        assertEquals(emptyList(), LibraryService.computeNewlyUnlocked(prev, cur, lastCheck, now))
    }

    @Test fun emptyOnFirstEverCheck() {
        val cur = listOf(ref("c1", 1500))
        assertEquals(emptyList(), LibraryService.computeNewlyUnlocked(emptyList(), cur, 0, now))
    }
}
