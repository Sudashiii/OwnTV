package dev.owntv.layout.model

/**
 * One "continue watching" entry an app published to Android TV (Watch Next). [positionMillis] and
 * [durationMillis] are 0 when the app does not report them.
 */
data class WatchNextEntry(
    val id: String,
    val packageName: String,
    val title: String,
    val posterUri: String?,
    val durationMillis: Long,
    val positionMillis: Long,
    /** An `intent:` URI that resumes the entry in its app. */
    val intentUri: String?,
    val lastEngagementMillis: Long,
) {
    /** Share watched, 0 to 1; null when the app reports no duration or no position. */
    val progress: Float?
        get() = if (durationMillis > 0 && positionMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else null

    /** Time left, or null when [progress] is unknown or nothing is left. */
    val remainingMillis: Long?
        get() = if (progress != null && durationMillis > positionMillis) durationMillis - positionMillis else null
}

object WatchNext {
    /** The entries a row shows: [exclude]d apps removed, most recently watched first, then [skip] and [limit] applied. */
    fun select(entries: List<WatchNextEntry>, exclude: List<String> = emptyList(), skip: Int = 0, limit: Int? = null): List<WatchNextEntry> {
        val remaining = entries
            .filter { it.packageName !in exclude }
            .distinctBy { it.id }
            .sortedByDescending { it.lastEngagementMillis }
            .drop(skip)
        return if (limit != null) remaining.take(limit) else remaining
    }

    /** "2h 18min", "1h", "45min": [millis] rounded up to whole minutes. */
    fun durationText(millis: Long): String {
        val minutes = ((millis + 59_999) / 60_000).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}min"
            rest == 0L -> "${hours}h"
            else -> "${hours}h ${rest}min"
        }
    }

    /** The line under a focused entry: its app and the time left, e.g. "Nuvio · 2h 18min left". */
    fun description(entry: WatchNextEntry, appLabel: String?): String {
        val left = entry.remainingMillis?.let { "${durationText(it)} left" }
        return listOfNotNull(appLabel, left).joinToString(" · ")
    }
}
