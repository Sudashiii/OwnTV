package dev.owntv.launcher.quick

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** When night light switches on by itself. */
@Serializable
enum class NightMode {
    @SerialName("off") Off,
    @SerialName("custom") Custom,
    @SerialName("sun") Sun,
}

/**
 * Night light right now, as chosen in Settings: [UntilSchedule] is on until the schedule's next end (what
 * quick settings switches on), [On] stays on until switched off, whatever the schedule says.
 */
enum class NightState { Off, UntilSchedule, On }

/** Night light as set in Settings > Display > Night light. */
@Serializable
data class NightLightConfig(
    val mode: NightMode = NightMode.Off,
    /** Minutes after midnight. */
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val temperatureK: Int = 3400,
    /** Strength of the warm tint in percent. */
    val intensity: Int = 50,
    /** How much darker the picture gets at night, in percent of the day brightness. */
    val darkening: Int = 20,
    /** How long night light takes to fade in after the start and out before the end. */
    val fadeMinutes: Int = 30,
) {
    fun normalized() = copy(
        startMinute = NightSteps.minuteOfDay(startMinute),
        endMinute = NightSteps.minuteOfDay(endMinute),
        temperatureK = temperatureK.coerceIn(NightSteps.MIN_K, NightSteps.MAX_K),
        intensity = intensity.coerceIn(0, 100),
        darkening = darkening.coerceIn(0, NightSteps.MAX_DARKENING),
        fadeMinutes = fadeMinutes.coerceIn(0, NightSteps.MAX_FADE),
    )
}

/**
 * A change made by hand (quick settings or the Settings screen's state row, see [NightState]). It holds until [until], the end of
 * the night it was made in (or the next scheduled end when switched on by day), then the schedule takes over again.
 * While [forcedOn] is true, [intensity] and [brightness] are fixed values that no fade changes before [until].
 */
@Serializable
data class NightLightOverride(
    /** Epoch ms; [Long.MAX_VALUE] keeps it until it is switched off. */
    val until: Long,
    val forcedOn: Boolean,
    val intensity: Int? = null,
    val brightness: Int? = null,
)

data class GeoPoint(val lat: Double, val lon: Double)

/** One night: [start] to [end] in epoch ms. */
data class NightPeriod(val start: Long, val end: Long) {
    operator fun contains(now: Long) = now in start until end
}

/** What the screen shows now and why. */
data class ScreenFilter(
    /** Effective brightness in percent ([BrightnessSteps.MIN] to [BrightnessSteps.MAX]). */
    val brightness: Int,
    /** Color of the warm layer, 0xRRGGBB. */
    val tintRgb: Int,
    /** Opacity of the warm layer; 0 means no layer. */
    val tintAlpha: Float,
    /** Night light's tint strength in percent right now, after fading. */
    val intensity: Int,
    /** True while night light is on (also while it fades). */
    val on: Boolean,
    /** When it switches off (while [on]) or on (while off); null when nothing is planned. [Long.MAX_VALUE]: until switched off. */
    val nextSwitch: Long?,
    /** True while a change made by hand holds. */
    val overridden: Boolean,
    /** When to look again. */
    val nextCheck: Long,
) {
    companion object {
        val NONE = ScreenFilter(BrightnessSteps.MAX, 0, 0f, 0, false, null, false, Long.MAX_VALUE)
    }
}

object NightSteps {
    const val MIN_K = 1000
    const val MAX_K = 6000
    const val STEP_K = 100
    const val MAX_DARKENING = 90
    const val MAX_FADE = 60
    const val STEP_FADE = 5
    const val STEP_TIME = 5

    /** Opacity of the warm layer at 100 % intensity. More washes the picture out. */
    const val MAX_TINT_ALPHA = 0.5f

    fun minuteOfDay(minute: Int) = Math.floorMod(minute, 24 * 60)

    fun temperature(k: Int, direction: Int) = (k + direction * STEP_K).coerceIn(MIN_K, MAX_K)
    fun percent(value: Int, direction: Int, max: Int = 100) = (value + direction).coerceIn(0, max)
    fun fade(minutes: Int, direction: Int) = (minutes + direction * STEP_FADE).coerceIn(0, MAX_FADE)
    fun time(minute: Int, direction: Int) = minuteOfDay(minute + direction * STEP_TIME)
    fun mode(mode: NightMode, direction: Int): NightMode {
        val all = NightMode.entries
        return all[Math.floorMod(all.indexOf(mode) + direction, all.size)]
    }

    fun formatTime(minute: Int) = "%02d:%02d".format(minuteOfDay(minute) / 60, minuteOfDay(minute) % 60)
}

/** Sunrise and sunset with the sunrise equation (accurate to a minute or two outside the polar regions). */
object SunTimes {
    data class Day(val sunrise: Long, val sunset: Long)

    /** Sunrise and sunset on [date] at [point]; null when the sun does not rise or does not set that day. */
    fun compute(date: LocalDate, point: GeoPoint): Day? {
        val julianDay = date.toEpochDay() + 2440588.0
        val n = julianDay - 2451545.0 + 0.0008
        val meanSolarNoon = n - point.lon / 360.0
        val m = ((357.5291 + 0.98560028 * meanSolarNoon) % 360 + 360) % 360
        val mRad = m.rad()
        val center = 1.9148 * sin(mRad) + 0.02 * sin(2 * mRad) + 0.0003 * sin(3 * mRad)
        val lambda = ((m + center + 180 + 102.9372) % 360).rad()
        val transit = 2451545.0 + meanSolarNoon + 0.0053 * sin(mRad) - 0.0069 * sin(2 * lambda)
        val declination = asin(sin(lambda) * sin(23.4397.rad()))
        val lat = point.lat.rad()
        val cosHour = (sin((-0.833).rad()) - sin(lat) * sin(declination)) / (cos(lat) * cos(declination))
        if (abs(cosHour) > 1) return null
        val hour = acos(cosHour) * 180 / PI
        return Day(julianToEpochMs(transit - hour / 360), julianToEpochMs(transit + hour / 360))
    }

    private fun Double.rad() = this * PI / 180
    private fun julianToEpochMs(j: Double) = ((j - 2440587.5) * 86_400_000).toLong()
}

object NightSchedule {
    /**
     * The night [now] is in, else the next one; null when the schedule is off. Sunset to sunrise uses the custom
     * times on days without a sunset or sunrise, and when [location] is unknown.
     */
    fun period(now: Long, zone: ZoneId, config: NightLightConfig, location: GeoPoint?): NightPeriod? {
        if (config.mode == NightMode.Off) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (-1L..1L).asSequence()
            .mapNotNull { periodStartingOn(today.plusDays(it), zone, config, location) }
            .firstOrNull { it.end > now }
    }

    private fun periodStartingOn(date: LocalDate, zone: ZoneId, config: NightLightConfig, location: GeoPoint?): NightPeriod? {
        if (config.mode == NightMode.Sun && location != null) {
            val evening = SunTimes.compute(date, location)
            val morning = SunTimes.compute(date.plusDays(1), location)
            if (evening != null && morning != null && morning.sunrise > evening.sunset) return NightPeriod(evening.sunset, morning.sunrise)
        }
        if (config.startMinute == config.endMinute) return null
        val start = at(date, config.startMinute, zone)
        val end = at(if (config.endMinute > config.startMinute) date else date.plusDays(1), config.endMinute, zone)
        return NightPeriod(start, end)
    }

    private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
}

object NightLightLogic {
    private const val FADE_TICK_MS = 15_000L
    private const val MAX_TICK_MS = 60_000L

    /** The override that still holds at [now], or null. */
    fun active(override: NightLightOverride?, now: Long) = override?.takeIf { now < it.until }

    fun evaluate(
        now: Long,
        zone: ZoneId,
        config: NightLightConfig,
        override: NightLightOverride?,
        dayBrightness: Int,
        location: GeoPoint?,
    ): ScreenFilter {
        val day = dayBrightness.coerceIn(BrightnessSteps.MIN, BrightnessSteps.MAX)
        val manual = active(override, now)
        val period = NightSchedule.period(now, zone, config, location)
        val fadeMs = config.fadeMinutes * 60_000L
        val level: Float
        val intensity: Int
        val nightBrightness: Int
        val nextSwitch: Long?
        when {
            manual?.forcedOn == true -> {
                level = if (manual.until == Long.MAX_VALUE) 1f else ramp(manual.until - now, fadeMs)
                intensity = manual.intensity ?: config.intensity
                nightBrightness = manual.brightness ?: darkened(day, config.darkening)
                nextSwitch = manual.until
            }
            manual != null -> { // switched off by hand
                level = 0f
                intensity = config.intensity
                nightBrightness = day
                nextSwitch = NightSchedule.period(manual.until, zone, config, location)?.start
            }
            period != null && now in period -> {
                level = minOf(ramp(now - period.start, fadeMs), ramp(period.end - now, fadeMs))
                intensity = config.intensity
                nightBrightness = darkened(day, config.darkening)
                nextSwitch = period.end
            }
            else -> {
                level = 0f
                intensity = config.intensity
                nightBrightness = day
                nextSwitch = period?.start
            }
        }
        val on = level > 0f
        val shownIntensity = (intensity * level).roundToInt()
        val brightness = (day + (nightBrightness - day) * level).roundToInt().coerceIn(BrightnessSteps.MIN, BrightnessSteps.MAX)
        val fading = level > 0f && level < 1f
        val boundaries = listOfNotNull(
            period?.start, period?.end, period?.start?.plus(fadeMs), period?.end?.minus(fadeMs),
            manual?.until?.takeIf { it != Long.MAX_VALUE }, manual?.until?.takeIf { it != Long.MAX_VALUE }?.minus(fadeMs),
        ).filter { it > now }
        val nextCheck = minOf(boundaries.minOrNull() ?: Long.MAX_VALUE, now + if (fading) FADE_TICK_MS else MAX_TICK_MS)
        return ScreenFilter(
            brightness = brightness,
            tintRgb = kelvinToRgb(config.temperatureK),
            tintAlpha = if (on) shownIntensity / 100f * NightSteps.MAX_TINT_ALPHA else 0f,
            intensity = if (on) shownIntensity else 0,
            on = on,
            nextSwitch = nextSwitch,
            overridden = manual != null,
            nextCheck = nextCheck,
        )
    }

    /** Switches night light on or off by hand until the night's end (on) or until the next night (off). */
    fun toggle(now: Long, zone: ZoneId, config: NightLightConfig, override: NightLightOverride?, location: GeoPoint?, filter: ScreenFilter): NightLightOverride? {
        val period = NightSchedule.period(now, zone, config, location)
        val scheduledOn = period != null && now in period
        return if (filter.on) {
            if (scheduledOn) NightLightOverride(until = period!!.end, forcedOn = false) else null
        } else {
            if (scheduledOn && active(override, now)?.forcedOn == false) {
                // Switched off by hand tonight: back on, without fading in.
                NightLightOverride(until = period!!.end, forcedOn = true)
            } else {
                NightLightOverride(until = onUntil(period, now), forcedOn = true)
            }
        }
    }

    /** The state shown in Settings: [NightState.On] only for an "on" made by hand that never ends. */
    fun state(override: NightLightOverride?, now: Long, filter: ScreenFilter): NightState = when {
        active(override, now)?.let { it.forcedOn && it.until == Long.MAX_VALUE } == true -> NightState.On
        filter.on -> NightState.UntilSchedule
        else -> NightState.Off
    }

    /** The choices for Settings' state row: without a schedule there is nothing to wait for. */
    fun states(config: NightLightConfig): List<NightState> =
        if (config.mode == NightMode.Off) listOf(NightState.Off, NightState.On) else NightState.entries

    /**
     * Switches to [state] by hand. Off holds until the next night (a scheduled night is skipped); on until the
     * schedule ends at the end of the current or next night; [NightState.On] until switched off. Values fixed in
     * quick settings are kept when switching between the two kinds of on.
     */
    fun withState(
        now: Long,
        zone: ZoneId,
        config: NightLightConfig,
        override: NightLightOverride?,
        location: GeoPoint?,
        filter: ScreenFilter,
        state: NightState,
    ): NightLightOverride? {
        val period = NightSchedule.period(now, zone, config, location)
        val scheduledOn = period != null && now in period
        val manualOn = active(override, now)?.takeIf { it.forcedOn }
        return when (state) {
            NightState.Off -> when {
                !filter.on -> override
                scheduledOn -> NightLightOverride(until = period!!.end, forcedOn = false)
                else -> null
            }
            NightState.UntilSchedule -> when {
                manualOn != null -> manualOn.copy(until = onUntil(period, now))
                scheduledOn && filter.on -> override
                else -> NightLightOverride(until = onUntil(period, now), forcedOn = true)
            }
            NightState.On -> manualOn?.copy(until = Long.MAX_VALUE) ?: NightLightOverride(until = Long.MAX_VALUE, forcedOn = true)
        }
    }

    /**
     * Fixes the tint at [intensity] until night light switches off, switching it on first if needed. The current
     * brightness is fixed too, so nothing changes by itself before the night ends.
     */
    fun withIntensity(now: Long, zone: ZoneId, config: NightLightConfig, override: NightLightOverride?, location: GeoPoint?, filter: ScreenFilter, intensity: Int): NightLightOverride {
        val value = intensity.coerceIn(0, 100)
        if (!filter.on) {
            val on = toggle(now, zone, config, override, location, filter) ?: NightLightOverride(onUntil(null, now), true)
            return on.copy(intensity = value)
        }
        return NightLightOverride(until = holdUntil(now, zone, config, override, location), forcedOn = true, intensity = value, brightness = filter.brightness)
    }

    /**
     * Fixes the brightness at [brightness] until night light switches off. Null while night light is off: then the
     * brightness is the day brightness and [ScreenDimmer] keeps it.
     */
    fun withBrightness(now: Long, zone: ZoneId, config: NightLightConfig, override: NightLightOverride?, location: GeoPoint?, filter: ScreenFilter, brightness: Int): NightLightOverride? {
        if (!filter.on) return null
        val value = brightness.coerceIn(BrightnessSteps.MIN, BrightnessSteps.MAX)
        return NightLightOverride(until = holdUntil(now, zone, config, override, location), forcedOn = true, intensity = filter.intensity, brightness = value)
    }

    /** Night brightness for a [day] brightness darkened by [darkening] percent. */
    fun darkened(day: Int, darkening: Int): Int =
        (day * (100 - darkening.coerceIn(0, NightSteps.MAX_DARKENING)) / 100.0).roundToInt().coerceIn(BrightnessSteps.MIN, BrightnessSteps.MAX)

    /** Color of a light source of [kelvin] (Tanner Helland's approximation), 0xRRGGBB. */
    fun kelvinToRgb(kelvin: Int): Int {
        val t = kelvin.coerceIn(NightSteps.MIN_K, 40_000) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
        return (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** Until when a change made now holds: the end of the hand-made "on", else of tonight. */
    private fun holdUntil(now: Long, zone: ZoneId, config: NightLightConfig, override: NightLightOverride?, location: GeoPoint?): Long {
        active(override, now)?.takeIf { it.forcedOn }?.let { return it.until }
        return onUntil(NightSchedule.period(now, zone, config, location), now)
    }

    /** Switched on by hand: until the end of the current or next night, or for good when nothing is scheduled. */
    private fun onUntil(period: NightPeriod?, now: Long): Long = period?.end?.takeIf { it > now } ?: Long.MAX_VALUE

    private fun ramp(elapsed: Long, fadeMs: Long): Float =
        if (fadeMs <= 0) 1f else (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
}
