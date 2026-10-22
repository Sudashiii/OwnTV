package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.WatchNext
import dev.owntv.layout.model.WatchNextEntry
import dev.owntv.layout.model.WatchNextRowNode
import dev.owntv.layout.model.containsFocusable
import dev.owntv.layout.validation.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchNextTest {
    private fun entry(
        id: String,
        pkg: String = "a.app",
        engaged: Long = 0,
        duration: Long = 0,
        position: Long = 0,
    ) = WatchNextEntry(id, pkg, "Title $id", null, duration, position, null, engaged)

    @Test
    fun `entries are sorted by last engagement, deduplicated, and cut by exclude, skip and limit`() {
        val entries = listOf(
            entry("old", engaged = 1),
            entry("new", engaged = 9),
            entry("other", pkg = "b.app", engaged = 5),
            entry("new", engaged = 9),
            entry("mid", engaged = 4),
        )
        assertEquals(listOf("new", "other", "mid", "old"), WatchNext.select(entries).map { it.id })
        assertEquals(listOf("new", "mid", "old"), WatchNext.select(entries, exclude = listOf("b.app")).map { it.id })
        assertEquals(listOf("other", "mid"), WatchNext.select(entries, skip = 1, limit = 2).map { it.id })
        assertEquals(emptyList(), WatchNext.select(entries, skip = 10))
    }

    @Test
    fun `progress and time left need both a duration and a position`() {
        val half = entry("a", duration = 100_000, position = 50_000)
        assertEquals(0.5f, half.progress)
        assertEquals(50_000L, half.remainingMillis)
        assertNull(entry("b", duration = 100_000).progress)
        assertNull(entry("c", position = 5_000).progress)
        // Past the end the bar is full and nothing is left.
        val over = entry("d", duration = 100_000, position = 120_000)
        assertEquals(1f, over.progress)
        assertNull(over.remainingMillis)
    }

    @Test
    fun `durations are written in hours and minutes, rounded up`() {
        assertEquals("2h 18min", WatchNext.durationText((2 * 60 + 18) * 60_000L))
        assertEquals("1h", WatchNext.durationText(60 * 60_000L))
        assertEquals("45min", WatchNext.durationText(45 * 60_000L))
        assertEquals("1min", WatchNext.durationText(1))
        assertEquals("1h 1min", WatchNext.durationText(60 * 60_000L + 1))
    }

    @Test
    fun `the focused description names the app and the time left`() {
        val e = entry("a", duration = 138 * 60_000L, position = 0)
        assertEquals("Nuvio", WatchNext.description(e, "Nuvio"))
        val watched = entry("a", duration = 138 * 60_000L, position = 60 * 60_000L)
        assertEquals("Nuvio · 1h 18min left", WatchNext.description(watched, "Nuvio"))
        assertEquals("1h 18min left", WatchNext.description(watched, null))
        assertEquals("", WatchNext.description(e, null))
    }

    @Test
    fun `a watch_next_row decodes, counts as focusable and is checked`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """{ "type": "watch_next_row", "id": "continue", "title": "Continue", "titleIcon": "play", "limit": 6, "exclude": ["a.b"], "showProgress": false }""",
            ),
        )
        val row = assertIs<WatchNextRowNode>(result.requireDocument().pages[0].root)
        assertEquals(6, row.limit)
        assertEquals(listOf("a.b"), row.exclude)
        assertEquals(false, row.showProgress)
        assertTrue(row.containsFocusable())
        assertTrue(result.issues.none { it.severity == Severity.Error }, result.issues.joinToString("\n"))

        val badIcon = LayoutCodec.decode(docWithRoot("""{ "type": "watch_next_row", "id": "continue", "titleIcon": "no_such_icon" }"""))
        assertEquals(listOf("$.pages[0].root.titleIcon"), badIcon.paths(Severity.Warning))
    }
}
