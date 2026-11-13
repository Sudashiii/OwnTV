package dev.owntv.launcher.cleanup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The packages Clean up this TV turned off, with when (epoch millis). Only enabled packages are ever turned
 * off, so undoing one is `pm enable`. Stored in `files/cleanup/record.json`; written before a change runs.
 */
@Serializable
data class CleanupRecord(val packages: Map<String, Long> = emptyMap()) {
    fun added(names: Collection<String>, at: Long): CleanupRecord = copy(packages = packages + names.filterNot { it in packages }.associateWith { at })

    fun removed(names: Collection<String>): CleanupRecord = copy(packages = packages - names.toSet())

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun decode(text: String): CleanupRecord = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(CleanupRecord())
    }
}
