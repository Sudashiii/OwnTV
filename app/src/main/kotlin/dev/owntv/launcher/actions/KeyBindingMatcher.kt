package dev.owntv.launcher.actions

import dev.owntv.layout.model.Action
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.KeyBinding
import dev.owntv.layout.model.KeyPress
import dev.owntv.layout.model.NextWallpaperAction
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.StandbyAction

/** Actions OwnTV's own screen carries out (they need OwnTV in front); the others leave the launcher. */
fun Action.runsInOwnTv(): Boolean =
    this is GoToPageAction || this == OpenAppDrawerAction || this == OpenLauncherSettingsAction || this == StandbyAction || this == NextWallpaperAction

/**
 * Turns key events into the actions of a layout's `keyBindings`. Pure: no Android types, no clock, no timers.
 * Short presses fire on key-up, long presses once the key has been held for [LONG_PRESS_MS], either from
 * [onLongPressTimeout] or from a key repeat, whichever comes first; the following key-up is then swallowed.
 * Only bound keys are consumed. Bindings come first-wins per key and press.
 */
class KeyBindingMatcher(bindings: List<KeyBinding>, keyCodeOf: (dev.owntv.layout.model.RemoteKey) -> Int = { it.keyCode() }) {
    sealed interface Result {
        /** Not a bound key: leave the event to the system and the apps. */
        data object PassThrough : Result

        /** A bound key: take the event, nothing to run yet. */
        data object Consume : Result

        /** Take the event and run [action]. */
        data class Fire(val action: Action) : Result
    }

    private class Entry(val short: Action?, val long: Action?)

    private class Press(val downAt: Long, var longFired: Boolean = false)

    private val entries: Map<Int, Entry> = bindings.groupBy { keyCodeOf(it.key) }.mapValues { (_, group) ->
        Entry(
            short = group.firstOrNull { it.press == KeyPress.Short }?.action,
            long = group.firstOrNull { it.press == KeyPress.Long }?.action,
        )
    }
    private val presses = mutableMapOf<Int, Press>()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun isBound(keyCode: Int): Boolean = keyCode in entries

    /** Key codes with a binding, for showing which buttons are taken over. */
    val boundKeyCodes: Set<Int> get() = entries.keys

    /** How long to wait before [onLongPressTimeout] for a key that was just pressed, or null if it has no long binding. */
    fun longPressDelayMs(keyCode: Int): Long? = if (entries[keyCode]?.long != null) LONG_PRESS_MS else null

    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, timeMs: Long): Result {
        val entry = entries[keyCode] ?: return Result.PassThrough
        if (!down) {
            val press = presses.remove(keyCode)
            return when {
                press == null || press.longFired -> Result.Consume
                entry.short != null -> Result.Fire(entry.short)
                else -> Result.Consume
            }
        }
        val press = presses[keyCode]
        if (repeatCount == 0 || press == null) {
            presses[keyCode] = Press(timeMs)
            return Result.Consume
        }
        if (!press.longFired && entry.long != null && timeMs - press.downAt >= LONG_PRESS_MS) {
            press.longFired = true
            return Result.Fire(entry.long)
        }
        return Result.Consume
    }

    /** The long press of [keyCode] is due. Fires once, and only while the key is still held. */
    fun onLongPressTimeout(keyCode: Int): Result.Fire? {
        val press = presses[keyCode] ?: return null
        val long = entries[keyCode]?.long ?: return null
        if (press.longFired) return null
        press.longFired = true
        return Result.Fire(long)
    }

    companion object {
        const val LONG_PRESS_MS = 500L
    }
}
