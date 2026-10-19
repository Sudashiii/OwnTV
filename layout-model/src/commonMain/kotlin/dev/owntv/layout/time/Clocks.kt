package dev.owntv.layout.time

/** A local date and time, split into the fields clock patterns need. [dayOfWeek] runs from 1 (Monday) to 7 (Sunday). */
data class WallClock(
    val year: Int,
    val month: Int,
    val day: Int,
    val dayOfWeek: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * The current time and clock patterns in java.time syntax ("HH:mm", "EEEE, d MMMM"). The JVM uses
 * java.time with the device locale; the web preview uses [TimePattern].
 */
expect object Clocks {
    fun nowMillis(): Long

    fun now(): WallClock

    /** [time] formatted with [pattern]; null when the pattern is invalid. */
    fun format(pattern: String, time: WallClock): String?

    /** Why [pattern] is invalid, or null when it is fine. */
    fun patternError(pattern: String): String?
}
