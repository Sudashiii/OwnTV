package dev.owntv.launcher.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateInfoTest {
    private val sha = "AB".repeat(32)

    private fun json(apk: String = "https://github.com/o/r/releases/download/v0.2.0/owntv-0.2.0.apk", sha256: String = sha) =
        """{"versionName":"0.2.0","versionCode":2000,"apk":"$apk","sha256":"$sha256","notes":"### Features\n\n- x","extra":1}"""

    @Test
    fun `parses the release workflow's update json`() {
        val info = UpdateInfo.parse(json()).getOrThrow()
        assertEquals("0.2.0", info.versionName)
        assertEquals(2000, info.versionCode)
        assertEquals("ab".repeat(32), info.sha256, "the checksum is compared in lower case")
        assertTrue(info.isNewerThan(1000))
        assertFalse(info.isNewerThan(2000))
    }

    @Test
    fun `rejects update json without a usable apk link or checksum`() {
        assertTrue(UpdateInfo.parse(json(apk = "file:///sdcard/evil.apk")).isFailure)
        assertTrue(UpdateInfo.parse(json(sha256 = "abc")).isFailure)
        assertTrue(UpdateInfo.parse("""{"versionName":"0.2.0"}""").isFailure)
        assertTrue(UpdateInfo.parse("<html>Not Found</html>").isFailure)
    }

    @Test
    fun `picks the profile built for the TV's Android version`() {
        val dm = "https://github.com/o/r/releases/download/v0.2.0/owntv-0.2.0-api"
        val text = json().dropLast(1) + ""","dexMetadata":[""" +
            """{"minSdk":28,"maxSdk":30,"url":"${dm}28.dm","sha256":"${"CD".repeat(32)}"},""" +
            """{"minSdk":31,"maxSdk":2147483647,"url":"${dm}31.dm","sha256":"${"ef".repeat(32)}"}]}"""
        val info = UpdateInfo.parse(text).getOrThrow()
        assertNull(info.dexMetadataFor(27))
        assertEquals("${dm}28.dm", info.dexMetadataFor(29)?.url)
        assertEquals("cd".repeat(32), info.dexMetadataFor(30)?.sha256, "the checksum is compared in lower case")
        assertEquals("${dm}31.dm", info.dexMetadataFor(34)?.url)
    }

    @Test
    fun `a missing or broken profile never blocks the update`() {
        assertEquals(emptyList(), UpdateInfo.parse(json()).getOrThrow().dexMetadata, "update.json from before profiles")
        val good = """{"minSdk":31,"url":"https://x/owntv.dm","sha256":"${"ab".repeat(32)}"}"""
        val broken = listOf(
            """{"minSdk":28,"maxSdk":30,"url":"file:///sdcard/evil.dm","sha256":"${"ab".repeat(32)}"}""",
            """{"minSdk":28,"url":"https://x/owntv.dm","sha256":"abc"}""",
            """{"url":"https://x/owntv.dm"}""",
            """"owntv.dm"""",
        )
        val info = UpdateInfo.parse(json().dropLast(1) + ""","dexMetadata":[${(broken + good).joinToString(",")}]}""").getOrThrow()
        assertEquals(listOf(31), info.dexMetadata.map { it.minSdk })
        assertEquals(Int.MAX_VALUE, info.dexMetadata.single().maxSdk)
        assertNull(info.dexMetadataFor(29))
        assertTrue(UpdateInfo.parse(json().dropLast(1) + ""","dexMetadata":"none"}""").getOrThrow().dexMetadata.isEmpty())
    }

    @Test
    fun `release notes drop commit links and markdown`() {
        val notes = """
            ### Breaking changes

            - **layout:** schemaVersion is now 2. ([3af4a73](https://github.com/o/r/commit/3af4a73484fd3e166ddc75a649d37ae7f521d4ca))

            ### Features

            - **app:** Add a [settings](https://example.com) shortcut ([c723166](https://github.com/o/r/commit/c723166))
            - Add OwnTV launcher ([d43fb9c](https://github.com/o/r/commit/d43fb9c))

            **Full changelog:** [v0.1.0...v0.2.0](https://github.com/o/r/compare/v0.1.0...v0.2.0)
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseNotes.Section("Breaking changes", listOf("layout: schemaVersion is now 2.")),
                ReleaseNotes.Section("Features", listOf("app: Add a settings shortcut", "Add OwnTV launcher")),
            ),
            ReleaseNotes.parse(notes),
        )
        assertEquals(listOf(ReleaseNotes.Section(null, listOf("No new features or fixes."))), ReleaseNotes.parse("No new features or fixes.\n"))
    }

    @Test
    fun `lists GitHub releases newest first and skips what the updater cannot use`() {
        fun release(tag: String, draft: Boolean = false, asset: String = "update.json") =
            """{"tag_name":"$tag","draft":$draft,"body":"notes $tag","assets":[{"name":"$asset","browser_download_url":"https://github.com/o/r/releases/download/$tag/$asset","size":1}],"x":1}"""
        val text = "[" + listOf(
            release("v0.2.1"),
            release("v0.3.0"),
            release("v0.4.0", draft = true),
            release("v0.5.0", asset = "owntv-0.5.0.apk"),
            release("nightly"),
            release("v0.1.0"),
        ).joinToString(",") + "]"
        val list = ReleaseEntry.parseGitHub(text).getOrThrow()
        assertEquals(listOf("0.3.0", "0.2.1", "0.1.0"), list.map { it.versionName })
        assertEquals(listOf(3000L, 2001L, 1000L), list.map { it.versionCode })
        assertEquals("notes v0.3.0", list.first().notes)
        assertEquals("https://github.com/o/r/releases/download/v0.3.0/update.json", list.first().updateJsonUrl)
        assertTrue(ReleaseEntry.parseGitHub("""{"message":"API rate limit exceeded"}""").isFailure)
    }
}
