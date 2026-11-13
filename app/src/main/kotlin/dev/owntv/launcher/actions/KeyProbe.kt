package dev.owntv.launcher.actions

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The button the user pressed last, shown by the Remote buttons screen. */
data class PressedKey(val keyCode: Int, val name: String, val count: Int)

/**
 * Feeds the "press a button" test on the Remote buttons screen. While the screen is active, bindable
 * buttons are consumed so they cannot launch their original action. Navigation buttons pass through.
 */
object KeyProbe {
    @Volatile
    var listening = false

    private val lastKey = MutableStateFlow<PressedKey?>(null)
    val last: StateFlow<PressedKey?> = lastKey.asStateFlow()
    private val seenKeys = MutableStateFlow<Set<Int>>(emptySet())
    val seen: StateFlow<Set<Int>> = seenKeys.asStateFlow()

    /** Returns true when this event should be kept from the TV and other apps. */
    fun handle(event: KeyEvent): Boolean = handle(event.keyCode, event.action, event.repeatCount)

    internal fun handle(keyCode: Int, action: Int, repeatCount: Int, nameOf: (Int) -> String = ::keyName): Boolean {
        if (!listening || !isRemappable(keyCode)) return false
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            seenKeys.value = seenKeys.value + keyCode
            val count = (lastKey.value?.takeIf { it.keyCode == keyCode }?.count ?: 0) + 1
            lastKey.value = PressedKey(keyCode, nameOf(keyCode), count)
        }
        return action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP
    }

    fun clear() {
        lastKey.value = null
        seenKeys.value = emptySet()
    }
}
