package dev.owntv.launcher.quick

/** Brightness of the picture in percent. OwnTV cannot change the TV's backlight, so it dims with a black layer. */
object BrightnessSteps {
    const val MIN = 10
    const val MAX = 100
    const val STEP = 10

    /** The level one remote press in [direction] (-1 or +1) away from [level]. */
    fun step(level: Int, direction: Int): Int = (level + direction * STEP).coerceIn(MIN, MAX)

    /** Opacity of the black layer for [level]: 0 at full brightness. */
    fun dimAlpha(level: Int): Float = 1f - level.coerceIn(MIN, MAX) / 100f
}

/** The choices of the sleep timer, in minutes (0 is off). */
object SleepSteps {
    val OPTIONS = listOf(0, 15, 30, 45, 60, 90, 120)

    /**
     * The timer's next length in minutes when the remote moves [direction] from a timer with [remainingMinutes]
     * left (null when it is off). Right picks the next longer choice, left the next shorter one or off.
     */
    fun step(remainingMinutes: Int?, direction: Int): Int {
        val current = remainingMinutes ?: 0
        return if (direction > 0) OPTIONS.firstOrNull { it > current } ?: OPTIONS.last()
        else OPTIONS.lastOrNull { it < current } ?: 0
    }

    /** Whole minutes left, rounded up, so a running timer never shows 0. */
    fun remainingMinutes(remainingMs: Long): Int = ((remainingMs + 59_999) / 60_000).toInt().coerceAtLeast(1)

    fun format(remainingMinutes: Int?): String = when {
        remainingMinutes == null -> "Off"
        remainingMinutes < 60 -> "$remainingMinutes min"
        remainingMinutes % 60 == 0 -> "${remainingMinutes / 60} h"
        else -> "${remainingMinutes / 60} h ${remainingMinutes % 60} min"
    }
}

/** Media volume steps: one press moves about 5 % of the range, at least one step. */
object VolumeSteps {
    fun step(volume: Int, max: Int, direction: Int): Int {
        val size = (max / 20).coerceAtLeast(1)
        return (volume + direction * size).coerceIn(0, max)
    }

    fun percent(volume: Int, max: Int): Int = if (max <= 0) 0 else (volume * 100 + max / 2) / max
}
