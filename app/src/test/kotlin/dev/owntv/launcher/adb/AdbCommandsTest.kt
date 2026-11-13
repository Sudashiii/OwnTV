package dev.owntv.launcher.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbCommandsTest {
    private val all = AdbCommands.groups("/sdcard/Android/data/dev.owntv.launcher/files").flatMap { it.commands }

    @Test
    fun `every command has a title, a description and an adb line`() {
        all.forEach { command ->
            assertTrue(command.title.isNotBlank() && command.description.isNotBlank(), command.toString())
            assertTrue(command.line.startsWith("adb "), command.line)
        }
        assertEquals(all.size, all.map { it.title }.toSet().size, "titles are unique")
    }

    @Test
    fun `shell commands run on the TV unless they only help on a computer`() {
        assertEquals("cmd package set-home-activity dev.owntv.launcher/.MainActivity", AdbCommands.SetHome.shellCommand)
        assertNotNull(AdbCommands.GrantSecureSettings.shellCommand)
        assertNull(AdbCommands.ReadPerformanceReport.shellCommand)
        assertNull(AdbCommands.OpenAdbPort.shellCommand)
        assertNull(AdbCommands.pushLayout("/x").shellCommand)
    }

    @Test
    fun `the list includes the commands the help texts show`() {
        listOf(
            AdbCommands.SetHome,
            AdbCommands.DisableGoogleTvLauncher,
            AdbCommands.EnableGoogleTvLauncher,
            AdbCommands.AllowRestrictedSettings,
            AdbCommands.GrantSecureSettings,
            AdbCommands.ScreenReaderModeOff,
            AdbCommands.UsageAccess,
            AdbCommands.OpenAdbPort,
            AdbCommands.ReadPerformanceReport,
        ).forEach { assertTrue(it in all, it.title) }
    }
}
