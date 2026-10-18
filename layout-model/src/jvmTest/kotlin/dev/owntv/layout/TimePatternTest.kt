package dev.owntv.layout

import dev.owntv.layout.time.TimePattern
import dev.owntv.layout.time.WallClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The web preview's [TimePattern] must print what java.time prints in English. */
class TimePatternTest {
    private val patterns = listOf(
        "HH:mm", "H:mm", "HH:mm:ss", "h:mm a", "hh:mm a", "k:mm", "K:mm a", "h a",
        "EEEE, d MMMM", "EEE d MMM yyyy", "EEEEE MMMMM", "dd.MM.yy", "d/M/y", "yyyy-MM-dd",
        "'Today is' EEEE", "'o''clock' H", "HH'h'mm", "MMMM d, yyyy", "LLLL", "[HH:mm]",
    )

    private val times = listOf(
        LocalDateTime.of(2026, 10, 18, 22, 6, 9),
        LocalDateTime.of(2026, 1, 5, 0, 0, 0),
        LocalDateTime.of(2027, 9, 1, 12, 30, 59),
        LocalDateTime.of(2028, 2, 29, 11, 59, 1),
    )

    @Test
    fun `formats like java time`() {
        for (time in times) {
            val wall = WallClock(time.year, time.monthValue, time.dayOfMonth, time.dayOfWeek.value, time.hour, time.minute, time.second)
            for (pattern in patterns) {
                val expected = time.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                assertEquals(expected, TimePattern.format(pattern, wall), "pattern \"$pattern\" at $time")
            }
        }
    }

    @Test
    fun `rejects what java time rejects`() {
        for (pattern in listOf("HH:mm 'open", "aa", "MMMMMM", "HHH", "jj", "#", "{x}")) {
            assertNotNull(TimePattern.error(pattern), "\"$pattern\" should be invalid")
            assertNotNull(runCatching { DateTimeFormatter.ofPattern(pattern) }.exceptionOrNull(), "java.time accepts \"$pattern\"")
        }
        for (pattern in patterns) assertNull(TimePattern.error(pattern), "\"$pattern\" should be valid")
    }
}
