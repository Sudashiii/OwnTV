package dev.owntv.launcher.data.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundFilterTest {
    private val withoutEntry = setOf("com.example.service")

    private fun filter() = ForegroundFilter(
        ownPackage = "dev.owntv.launcher",
        isInputMethod = { it == "com.example.keyboard" },
        hasLauncherEntry = { it !in withoutEntry },
    )

    @Test
    fun `counts an app with a launcher entry`() {
        assertEquals("com.netflix.ninja", filter().accept("com.netflix.ninja"))
    }

    @Test
    fun `ignores empty events, OwnTV, system dialogs, keyboards and packages without a launcher entry`() {
        val filter = filter()
        assertNull(filter.accept(null))
        assertNull(filter.accept(""))
        assertNull(filter.accept("dev.owntv.launcher"))
        assertNull(filter.accept("com.android.systemui"))
        assertNull(filter.accept("com.google.android.permissioncontroller"))
        assertNull(filter.accept("com.example.keyboard"))
        assertNull(filter.accept("com.example.service"))
    }

    @Test
    fun `ignores repeats of the last app`() {
        val filter = filter()
        assertEquals("com.a", filter.accept("com.a"))
        assertNull(filter.accept("com.a"))
        assertEquals("com.b", filter.accept("com.b"))
        assertEquals("com.a", filter.accept("com.a"))
    }

    @Test
    fun `a dialog on top of an app does not make the app count twice`() {
        val filter = filter()
        assertEquals("com.a", filter.accept("com.a"))
        assertNull(filter.accept("com.android.systemui"))
        assertNull(filter.accept("com.a"))
    }

    @Test
    fun `opening the same app again after visiting OwnTV counts again`() {
        val filter = filter()
        assertEquals("com.a", filter.accept("com.a"))
        assertNull(filter.accept("dev.owntv.launcher"))
        assertEquals("com.a", filter.accept("com.a"))
    }
}
