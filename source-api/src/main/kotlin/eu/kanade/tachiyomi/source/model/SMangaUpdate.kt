package eu.kanade.tachiyomi.source.model

/**
 * extensions-lib 1.6 replaced the separate `getMangaDetails` + `getChapterList` calls with a single
 * `getMangaUpdate(...)` that returns both at once (KeiSource-based sources like MangaFire implement only
 * this; the old parse methods `throw UnsupportedOperationException`). This is that combined result.
 */
class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
)
