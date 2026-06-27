/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.status

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import org.slf4j.LoggerFactory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Durable, queryable history of jobs and their attempts (`data/jobs.json`). The download/
 * update/install managers report here; the CLI `status` command reads it back.
 */
object StatusStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("jobs.json")
    private const val MAX_JOBS = 200

    @Synchronized
    fun all(): List<Job> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Job>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun get(id: String): Job? = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(job: Job) {
        job.updatedAt = System.currentTimeMillis()
        val others = all().filterNot { it.id == job.id }
        val updated = (others + job).sortedByDescending { it.createdAt }.take(MAX_JOBS)
        file.createParentDirectories()
        file.writeText(json.encodeToString(updated))
        log.debug("Job {} -> {}", job.id, job.state)
    }

    fun newId(): String = System.currentTimeMillis().toString(36) + "-" + (1000..9999).random()
}
