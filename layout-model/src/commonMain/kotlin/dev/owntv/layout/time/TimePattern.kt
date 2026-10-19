package dev.owntv.layout.time

/**
 * Formats java.time patterns without java.time, for the web preview. It knows the letters clocks
 * use (y u M L d E H k h K m s a) with English names. Other java.time letters are kept as written.
 */
object TimePattern {
    private val javaTimeLetters = "GuyDMLdQqYwWEecFaBhKkHmsSAnNVvzOXxZ".toSet()
    private val maxCounts = mapOf(
        'M' to 5, 'L' to 5, 'E' to 5, 'a' to 1,
        'd' to 2, 'H' to 2, 'k' to 2, 'h' to 2, 'K' to 2, 'm' to 2, 's' to 2,
    )
    private val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private sealed interface Part {
        data class Literal(val text: String) : Part

        data class Field(val letter: Char, val count: Int) : Part
    }

    private class PatternException(message: String) : IllegalArgumentException(message)

    /** Why [pattern] is invalid, or null when it is fine. */
    fun error(pattern: String): String? = try {
        parse(pattern)
        null
    } catch (e: PatternException) {
        e.message
    }

    /** [time] formatted with [pattern]; null when the pattern is invalid. */
    fun format(pattern: String, time: WallClock): String? {
        val parts = try {
            parse(pattern)
        } catch (_: PatternException) {
            return null
        }
        return buildString {
            parts.forEach { part ->
                when (part) {
                    is Part.Literal -> append(part.text)
                    is Part.Field -> append(field(part.letter, part.count, time))
                }
            }
        }
    }

    private fun field(letter: Char, count: Int, t: WallClock): String = when (letter) {
        'y', 'u' -> if (count == 2) pad(t.year % 100, 2) else pad(t.year, count)
        'M', 'L' -> when (count) {
            1, 2 -> pad(t.month, count)
            3 -> months[t.month - 1].take(3)
            4 -> months[t.month - 1]
            else -> months[t.month - 1].take(1)
        }
        'd' -> pad(t.day, count)
        'E' -> when (count) {
            4 -> days[t.dayOfWeek - 1]
            5 -> days[t.dayOfWeek - 1].take(1)
            else -> days[t.dayOfWeek - 1].take(3)
        }
        'H' -> pad(t.hour, count)
        'k' -> pad(if (t.hour == 0) 24 else t.hour, count)
        'h' -> pad((t.hour % 12).let { if (it == 0) 12 else it }, count)
        'K' -> pad(t.hour % 12, count)
        'm' -> pad(t.minute, count)
        's' -> pad(t.second, count)
        'a' -> if (t.hour < 12) "AM" else "PM"
        else -> letter.toString().repeat(count)
    }

    private fun pad(value: Int, count: Int) = value.toString().padStart(count, '0')

    private fun parse(pattern: String): List<Part> {
        val parts = mutableListOf<Part>()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> i = quoted(pattern, i, parts)
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    var end = i
                    while (end < pattern.length && pattern[end] == c) end++
                    val count = end - i
                    if (c !in javaTimeLetters) throw PatternException("Unknown pattern letter: $c")
                    if (count > (maxCounts[c] ?: Int.MAX_VALUE)) throw PatternException("Too many pattern letters: $c")
                    parts += Part.Field(c, count)
                    i = end
                }
                c == '#' || c == '{' || c == '}' -> throw PatternException("Pattern includes reserved character: '$c'")
                // Optional sections: everything inside is printed.
                c == '[' || c == ']' -> i++
                else -> {
                    parts += Part.Literal(c.toString())
                    i++
                }
            }
        }
        return parts
    }

    /** Reads a quoted literal starting at [start]; `''` is a single quote. Returns the index after it. */
    private fun quoted(pattern: String, start: Int, parts: MutableList<Part>): Int {
        if (pattern.getOrNull(start + 1) == '\'') {
            parts += Part.Literal("'")
            return start + 2
        }
        val text = StringBuilder()
        var i = start + 1
        while (true) {
            if (i >= pattern.length) throw PatternException("Pattern ends with an incomplete string literal: $pattern")
            if (pattern[i] == '\'') {
                if (pattern.getOrNull(i + 1) == '\'') {
                    text.append('\'')
                    i += 2
                    continue
                }
                parts += Part.Literal(text.toString())
                return i + 1
            }
            text.append(pattern[i])
            i++
        }
    }
}
