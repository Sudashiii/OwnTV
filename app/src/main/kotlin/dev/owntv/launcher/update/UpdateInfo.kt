package dev.owntv.launcher.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/** The `update.json` each GitHub release carries (see `.github/workflows/release.yml`). */
@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apk: String,
    val sha256: String,
    val notes: String = "",
    /** Read leniently by [parse]: a broken entry is left out instead of failing the update. */
    @Transient val dexMetadata: List<DexMetadata> = emptyList(),
    /** APK download size in bytes; older releases may omit it. */
    val apkSizeBytes: Long? = null,
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    /** The `.dm` built for Android API level [sdk], if the release has one. */
    fun dexMetadataFor(sdk: Int): DexMetadata? = dexMetadata.firstOrNull { sdk in it.minSdk..it.maxSdk }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Result<UpdateInfo> = runCatching { json.decodeFromString(serializer(), text) }
            .mapCatching { info ->
                require(info.apk.startsWith("https://") || info.apk.startsWith("http://")) { "The APK link is not a web address" }
                require(SHA256.matches(info.sha256)) { "The checksum is not a SHA-256 hash" }
                info.copy(
                    sha256 = info.sha256.lowercase(),
                    dexMetadata = dexMetadataOf(text),
                    apkSizeBytes = info.apkSizeBytes?.takeIf { it > 0 },
                )
            }

        private fun dexMetadataOf(text: String): List<DexMetadata> {
            val entries = runCatching { json.parseToJsonElement(text).jsonObject["dexMetadata"] as? JsonArray }.getOrNull()
            return entries.orEmpty().mapNotNull { entry ->
                runCatching { json.decodeFromJsonElement(DexMetadata.serializer(), entry) }.getOrNull()
                    ?.takeIf { (it.url.startsWith("https://") || it.url.startsWith("http://")) && SHA256.matches(it.sha256) }
                    ?.let { it.copy(sha256 = it.sha256.lowercase()) }
            }
        }

        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

/**
 * A dex metadata file (`.dm`) with the release's baseline profile, built for API levels [minSdk] to [maxSdk].
 * Installed next to the APK, it makes Android compile OwnTV during the install instead of running it interpreted
 * until the next background dexopt.
 */
@Serializable
data class DexMetadata(
    val minSdk: Int,
    val maxSdk: Int = Int.MAX_VALUE,
    val url: String,
    val sha256: String,
)

/** One GitHub release in the version list: its notes, and where its own `update.json` (APK link and checksum) is. */
data class ReleaseEntry(
    val versionName: String,
    val versionCode: Long,
    val updateJsonUrl: String,
    val notes: String,
    val apkSizeBytes: Long? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val tag = Regex("^v?(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /** Same formula as `versionCodeOf` in app/build.gradle.kts: 1.2.3 is 1002003. */
        fun versionCodeOf(versionName: String): Long? {
            val (major, minor, patch) = tag.matchEntire(versionName)?.destructured ?: return null
            return major.toLong() * 1_000_000 + minor.toLong() * 1_000 + patch.toLong()
        }

        /**
         * The answer of GitHub's `releases` API, newest version first. Drafts, releases without an `update.json`
         * and tags that are not `vX.Y.Z` are left out.
         */
        fun parseGitHub(text: String): Result<List<ReleaseEntry>> = runCatching {
            json.decodeFromString(ListSerializer(GitHubRelease.serializer()), text).mapNotNull { release ->
                if (release.draft) return@mapNotNull null
                val code = versionCodeOf(release.tag) ?: return@mapNotNull null
                val url = release.assets.firstOrNull { it.name == "update.json" }?.url ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val apkSize = release.assets.firstOrNull { it.name == "owntv-${release.tag.removePrefix("v")}.apk" }
                    ?.size?.takeIf { it > 0 }
                ReleaseEntry(release.tag.removePrefix("v"), code, url, release.body.orEmpty(), apkSize)
            }.sortedByDescending { it.versionCode }
        }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long? = null,
)

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
