package dev.owntv.launcher.quick

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The quick settings brightness. TV boxes cannot change the TV's backlight, so OwnTV lays a black layer over
 * the picture: the remote service draws it over every app (an accessibility overlay), and without the
 * service OwnTV's own screens draw it ([DimLayer]). The level survives restarts.
 */
class ScreenDimmer(context: Context) {
    private val prefs = context.getSharedPreferences("quick_settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(prefs.getInt(KEY_LEVEL, BrightnessSteps.MAX).coerceIn(BrightnessSteps.MIN, BrightnessSteps.MAX))

    /** Brightness in percent, [BrightnessSteps.MIN] to [BrightnessSteps.MAX]. */
    val level: StateFlow<Int> = current.asStateFlow()

    fun set(level: Int) {
        val value = level.coerceIn(BrightnessSteps.MIN, BrightnessSteps.MAX)
        current.value = value
        prefs.edit().putInt(KEY_LEVEL, value).apply()
    }

    private companion object {
        const val KEY_LEVEL = "brightness"
    }
}
