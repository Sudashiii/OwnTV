package dev.owntv.launcher.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class IntentHandlersTest {
    private val stub = "com.android.tv.frameworkpackagestubs"

    @Test
    fun `intents without stubs are left to the system`() {
        assertEquals(IntentHandlers.Choice.System, IntentHandlers.choose(emptyList()))
        assertEquals(IntentHandlers.Choice.System, IntentHandlers.choose(listOf("com.android.tv.settings", "com.example")))
    }

    @Test
    fun `an intent only a stub answers is unsupported`() {
        assertEquals(IntentHandlers.Choice.Unsupported, IntentHandlers.choose(listOf(stub)))
    }

    @Test
    fun `a real handler wins over a stub`() {
        assertEquals(IntentHandlers.Choice.Use(1), IntentHandlers.choose(listOf(stub, "com.android.tv.settings")))
        assertEquals(IntentHandlers.Choice.Use(0), IntentHandlers.choose(listOf("com.android.tv.settings", stub)))
    }
}
