package dev.owntv.launcher.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.ZoneId

/**
 * Night light: a warm layer and extra darkening over the picture, on a schedule (custom times or sunset to
 * sunrise), like Twilight. [filter] is what the remote service and [DimLayer] draw; it also carries the
 * quick settings brightness ([ScreenDimmer] keeps the day level).
 *
 * Changes made by hand at night ([setBrightness], [setIntensity], [toggle]) hold until the night ends, see
 * [NightLightOverride]. The clock is read again on every check, as delays stop while the TV sleeps.
 */
class NightLight(
    context: Context,
    private val scope: CoroutineScope,
    private val dimmer: ScreenDimmer,
    val location: SunLocation,
) {
    private val prefs = context.getSharedPreferences("quick_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _config = MutableStateFlow(load(KEY_CONFIG, NightLightConfig.serializer())?.normalized() ?: NightLightConfig())
    private val _override = MutableStateFlow(load(KEY_OVERRIDE, NightLightOverride.serializer()))
    private val _filter = MutableStateFlow(ScreenFilter.NONE)
    private var tick: Job? = null

    val config: StateFlow<NightLightConfig> = _config.asStateFlow()
    val filter: StateFlow<ScreenFilter> = _filter.asStateFlow()

    init {
        refresh()
        scope.launch { combine(_config, _override, dimmer.level, location.place) { _, _, _, _ -> }.collect { refresh() } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) location.refresh()
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Changes the settings. A new schedule ends tonight's hand-made changes; new values replace the ones they fix. */
    fun update(transform: (NightLightConfig) -> NightLightConfig) {
        val old = _config.value
        val new = transform(old).normalized()
        if (new == old) return
        _config.value = new
        save(KEY_CONFIG, NightLightConfig.serializer(), new)
        val override = _override.value ?: return
        when {
            new.mode != old.mode || new.startMinute != old.startMinute || new.endMinute != old.endMinute -> setOverride(null)
            new.intensity != old.intensity && override.intensity != null -> setOverride(override.copy(intensity = null))
            new.darkening != old.darkening && override.brightness != null -> setOverride(override.copy(brightness = null))
        }
    }

    /** Switches night light on or off by hand, until the night ends (on) or the next night starts (off). */
    fun toggle() = change { now, zone, override, place, filter ->
        NightLightLogic.toggle(now, zone, _config.value, override, place, filter)
    }

    /** The tint strength from quick settings: kept until night light switches off; switches it on if needed. */
    fun setIntensity(intensity: Int) = change { now, zone, override, place, filter ->
        NightLightLogic.withIntensity(now, zone, _config.value, override, place, filter, intensity)
    }

    /** The quick settings brightness: at night it holds until the night ends, by day it is the day brightness. */
    fun setBrightness(level: Int) {
        val now = System.currentTimeMillis()
        val override = NightLightLogic.withBrightness(now, ZoneId.systemDefault(), _config.value, _override.value, location.place.value?.point, current(now), level)
        if (override == null) dimmer.set(level) else setOverride(override)
    }

    /** Back to the schedule. */
    fun clearOverride() = setOverride(null)

    /** True while a hand-made change holds. */
    val overridden: Boolean get() = NightLightLogic.active(_override.value, System.currentTimeMillis()) != null

    fun refresh() {
        val now = System.currentTimeMillis()
        if (_override.value != null && NightLightLogic.active(_override.value, now) == null) setOverride(null)
        val filter = current(now)
        _filter.value = filter
        tick?.cancel()
        tick = scope.launch {
            delay((filter.nextCheck - now).coerceIn(1_000, 60_000))
            refresh()
        }
    }

    private fun current(now: Long) =
        NightLightLogic.evaluate(now, ZoneId.systemDefault(), _config.value, _override.value, dimmer.level.value, location.place.value?.point)

    private inline fun change(block: (Long, ZoneId, NightLightOverride?, GeoPoint?, ScreenFilter) -> NightLightOverride?) {
        val now = System.currentTimeMillis()
        setOverride(block(now, ZoneId.systemDefault(), _override.value, location.place.value?.point, current(now)))
    }

    private fun setOverride(override: NightLightOverride?) {
        _override.value = override
        if (override == null) prefs.edit().remove(KEY_OVERRIDE).apply() else save(KEY_OVERRIDE, NightLightOverride.serializer(), override)
        refresh()
    }

    private fun <T> load(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> save(key: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }

    private companion object {
        const val KEY_CONFIG = "night_light"
        const val KEY_OVERRIDE = "night_light_override"
    }
}
