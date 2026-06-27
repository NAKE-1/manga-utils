/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import org.slf4j.LoggerFactory

/** An installed extension that has a newer version available in a repo. */
data class ExtensionUpdate(
    val installed: InstalledExtension,
    val entry: ExtensionRepoEntry,
    val indexUrl: String,
)

/** Compares installed extensions against the added repositories to find available updates. */
object ExtensionUpdates {
    private val log = LoggerFactory.getLogger(javaClass)

    fun check(client: ExtensionRepoClient = ExtensionRepoClient()): List<ExtensionUpdate> {
        // Build pkg -> (best/highest-version entry, repo url) across all added repos.
        val best = HashMap<String, Pair<ExtensionRepoEntry, String>>()
        for (repo in RepoStore.list()) {
            val entries = runCatching { client.fetchIndex(repo) }.getOrElse {
                log.warn("Update check: failed to fetch {}: {}", repo, it.message)
                emptyList()
            }
            for (e in entries) {
                val current = best[e.pkg]
                if (current == null || e.code > current.first.code) best[e.pkg] = e to repo
            }
        }
        return InstalledStore.list().mapNotNull { inst ->
            val (entry, url) = best[inst.pkg] ?: return@mapNotNull null
            if (entry.code > inst.versionCode) ExtensionUpdate(inst, entry, url) else null
        }
    }
}
