/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import java.util.concurrent.ConcurrentHashMap

/**
 * Transient per-source reachability. Updated on every browse/search/details: a non-Cloudflare
 * failure (timeout, 5xx, connection reset, extension init error) marks the source down; any success
 * marks it up. In-memory only (a fresh process re-learns), unlike [CloudflareState] which is sticky.
 */
object SourceHealth {
    private val down = ConcurrentHashMap.newKeySet<Long>()

    fun isDown(sourceId: Long): Boolean = down.contains(sourceId)
    fun markDown(sourceId: Long) { down.add(sourceId) }
    fun markUp(sourceId: Long) { down.remove(sourceId) }

    // Image-serving health, tracked separately from browse/API health: a source's API can be fine
    // while its image CDN 5xx's (the atsu outage). Fed by actual page fetches in the reader.
    private val imagesDown = ConcurrentHashMap.newKeySet<Long>()
    fun areImagesDown(sourceId: Long): Boolean = imagesDown.contains(sourceId)
    fun markImagesDown(sourceId: Long) { imagesDown.add(sourceId) }
    fun markImagesUp(sourceId: Long) { imagesDown.remove(sourceId) }
}
