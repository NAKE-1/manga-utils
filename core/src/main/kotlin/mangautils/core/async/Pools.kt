/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.async

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Isolated dispatcher slices so one slow concern can't starve the others.
 *
 * Before this, *every* blocking call — source browse/search/details, image serving, and download
 * page-fetches — shared the single ~64-thread [Dispatchers.IO]. A burst of hanging source calls (a
 * failing source, or a global search fanning out to every installed source) could pin the threads
 * the reader/library/downloads also needed, so the whole app went sluggish until those calls timed
 * out ~20s later.
 *
 * Each slice below is a [limitedParallelism] view of [Dispatchers.IO]: it caps its own concurrency
 * and is isolated from the other slices, so a stall in one can't drain the others. Sizes are set at
 * or above real demand so nothing legitimate gets throttled:
 *  - source: a global search hits ~12 installed sources at once → 16 leaves headroom.
 *  - image:  reader page loads must stay snappy while sources hang / a download runs.
 *  - download: parallelDownloads(3) × downloadConcurrency(4) = 12 page-fetches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Pools {
    /** Source browse / search / details. Cancellable calls run here (see SourceBrowser *Async). */
    val source: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(16)

    /** Reader page images. Kept separate from covers so a reader grinding through a slow/down source
     *  (e.g. atsu.moe timing out every page) can't starve the library/Home cover grid. */
    val image: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(16)

    /** Cover thumbnails (Home/library/search grids). Its own slice so covers always load promptly,
     *  even while the reader pool is saturated on a dead source. */
    val cover: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8)

    /** Download page-fetches — their own slice so a big download doesn't fight interactive browsing. */
    val download: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(12)
}
