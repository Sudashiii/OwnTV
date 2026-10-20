package dev.owntv.launcher.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `update.json` each GitHub release carries (see `.github/workflows/release.yml`). */
@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apk: String,
    val sha256: String,
    val notes: String = "",
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Result<UpdateInfo> = runCatching { json.decodeFromString(serializer(), text) }
            .mapCatching { info ->
                require(info.apk.startsWith("https://") || info.apk.startsWith("http://")) { "The APK link is not a web address" }
                require(SHA256.matches(info.sha256)) { "The checksum is not a SHA-256 hash" }
                info.copy(sha256 = info.sha256.lowercase())
            }

        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

/** Release notes from `scripts/release.py`, reduced to what reads well on a TV: sections of plain bullet points. */
object ReleaseNotes {
    data class Section(val title: String?, val items: List<String>)

    private val heading = Regex("^#{1,6}\\s+(.+)$")
    private val bullet = Regex("^[-*]\\s+(.+)$")
    private val commitLink = Regex("\\s*\\(\\[[0-9a-f]{7,40}]\\([^)]*\\)\\)")
    private val link = Regex("\\[([^]]*)]\\([^)]*\\)")
    private val emphasis = Regex("\\*\\*|__|`")

    fun parse(markdown: String): List<Section> {
        val sections = mutableListOf<Section>()
        var title: String? = null
        val items = mutableListOf<String>()
        fun flush() {
            if (items.isNotEmpty()) sections += Section(title, items.toList())
            items.clear()
        }
        markdown.lines().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                // The compare link means nothing on a TV.
                line.startsWith("**Full changelog:**") -> Unit
                heading.matches(line) -> {
                    flush()
                    title = plain(heading.find(line)!!.groupValues[1])
                }
                bullet.matches(line) -> items += plain(bullet.find(line)!!.groupValues[1])
                else -> items += plain(line)
            }
        }
        flush()
        return sections
    }

    private fun plain(text: String): String =
        text.replace(commitLink, "").replace(link) { it.groupValues[1] }.replace(emphasis, "").trim()
}
