package dev.owntv.launcher.actions

import android.view.KeyEvent
import dev.owntv.layout.model.KeyBinding
import dev.owntv.layout.model.KeyPress
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.RemoteKey
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.NextWallpaperAction
import dev.owntv.layout.model.StandbyAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyBindingMatcherTest {
    private val red = KeyEvent.KEYCODE_PROG_RED
    private val blue = KeyEvent.KEYCODE_PROG_BLUE
    private val launch = LaunchAppAction("com.example.app")
    private val fire = KeyBindingMatcher.Result::Fire

    private fun matcher(vararg bindings: KeyBinding) = KeyBindingMatcher(bindings.toList())

    private fun KeyBindingMatcher.down(key: Int, at: Long = 0, repeat: Int = 0) = onKey(key, down = true, repeatCount = repeat, timeMs = at)
    private fun KeyBindingMatcher.up(key: Int, at: Long = 0) = onKey(key, down = false, repeatCount = 0, timeMs = at)

    @Test
    fun `every remote key has its own key code`() {
        val codes = RemoteKey.entries.map { it.keyCode() }
        assertEquals(RemoteKey.entries.size, codes.toSet().size, "two keys share a code")
        assertTrue(codes.none { it == KeyEvent.KEYCODE_UNKNOWN })
        val reserved = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        )
        assertTrue(codes.none { it in reserved }, "D-pad, OK, Back, Home and Power cannot be remapped")
    }

    @Test
    fun `key names follow the layout`() {
        assertEquals("red", keyName(red))
        assertEquals("channel_up", keyName(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals("tv_input", keyName(KeyEvent.KEYCODE_TV_INPUT))
    }

    @Test
    fun `unbound keys pass through`() {
        val m = matcher(KeyBinding(RemoteKey.Red, KeyPress.Short, launch))
        assertEquals(KeyBindingMatcher.Result.PassThrough, m.down(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(KeyBindingMatcher.Result.PassThrough, m.up(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(KeyBindingMatcher.Result.PassThrough, matcher().down(red))
    }

    @Test
    fun `a short press fires on key-up and takes both events`() {
        val m = matcher(KeyBinding(RemoteKey.Red, KeyPress.Short, launch))
        assertEquals(KeyBindingMatcher.Result.Consume, m.down(red))
        assertEquals(fire(launch), m.up(red, at = 100))
        assertNull(m.longPressDelayMs(red))
    }

    @Test
    fun `a short press held for long still fires once on key-up when there is no long binding`() {
        val m = matcher(KeyBinding(RemoteKey.Red, KeyPress.Short, launch))
        m.down(red)
        assertEquals(KeyBindingMatcher.Result.Consume, m.down(red, at = 800, repeat = 5))
        assertEquals(fire(launch), m.up(red, at = 900))
    }

    @Test
    fun `a long press fires on the timeout and swallows the key-up`() {
        val long = OpenAppDrawerAction
        val m = matcher(KeyBinding(RemoteKey.Blue, KeyPress.Long, long))
        assertEquals(KeyBindingMatcher.Result.Consume, m.down(blue))
        assertEquals(KeyBindingMatcher.LONG_PRESS_MS, m.longPressDelayMs(blue))
        assertEquals(fire(long), m.onLongPressTimeout(blue))
        assertNull(m.onLongPressTimeout(blue), "fires once")
        assertEquals(KeyBindingMatcher.Result.Consume, m.up(blue, at = 1500))
    }

    @Test
    fun `a long press also fires from a key repeat when the timer is late`() {
        val m = matcher(KeyBinding(RemoteKey.Blue, KeyPress.Long, OpenLauncherSettingsAction))
        m.down(blue, at = 1000)
        assertEquals(KeyBindingMatcher.Result.Consume, m.down(blue, at = 1300, repeat = 1))
        assertEquals(fire(OpenLauncherSettingsAction), m.down(blue, at = 1500, repeat = 2))
        assertEquals(KeyBindingMatcher.Result.Consume, m.down(blue, at = 1600, repeat = 3))
        assertNull(m.onLongPressTimeout(blue))
        assertEquals(KeyBindingMatcher.Result.Consume, m.up(blue, at = 1700))
    }

    @Test
    fun `a key with both bindings runs the short one when released early and the long one when held`() {
        val m = matcher(
            KeyBinding(RemoteKey.Red, KeyPress.Short, launch),
            KeyBinding(RemoteKey.Red, KeyPress.Long, StandbyAction),
        )
        m.down(red)
        assertEquals(fire(launch), m.up(red, at = 200))
        assertNull(m.onLongPressTimeout(red), "released before the timeout: nothing to fire")

        m.down(red, at = 1000)
        assertEquals(fire(StandbyAction), m.onLongPressTimeout(red))
        assertEquals(KeyBindingMatcher.Result.Consume, m.up(red, at = 1800))
    }

    @Test
    fun `a key with only a long binding does nothing on a short press`() {
        val m = matcher(KeyBinding(RemoteKey.Blue, KeyPress.Long, StandbyAction))
        m.down(blue)
        assertEquals(KeyBindingMatcher.Result.Consume, m.up(blue, at = 100))
    }

    @Test
    fun `the first binding of a key and press wins`() {
        val m = matcher(
            KeyBinding(RemoteKey.Red, KeyPress.Short, launch),
            KeyBinding(RemoteKey.Red, KeyPress.Short, NextWallpaperAction),
        )
        m.down(red)
        assertEquals(fire(launch), m.up(red))
    }

    @Test
    fun `a lost key-up does not leave the next press stuck`() {
        val m = matcher(
            KeyBinding(RemoteKey.Red, KeyPress.Short, launch),
            KeyBinding(RemoteKey.Red, KeyPress.Long, GoToPageAction("night")),
        )
        m.down(red, at = 0)
        m.onLongPressTimeout(red)
        // The release never arrives; a new press starts over.
        m.down(red, at = 5000)
        assertEquals(fire(launch), m.up(red, at = 5100))
    }

    @Test
    fun `a stray key-up of a bound key is swallowed and a timeout without a press does nothing`() {
        val m = matcher(KeyBinding(RemoteKey.Blue, KeyPress.Long, StandbyAction))
        assertEquals(KeyBindingMatcher.Result.Consume, m.up(blue))
        assertNull(m.onLongPressTimeout(blue))
        assertFalse(m.isBound(red))
        assertTrue(m.isBound(blue))
        assertTrue(matcher().isEmpty)
    }

    @Test
    fun `only actions that need OwnTV in front run there`() {
        assertTrue(GoToPageAction("a").runsInOwnTv())
        assertTrue(OpenAppDrawerAction.runsInOwnTv())
        assertTrue(OpenLauncherSettingsAction.runsInOwnTv())
        assertTrue(StandbyAction.runsInOwnTv())
        assertTrue(NextWallpaperAction.runsInOwnTv())
        assertFalse(launch.runsInOwnTv())
    }
}
