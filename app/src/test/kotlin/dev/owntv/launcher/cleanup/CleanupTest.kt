package dev.owntv.launcher.cleanup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanupTest {
    /** What the Google TV emulator (API 34) printed for [CleanupScan.SCRIPT], shortened. */
    private val scanOutput = """
        ==enabled
        package:com.google.android.feedback
        package:com.google.android.play.games
        package:com.google.android.katniss
        package:com.google.android.tungsten.setupwraith
        package:com.android.tv.provision
        package:com.android.systemui
        package:com.android.providers.media
        package:com.android.systemui.auto_generated_rro_vendor__
        package:com.google.android.inputmethod.latin
        package:com.example.vendorthing
        package:dev.owntv.launcher
        ==disabled
        package:com.google.android.apps.tv.launcherx
        package:com.google.android.tvrecommendations
        ==home
        priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
        dev.owntv.launcher/.MainActivity
        ==homes
        2 activities found:
          Activity #0:
            dev.owntv.launcher/.MainActivity
          Activity #1:
            com.google.android.tungsten.setupwraith/com.google.android.libraries.tv.setupwraith.RecoveryActivity
        ==settings
        1 activities found:
          Activity #0:
            priority=1 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.android.tv.settings/.MainSettings
        ==inputs
        No services found
        ==keyboard
        com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
        ==wifi
        cname=android.uid.system:1000
        cname=com.android.tv.provision
    """.trimIndent()

    private val catalog = CleanupCatalog(
        source = CleanupCatalog.Source("Gathin23/cleanyourtv", "abc"),
        packages = mapOf(
            "com.google.android.feedback" to CleanupCatalog.PackageInfo(363, 6, "send feedback"),
            "com.google.android.play.games" to CleanupCatalog.PackageInfo(287),
            "com.google.android.katniss" to CleanupCatalog.PackageInfo(138, 62, "Google Assistant"),
            "com.google.android.tungsten.setupwraith" to CleanupCatalog.PackageInfo(165, 19),
            "com.android.tv.provision" to CleanupCatalog.PackageInfo(3),
            "com.google.android.apps.tv.launcherx" to CleanupCatalog.PackageInfo(62),
        ),
        lists = listOf(
            list("google-chromecast-gtv-4k/debloat.txt", "Google Chromecast with Google TV 4K (sabrina)", "Google", "com.google.android.feedback", "com.google.android.katniss"),
            list("google-chromecast-gtv-4k/debloat-keep-cast.txt", "Google Chromecast with Google TV 4K (sabrina)", "Google", "com.google.android.feedback", variant = "keep-cast"),
            list("google-tv-streamer-4k-kirkwood/debloat.txt", "Google TV Streamer (kirkwood)", "Google", "com.google.android.play.games"),
            list("sony-bravia-8-ii/debloat.txt", "Sony BRAVIA 8 II (K-55XR8M2, BRAVIA_AE2), UK/EU", "Sony", "tv.samba.ssm"),
        ),
    )

    private fun list(id: String, tv: String, brand: String, vararg off: String, variant: String? = null) = CleanupCatalog.TvList(
        id = id, tv = tv, brand = brand, match = "${id.substringBefore('/').replace('-', ' ')} $tv".lowercase(), off = off.toList(), variant = variant,
    )

    private val state = assertNotNull(CleanupScan.parse(scanOutput))

    @Test
    fun `the scan reads every section`() {
        assertTrue("com.google.android.feedback" in state.enabled)
        assertEquals(setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvrecommendations"), state.disabled)
        assertEquals("dev.owntv.launcher", state.homeOwner)
        assertTrue(state.ownTvIsHome)
        assertEquals(setOf("dev.owntv.launcher", "com.google.android.tungsten.setupwraith"), state.homeApps)
        assertEquals(setOf("com.android.tv.settings"), state.settingsHandlers)
        assertEquals(emptySet(), state.tvInputs)
        assertEquals("com.google.android.inputmethod.latin", state.keyboard)
        assertEquals(setOf("com.android.tv.provision"), state.wifiOwners)
    }

    @Test
    fun `sections the TV did not answer are unknown, and no package list is no scan`() {
        val partial = assertNotNull(CleanupScan.parse("==enabled\npackage:a.b\n==inputs\n/system/bin/sh: cmd: not found\n==wifi\n"))
        assertNull(partial.tvInputs)
        assertNull(partial.wifiOwners)
        assertNull(partial.homeOwner)
        assertNull(CleanupScan.parse("error: closed"))
    }

    @Test
    fun `core packages, OwnTV and Play are never offered, overlays only from a list`() {
        listOf("android", "com.android.systemui", "com.android.providers.media", "com.android.tv.settings", "com.google.android.gms",
            "com.android.vending", "dev.owntv.launcher", "com.mediatek.tv.service", "com.google.android.overlay.modules.foo", "bad name;rm")
            .forEach { assertTrue(CleanupRules.isProtected(it), it) }
        listOf("com.android.providers.calendar", "com.google.android.feedback", "tv.samba.ssm").forEach { assertFalse(CleanupRules.isProtected(it), it) }
        assertTrue(CleanupRules.isOverlay("com.android.systemui.auto_generated_rro_vendor__"))
        assertFalse(CleanupRules.isOverlay("com.example.mirroring"))

        val plan = CleanupPlanner.build(catalog, state, null, emptySet())
        val offered = (plan.suggested + plan.common + plan.other).map { it.packageName }
        assertFalse(offered.any { it == "com.android.systemui" || it == "dev.owntv.launcher" || it == "com.android.providers.media" })
        assertFalse("com.android.systemui.auto_generated_rro_vendor__" in offered)
    }

    @Test
    fun `the chosen list is suggested, other lists sorted by how often, the rest under a warning`() {
        val chromecast = catalog.lists.first()
        val plan = CleanupPlanner.build(catalog, state, chromecast, setOf("com.google.android.apps.tv.launcherx"))
        assertEquals(listOf("com.google.android.feedback", "com.google.android.katniss"), plan.suggested.map { it.packageName })
        assertTrue(plan.suggested.first().suggested)
        assertFalse(plan.suggested.last().suggested, "the Assistant has a warning, so it is never selected for you")
        assertEquals(listOf("com.google.android.play.games", "com.google.android.tungsten.setupwraith", "com.android.tv.provision"), plan.common.map { it.packageName })
        assertEquals(listOf("com.example.vendorthing"), plan.other.map { it.packageName }, "the keyboard is blocked, so it is left out")
        assertEquals(CleanupRules.OTHER_WARNING, plan.other.single().warning)
        assertEquals(listOf(true, false), plan.turnedOff.map { it.byOwnTv })
        assertEquals(emptyList(), plan.unchecked)
    }

    @Test
    fun `guards keep Home, Wi-Fi, Settings, inputs and the keyboard`() {
        assertNotNull(CleanupPlanner.blockReason("com.android.tv.provision", state), "owns the saved Wi-Fi")
        assertNull(CleanupPlanner.blockReason(CleanupRules.SETUP_WIZARD, state), "OwnTV is Home and the wizard does not own the Wi-Fi")
        assertNotNull(CleanupPlanner.blockReason("com.google.android.inputmethod.latin", state))

        val stockHome = state.copy(homeOwner = "com.google.android.apps.tv.launcherx")
        assertNotNull(CleanupPlanner.blockReason("com.google.android.apps.tv.launcherx", stockHome))
        assertNotNull(CleanupPlanner.blockReason(CleanupRules.SETUP_WIZARD, stockHome), "answers Home while OwnTV is not Home")
        val chooser = state.copy(homeOwner = "android", homeApps = null)
        assertNotNull(CleanupPlanner.blockReason("com.google.android.tvlauncher", chooser), "a known launcher when nothing says who answers Home")

        val unknown = state.copy(wifiOwners = null, tvInputs = null)
        assertNotNull(CleanupPlanner.blockReason(CleanupRules.SETUP_WIZARD, unknown))
        assertNull(CleanupPlanner.blockReason("com.google.android.feedback", unknown))
        assertNotNull(CleanupPlanner.blockReason("com.example.vendorthing", unknown, strict = true))
        assertEquals(2, CleanupPlanner.build(catalog, unknown, null, emptySet()).unchecked.size)

        assertNotNull(CleanupPlanner.blockReason("com.vendor.hdmi", state.copy(tvInputs = setOf("com.vendor.hdmi"))))
        assertNotNull(CleanupPlanner.blockReason("com.vendor.settings", state.copy(settingsHandlers = setOf("com.vendor.settings"))))
    }

    @Test
    fun `lists match by codename or model, the main list before its variants`() {
        val sabrina = CleanupPlanner.matchLists(catalog, "Google", "Chromecast", "sabrina", "sabrina")
        assertEquals("google-chromecast-gtv-4k/debloat.txt", sabrina.best?.id)
        assertEquals(3, sabrina.choices.size)
        assertEquals("google-tv-streamer-4k-kirkwood/debloat.txt", CleanupPlanner.matchLists(catalog, "Google", "Google TV Streamer", "kirkwood", "kirkwood").best?.id)
        assertEquals("sony-bravia-8-ii/debloat.txt", CleanupPlanner.matchLists(catalog, "Sony", "K-55XR8M2", "BRAVIA_AE2", "BRAVIA_AE2_EU").best?.id)

        val emulator = CleanupPlanner.matchLists(catalog, "Google", "sdk_google_atv64_amati_arm64", "emu64a", "sdk_google_atv64_amati_arm64")
        assertNull(emulator.best, "the brand alone is not a match")
        assertEquals(3, emulator.choices.size)
        assertEquals(4, CleanupPlanner.matchLists(catalog, "Acme", "X", "x", "x").choices.size)
    }

    @Test
    fun `commands only take plain package names, in batches`() {
        val names = (1..12).map { "com.example.app$it" }
        val commands = CleanupPlanner.disableCommands(names)
        assertEquals(2, commands.size)
        assertTrue(commands.first().startsWith("pm disable-user --user 0 com.example.app1; pm disable-user --user 0 com.example.app2"))
        assertEquals("pm enable --user 0 com.example.app1", CleanupPlanner.enableCommands(listOf("com.example.app1")).single())
        assertFailsWith<IllegalArgumentException> { CleanupPlanner.disableCommands(listOf("com.example.a; reboot")) }
        assertEquals(listOf("b.c"), CleanupPlanner.stillEnabled(listOf("a.b", "b.c"), setOf("b.c")))
        assertEquals(
            "Error: java.lang.IllegalArgumentException: Shell cannot change component state for b.c to 3",
            CleanupPlanner.errorFor("b.c", "Package a.b new state: disabled-user\nError: java.lang.IllegalArgumentException: Shell cannot change component state for b.c to 3"),
        )
    }

    @Test
    fun `the record survives a round trip and keeps the first time`() {
        val record = CleanupRecord().added(listOf("a.b", "c.d"), 1).added(listOf("a.b"), 2)
        assertEquals(mapOf("a.b" to 1L, "c.d" to 1L), CleanupRecord.decode(record.encode()).packages)
        assertEquals(setOf("c.d"), record.removed(listOf("a.b")).packages.keys)
        assertEquals(CleanupRecord(), CleanupRecord.decode("not json"))
    }

    @Test
    fun `the bundled catalog decodes, without core packages or odd names`() {
        val bundled = CleanupCatalog.decode(File("src/main/assets/${CleanupCatalog.ASSET}").readText())
        assertEquals("Gathin23/cleanyourtv", bundled.source.repo)
        assertTrue(bundled.lists.size > 400 && bundled.packages.size > 1000)
        val names = bundled.packages.keys + bundled.lists.flatMap { it.off }
        names.forEach { assertTrue(CleanupRules.isValidPackage(it), it) }
        assertTrue(names.none { CleanupRules.isProtected(it) && it !in setOf("com.android.vending", "com.google.android.gms", "com.google.android.gsf") })
        assertTrue(bundled.lists.all { it.off.isNotEmpty() && it.off.all { name -> name in bundled.packages } })
        assertTrue(File("src/main/assets/cleanup/cleanyourtv-LICENSE.txt").readText().startsWith("MIT License"))
    }
}
