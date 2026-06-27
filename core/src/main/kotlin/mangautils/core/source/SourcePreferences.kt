/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import android.app.Application
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import org.slf4j.LoggerFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One configurable preference exposed by a source (e.g. content quality, language). */
data class SourcePref(
    val index: Int,
    val key: String?,
    val title: String,
    val summary: String?,
    /** "String" | "Boolean" | "Set<String>" */
    val type: String,
    val value: String,
    /** Display labels for list-style preferences (null otherwise). */
    val entries: List<String>?,
    /** The stored values matching [entries] one-to-one. */
    val entryValues: List<String>?,
    val enabled: Boolean,
)

/**
 * Reads and writes a source's own preferences (the "quality / language / …" toggles a source
 * declares via [ConfigurableSource.setupPreferenceScreen]). Values persist through the source's
 * scoped SharedPreferences. Mirrors Suwayomi's source-preferences handling.
 */
object SourcePreferences {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Null if the source isn't installed; empty list if it has no configurable preferences. */
    fun list(sourceId: Long): List<SourcePref>? {
        val source = SourceManager.loadSource(sourceId) ?: return null
        if (source !is ConfigurableSource) return emptyList()
        return try {
            buildScreen(source).preferences.mapIndexed { i, p -> toModel(i, p) }
        } catch (e: Exception) {
            log.warn("Could not load preferences for source {}: {}", sourceId, e.toString())
            throw IllegalStateException("the source's settings could not be loaded (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Set preference [index] to [rawValue]. Returns an error message, or null on success. */
    fun set(
        sourceId: Long,
        index: Int,
        rawValue: String,
    ): String? {
        val source = SourceManager.loadSource(sourceId) ?: return "source $sourceId is not installed"
        if (source !is ConfigurableSource) return "source has no configurable settings"
        val prefs = buildScreen(source).preferences
        val pref = prefs.getOrNull(index) ?: return "no preference #$index (have 0..${prefs.size - 1})"
        if (!pref.isEnabled) return "preference is disabled"
        val type = runCatching { pref.defaultValueType }.getOrDefault("String")
        val newValue: Any =
            when (type) {
                "Boolean" -> rawValue.toBooleanStrictOrNull() ?: return "must be true|false"
                "Set<String>" -> rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                else -> rawValue
            }
        return try {
            pref.saveNewValue(newValue)
            pref.callChangeListener(newValue)
            log.debug("Set source {} pref[{}] '{}' = {}", sourceId, index, pref.key, newValue)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    private fun buildScreen(source: ConfigurableSource): PreferenceScreen {
        // ExtensionRuntime is already started by SourceManager.loadSource → Application is bound.
        val screen = PreferenceScreen(Injekt.get<Application>())
        screen.sharedPreferences = source.sourcePreferences()
        source.setupPreferenceScreen(screen)
        return screen
    }

    private fun toModel(
        index: Int,
        p: Preference,
    ): SourcePref {
        val type = runCatching { p.defaultValueType }.getOrDefault("String")
        val value = runCatching { p.currentValue?.toString() }.getOrNull().orEmpty()
        // entries/entryValues live on List/MultiSelect subclasses — read reflectively to avoid hard casts.
        val entries = readCharSeqArray(p, "getEntries")
        val entryValues = readCharSeqArray(p, "getEntryValues")
        return SourcePref(
            index = index,
            key = runCatching { p.key }.getOrNull(),
            title = runCatching { p.title?.toString() }.getOrNull()?.ifBlank { null } ?: (p.key ?: "preference $index"),
            summary = runCatching { p.summary?.toString() }.getOrNull()?.ifBlank { null },
            type = type,
            value = value,
            entries = entries,
            entryValues = entryValues,
            enabled = runCatching { p.isEnabled }.getOrDefault(true),
        )
    }

    private fun readCharSeqArray(
        p: Preference,
        method: String,
    ): List<String>? =
        runCatching {
            val arr = p.javaClass.getMethod(method).invoke(p) as? Array<*> ?: return null
            arr.map { it.toString() }
        }.getOrNull()
}
