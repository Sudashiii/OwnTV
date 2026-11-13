package dev.owntv.launcher.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsNavigationTest {
    private fun nav(vararg screens: Screen) = Navigator().apply { screens.forEach(::push) }

    @Test
    fun `a pane screen opened from Settings shows next to the sidebar`() {
        assertEquals(Screen.Updates, nav(Screen.Settings, Screen.Updates).settingsDetail())
        assertEquals(Screen.Help, nav(Screen.Settings, Screen.Import, Screen.Help).settingsDetail())
    }

    @Test
    fun `Settings itself, full-screen screens and screens opened elsewhere have no pane`() {
        assertNull(nav(Screen.Settings).settingsDetail())
        assertNull(nav(Screen.Settings, Screen.Editor()).settingsDetail())
        assertNull(nav(Screen.Settings, Screen.Looks, Screen.LookPreview("roku.json")).settingsDetail())
        assertNull(nav(Screen.HiddenApps).settingsDetail())
    }

    @Test
    fun `Back closes the pane screen one step at a time`() {
        val nav = nav(Screen.Settings, Screen.Import, Screen.Help)
        nav.back()
        assertEquals(Screen.Import, nav.settingsDetail())
        nav.back()
        assertEquals(Screen.Settings, nav.current)
        assertNull(nav.settingsDetail())
    }

    @Test
    fun `switching groups closes every pane screen`() {
        val nav = nav(Screen.Settings, Screen.Import, Screen.Help)
        nav.closeSettingsDetail()
        assertEquals(Screen.Settings, nav.current)
        // Under a full-screen screen nothing is closed.
        val editing = nav(Screen.Settings, Screen.Editor())
        editing.closeSettingsDetail()
        assertEquals(Screen.Editor(), editing.current)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("45 seconds", duration(45))
        assertEquals("1 minute", duration(60))
        assertEquals("5 minutes", duration(300))
        assertEquals("1 minute 30 seconds", duration(90))
        assertEquals("1 hour", duration(3600))
        assertEquals("2 hours 15 minutes", duration(8100))
    }
}
