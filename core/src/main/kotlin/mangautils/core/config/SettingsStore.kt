/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.config

import kotlinx.serialization.json.Json
import mangautils.core.download.ExistingPolicy
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Loads/saves [Settings] from `data/settings.json`, with string key access for the CLI. */
object SettingsStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = AppConfig.dataDir.resolve("settings.json")

    @Volatile
    private var cached: Settings? = null

    @Synchronized
    fun get(): Settings {
        cached?.let { return it }
        val loaded =
            if (file.exists()) {
                runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrDefault(Settings())
            } else {
                Settings()
            }
        cached = loaded
        return loaded
    }

    @Synchronized
    fun save(settings: Settings) {
        file.createParentDirectories()
        file.writeText(json.encodeToString(settings))
        cached = settings
    }

    fun reset() = save(Settings())

    /** Editable scalar keys, in display order, with their current string value. */
    fun describe(): List<Pair<String, String>> {
        val s = get()
        return listOf(
            "downloadConcurrency" to s.downloadConcurrency.toString(),
            "downloadRetries" to s.downloadRetries.toString(),
            "existingBehavior" to s.existingBehavior.name.lowercase(),
            "defaultFormat" to s.defaultFormat,
            "defaultLanguage" to (s.defaultLanguage ?: ""),
            "nsfwVisible" to s.nsfwVisible.toString(),
        )
    }

    fun getByKey(key: String): String? = describe().firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

    /** Sets a scalar key from a string; returns an error message or null on success. */
    @Synchronized
    fun setByKey(
        key: String,
        value: String,
    ): String? {
        val s = get()
        val updated =
            when (key.lowercase()) {
                "downloadconcurrency" ->
                    s.copy(downloadConcurrency = value.toIntOrNull()?.coerceIn(1, 32) ?: return "must be an integer 1..32")
                "downloadretries" ->
                    s.copy(downloadRetries = value.toIntOrNull()?.coerceIn(0, 10) ?: return "must be an integer 0..10")
                "existingbehavior" ->
                    s.copy(existingBehavior = ExistingPolicy.from(value) ?: return "must be skip|replace|ask")
                "defaultformat" -> s.copy(defaultFormat = value.lowercase())
                "defaultlanguage" -> s.copy(defaultLanguage = value.ifBlank { null })
                "nsfwvisible" ->
                    s.copy(nsfwVisible = value.toBooleanStrictOrNull() ?: return "must be true|false")
                else -> return "unknown setting '$key'"
            }
        save(updated)
        return null
    }
}
