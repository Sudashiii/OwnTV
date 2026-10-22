package dev.owntv.launcher.actions

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The button the user pressed last, shown by the Remote buttons screen. */
data class PressedKey(val keyCode: Int, val name: String, val remappable: Boolean, val count: Int)

/**
 * Feeds the "press a button" test on the Remote buttons screen. Key presses are only looked at while that
 * screen is open ([listening]) and only the last one is kept in memory: nothing is logged or stored.
 */
object KeyProbe {
    @Volatile
    var listening = false

    private val lastKey = MutableStateFlow<PressedKey?>(null)
    val last: StateFlow<PressedKey?> = lastKey.asStateFlow()

    /** [event] is a key-down that reached OwnTV or its remote service. */
    fun report(event: KeyEvent) {
        if (!listening || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val count = (lastKey.value?.takeIf { it.keyCode == event.keyCode }?.count ?: 0) + 1
        lastKey.value = PressedKey(event.keyCode, keyName(event.keyCode), isRemappable(event.keyCode), count)
    }

    fun clear() {
        lastKey.value = null
    }
}
