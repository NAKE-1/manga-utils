/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.status

import kotlinx.serialization.Serializable

enum class JobState { QUEUED, RUNNING, RETRYING, DONE, FAILED }

/**
 * One attempt to fetch something from one source — the backbone of the verbose status/error
 * trace. A chapter download that cascades across mirror sources produces one attempt per
 * candidate, each recording which source was tried and what happened.
 */
@Serializable
data class JobAttempt(
    val sourceId: Long,
    val target: String,
    val outcome: String, // "ok" | "failed"
    val message: String = "",
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

/** A unit of long-running work (a download, an update, an install) with its full attempt trace. */
@Serializable
data class Job(
    val id: String,
    val type: String,
    var state: JobState,
    val target: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var error: String = "",
    val attempts: MutableList<JobAttempt> = mutableListOf(),
)
