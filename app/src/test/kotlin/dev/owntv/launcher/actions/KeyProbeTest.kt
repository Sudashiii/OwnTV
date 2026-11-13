package dev.owntv.launcher.actions

import android.view.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyProbeTest {
    @AfterTest
    fun reset() {
        KeyProbe.listening = false
        KeyProbe.clear()
    }

    @Test
    fun `capture consumes both halves and reports only fresh remappable presses`() {
        fun event(code: Int, action: Int, repeat: Int = 0) = KeyProbe.handle(code, action, repeat) { "key-$it" }
        val eight = KeyEvent.KEYCODE_8
        assertFalse(event(eight, KeyEvent.ACTION_DOWN))
        assertNull(KeyProbe.last.value)

        KeyProbe.listening = true
        assertTrue(event(eight, KeyEvent.ACTION_DOWN))
        assertEquals(PressedKey(eight, "key-$eight", 1), KeyProbe.last.value)
        assertTrue(event(eight, KeyEvent.ACTION_DOWN, repeat = 1))
        assertTrue(event(eight, KeyEvent.ACTION_UP))
        assertEquals(1, KeyProbe.last.value?.count)
        assertFalse(event(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN))
        assertFalse(event(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))
        assertFalse(event(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.ACTION_DOWN))
        assertEquals(eight, KeyProbe.last.value?.keyCode)
        assertTrue(event(KeyEvent.KEYCODE_9, KeyEvent.ACTION_DOWN))
        assertEquals(setOf(eight, KeyEvent.KEYCODE_9), KeyProbe.seen.value)

        assertTrue(event(eight, KeyEvent.ACTION_DOWN))
        assertEquals(1, KeyProbe.last.value?.count)
        assertTrue(event(eight, KeyEvent.ACTION_DOWN))
        assertEquals(2, KeyProbe.last.value?.count)
        KeyProbe.listening = false
        assertFalse(event(eight, KeyEvent.ACTION_UP))
    }
}
