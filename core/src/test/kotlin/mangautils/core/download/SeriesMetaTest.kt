/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesMetaTest {
    @Test
    fun writeThenReadRoundTrips() {
        val dir = Files.createTempDirectory("series")
        val meta = SeriesMeta(
            sourceId = 6084907896154116083L, sourceName = "MangaFire",
            mangaUrl = "/title/nrn8m-the-rewards-of-marriage", title = "The Rewards of Marriage",
            author = "Choam, Marisea", status = 1, genre = "Manhwa, Comedy", savedAt = 123L,
        )
        SeriesMeta.write(dir, meta)
        assertEquals(meta, SeriesMeta.read(dir))
        assertTrue(Files.exists(dir.resolve(SeriesMeta.FILE)))
        assertTrue(!Files.exists(dir.resolve(SeriesMeta.FILE + ".tmp"))) // temp cleaned up by the atomic move
    }

    @Test
    fun rewriteReplacesInPlace() {
        val dir = Files.createTempDirectory("series")
        SeriesMeta.write(dir, SeriesMeta(sourceId = 1, mangaUrl = "/a", title = "A"))
        SeriesMeta.write(dir, SeriesMeta(sourceId = 2, sourceName = "Src2", mangaUrl = "/b", title = "B"))
        val m = SeriesMeta.read(dir)!!
        assertEquals(2L, m.sourceId)
        assertEquals("B", m.title)
    }

    @Test
    fun missingReturnsNull() {
        val dir = Files.createTempDirectory("series")
        assertNull(SeriesMeta.read(dir))
    }
}
