package dev.owntv.launcher.actions

/**
 * Google TV answers intents it does not support with placeholder activities ("framework package stubs") that
 * only say no app can do this. Starting such an intent "works", so OwnTV's own fallbacks never ran. Pure:
 * takes the packages of the activities that answer an intent, best match first.
 */
object IntentHandlers {
    val STUB_PACKAGES = setOf("com.android.tv.frameworkpackagestubs", "com.google.android.tv.frameworkpackagestubs")

    sealed interface Choice {
        /** Let the system pick, as before (no stub involved, or OwnTV cannot see the handlers). */
        data object System : Choice

        /** Only placeholders answer: treat the intent as unsupported. */
        data object Unsupported : Choice

        /** Start the handler at [index]; a stub would otherwise win or ask. */
        data class Use(val index: Int) : Choice
    }

    fun choose(packages: List<String>): Choice {
        if (packages.none { it in STUB_PACKAGES }) return Choice.System
        val real = packages.indexOfFirst { it !in STUB_PACKAGES }
        return if (real < 0) Choice.Unsupported else Choice.Use(real)
    }
}
