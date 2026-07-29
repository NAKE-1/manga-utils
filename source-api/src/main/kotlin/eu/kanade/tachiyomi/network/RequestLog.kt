package eu.kanade.tachiyomi.network

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A small ring buffer of recent outbound requests made through the shared OkHttp client, for the
 * Developer screen's network log. Recorded by a network interceptor in [NetworkHelper].
 *
 * ponytail: keeps the last [CAP] requests including image/CDN fetches — a busy chapter load can flush
 * the API calls out of view. Clear the log then trigger the action you want to inspect. Add a
 * server-side image filter only if that's actually annoying.
 */
object RequestLog {
    data class Entry(
        val time: Long,
        val method: String,
        val host: String,
        val path: String,
        val code: Int,
        val ms: Long,
    )

    private const val CAP = 500
    private val entries = ConcurrentLinkedDeque<Entry>()

    fun record(method: String, host: String, path: String, code: Int, ms: Long) {
        entries.addLast(Entry(System.currentTimeMillis(), method, host, path, code, ms))
        while (entries.size > CAP) entries.pollFirst()
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() = entries.clear()
}
