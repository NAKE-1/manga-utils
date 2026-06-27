/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import mangautils.core.status.JobState
import mangautils.core.status.StatusStore
import java.text.SimpleDateFormat
import java.util.Date

/** `mu status [jobId]` — list recent jobs, or show one job's full attempt trace. */
class StatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "Show job status and the verbose attempt trace"

    private val jobId by argument(name = "jobId").optional()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    override fun run() {
        val id = jobId
        if (id == null) {
            val jobs = StatusStore.all()
            if (jobs.isEmpty()) {
                echo(Style.dim("No jobs yet."))
                return
            }
            jobs.take(20).forEach { j ->
                val ok = j.attempts.count { it.outcome == "ok" }
                val failed = j.attempts.count { it.outcome == "failed" }
                echo("${Style.id(j.id)}  ${state(j.state)}  ${Style.dim(j.type)}  ${j.target}  ${Style.dim("($ok ok, $failed failed)")}")
            }
            echo("")
            echo(Style.dim("Details: mu status <jobId>"))
            return
        }

        val job = StatusStore.get(id)
        if (job == null) {
            echo(Style.warn("No such job: $id"), err = true)
            return
        }
        echo("${Style.label("Job:")}     ${Style.id(job.id)}")
        echo("${Style.label("Type:")}    ${job.type}")
        echo("${Style.label("State:")}   ${state(job.state)}")
        echo("${Style.label("Target:")}  ${job.target}")
        echo("${Style.label("Created:")} ${fmt.format(Date(job.createdAt))}")
        if (job.error.isNotBlank()) echo("${Style.label("Error:")}   ${Style.warn(job.error)}")
        echo("")
        echo(Style.heading("Attempts (${job.attempts.size}):"))
        job.attempts.forEach { a ->
            val tag = if (a.outcome == "ok") Style.ok("ok  ") else Style.warn("fail")
            echo("  $tag ${Style.dim("src ${a.sourceId}")}  ${Style.title(a.target)}  ${Style.dim("${a.durationMs}ms")}")
            if (a.message.isNotBlank()) echo("       ${Style.dim(a.message)}")
        }
    }

    private fun state(s: JobState): String =
        if (s == JobState.DONE) Style.ok(s.name) else if (s == JobState.FAILED) Style.warn(s.name) else Style.dim(s.name)
}
