/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import java.nio.file.Path

/** What to do when a chapter's output file already exists on disk. */
enum class ExistingPolicy {
    SKIP,
    REPLACE,
    ASK,
    ;

    companion object {
        fun from(s: String): ExistingPolicy? =
            when (s.trim().lowercase()) {
                "skip" -> SKIP
                "replace", "overwrite" -> REPLACE
                "ask", "prompt" -> ASK
                else -> null
            }
    }
}

/** A per-chapter decision when [ExistingPolicy.ASK] is in effect. The *_ALL variants latch. */
enum class ExistingDecision { SKIP, REPLACE, SKIP_ALL, REPLACE_ALL }

/**
 * Supplied by the UI layer (CLI/TUI) so the engine can ask the user about an existing file
 * without `core` ever touching stdin. Returns the decision for [chapterName] at [dest].
 */
fun interface ExistingPrompt {
    fun decide(
        chapterName: String,
        dest: Path,
    ): ExistingDecision
}
