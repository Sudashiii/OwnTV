package dev.owntv.launcher.androidsettings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidSettingsLogicTest {
    @Test
    fun `commands are built from plain values`() {
        assertEquals("settings put global auto_time 1", AndroidCommands.put(SettingsTable.Global, "auto_time", "1"))
        assertEquals("settings put system screen_off_timeout 1800000", AndroidCommands.put(SettingsTable.System, "screen_off_timeout", "1800000"))
        assertEquals("svc wifi enable", AndroidCommands.wifi(true))
        assertEquals("svc bluetooth disable", AndroidCommands.bluetooth(false))
        assertEquals("cmd alarm set-timezone Europe/Berlin", AndroidCommands.timeZone("Europe/Berlin"))
        assertEquals(
            "pm grant dev.owntv.launcher android.permission.WRITE_SECURE_SETTINGS",
            AndroidCommands.grantSecureSettings("dev.owntv.launcher"),
        )
    }

    @Test
    fun `nothing that could reach the shell gets through`() {
        assertFailsWith<IllegalArgumentException> { AndroidCommands.timeZone("Europe/Berlin; reboot") }
        assertFailsWith<IllegalArgumentException> { AndroidCommands.put(SettingsTable.Global, "auto_time", "1 && id") }
        assertFailsWith<IllegalArgumentException> { AndroidCommands.put(SettingsTable.Global, "", "1") }
        assertFailsWith<IllegalArgumentException> { AndroidCommands.timeZone("\$(id)") }
    }

    @Test
    fun `screen timeout reads naturally and steps within its list`() {
        assertEquals("15 seconds", ScreenTimeout.label(15_000))
        assertEquals("1 minute", ScreenTimeout.label(60_000))
        assertEquals("30 minutes", ScreenTimeout.label(30 * 60_000))
        assertEquals("1 hour", ScreenTimeout.label(60 * 60_000))
        assertEquals("Never", ScreenTimeout.label(ScreenTimeout.Never))
        assertEquals(2 * 60_000, ScreenTimeout.step(60_000, 1))
        assertEquals(30_000, ScreenTimeout.step(60_000, -1))
        assertEquals(15_000, ScreenTimeout.step(15_000, -1))
        assertEquals(ScreenTimeout.Never, ScreenTimeout.step(ScreenTimeout.Never, 1))
        // A value the list does not have steps from the nearest one.
        assertEquals(5 * 60_000, ScreenTimeout.step(200_000, 1))
    }

    @Test
    fun `surround modes cycle`() {
        assertEquals(SurroundMode.Automatic, SurroundMode.of(0))
        assertEquals(SurroundMode.Automatic, SurroundMode.of(7))
        assertEquals(SurroundMode.Never, SurroundMode.step(SurroundMode.Automatic, 1))
        assertEquals(SurroundMode.Always, SurroundMode.step(SurroundMode.Automatic, -1))
        assertEquals(SurroundMode.Automatic, SurroundMode.step(SurroundMode.Always, 1))
    }

    @Test
    fun `time zones list regions only`() {
        val ids = listOf("Europe/Berlin", "UTC", "Etc/GMT+1", "US/Pacific", "America/Argentina/Buenos_Aires", "GMT", "Asia/Tokyo")
        assertEquals(listOf("America/Argentina/Buenos_Aires", "Asia/Tokyo", "Europe/Berlin"), TimeZones.listable(ids))
        assertEquals("Buenos Aires", TimeZones.city("America/Argentina/Buenos_Aires"))
        assertEquals("Europe", TimeZones.region("Europe/Berlin"))
    }
}
