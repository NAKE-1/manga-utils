/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.util

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeFileTest {
    private val parse: (String) -> String? = { if (it.startsWith("ok:")) it else null }

    @Test
    fun writeThenReadRoundTrips() {
        val dir = Files.createTempDirectory("safefile")
        val f = dir.resolve("data.json")
        SafeFile.writeAtomic(f, "ok:one")
        assertEquals("ok:one", SafeFile.read(f, parse))
    }

    @Test
    fun secondWriteRotatesPreviousToOld() {
        val dir = Files.createTempDirectory("safefile")
        val f = dir.resolve("data.json")
        SafeFile.writeAtomic(f, "ok:one")
        SafeFile.writeAtomic(f, "ok:two")
        assertEquals("ok:two", f.readText())
        assertEquals("ok:one", dir.resolve("data.json.old").readText()) // previous good kept one deep
        assertTrue(!Files.exists(dir.resolve("data.json.tmp")))          // temp cleaned up by the move
    }

    @Test
    fun corruptLiveFileFallsBackToOld() {
        val dir = Files.createTempDirectory("safefile")
        val f = dir.resolve("data.json")
        SafeFile.writeAtomic(f, "ok:one")
        SafeFile.writeAtomic(f, "ok:two")     // now .old holds the prior good copy "ok:one"
        f.writeText("<<garbage, mid-write kill>>") // live file unparseable
        assertEquals("ok:one", SafeFile.read(f, parse)) // recovered from .old
    }

    @Test
    fun bothBadReturnsNull() {
        val dir = Files.createTempDirectory("safefile")
        val f = dir.resolve("data.json")
        f.writeText("garbage")
        dir.resolve("data.json.old").writeText("also garbage")
        assertNull(SafeFile.read(f, parse))
    }
}
