package dev.owntv.layout.time

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

actual object Clocks {
    actual fun nowMillis(): Long = System.currentTimeMillis()

    actual fun now(): WallClock = LocalDateTime.now().let {
        WallClock(it.year, it.monthValue, it.dayOfMonth, it.dayOfWeek.value, it.hour, it.minute, it.second)
    }

    actual fun format(pattern: String, time: WallClock): String? = runCatching {
        LocalDateTime.of(time.year, time.month, time.day, time.hour, time.minute, time.second)
            .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }.getOrNull()

    actual fun patternError(pattern: String): String? =
        runCatching { DateTimeFormatter.ofPattern(pattern) }.exceptionOrNull()?.let { it.message ?: it.toString() }
}
