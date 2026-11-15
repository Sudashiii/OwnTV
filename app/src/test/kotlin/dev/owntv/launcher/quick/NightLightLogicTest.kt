package dev.owntv.launcher.quick

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightLightLogicTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val vienna = GeoPoint(48.2, 16.37)
    private val custom = NightLightConfig(mode = NightMode.Custom, startMinute = 22 * 60, endMinute = 7 * 60, fadeMinutes = 0, intensity = 50, darkening = 20)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun eval(now: Long, config: NightLightConfig = custom, override: NightLightOverride? = null, day: Int = 100) =
        NightLightLogic.evaluate(now, zone, config, override, day, vienna)

    @Test
    fun `custom window across midnight`() {
        assertFalse(eval(at(14, 21, 59)).on)
        assertTrue(eval(at(14, 22, 0)).on)
        assertTrue(eval(at(15, 3, 0)).on)
        assertTrue(eval(at(15, 6, 59)).on)
        assertFalse(eval(at(15, 7, 0)).on)
        assertEquals(at(15, 7), eval(at(14, 23)).nextSwitch)
        assertEquals(at(15, 22), eval(at(15, 12)).nextSwitch)
        assertFalse(eval(at(14, 23), custom.copy(mode = NightMode.Off)).on)
    }

    @Test
    fun `window within one day`() {
        val config = custom.copy(startMinute = 13 * 60, endMinute = 15 * 60)
        assertTrue(eval(at(14, 14), config).on)
        assertFalse(eval(at(14, 15, 30), config).on)
        assertEquals(at(15, 13), eval(at(14, 15, 30), config).nextSwitch)
    }

    @Test
    fun `fades in and out`() {
        val config = custom.copy(fadeMinutes = 30)
        assertEquals(25, eval(at(14, 22, 15), config).intensity)
        assertEquals(50, eval(at(15, 2), config).intensity)
        assertEquals(25, eval(at(15, 6, 45), config).intensity)
        // Day 100 %, darkened 20 %: halfway through the fade 90 %.
        assertEquals(90, eval(at(14, 22, 15), config).brightness)
        assertEquals(80, eval(at(15, 2), config).brightness)
    }

    @Test
    fun `night brightness is darkened from the day brightness and never below the minimum`() {
        assertEquals(80, NightLightLogic.darkened(100, 20))
        assertEquals(40, NightLightLogic.darkened(50, 20))
        assertEquals(BrightnessSteps.MIN, NightLightLogic.darkened(15, 90))
        assertEquals(60, eval(at(15, 1), day = 75).brightness)
        assertEquals(75, eval(at(15, 12), day = 75).brightness)
    }

    @Test
    fun `sun times for Vienna`() {
        val day = assertNotNull(SunTimes.compute(LocalDate.of(2026, 6, 21), vienna))
        assertClose(LocalDateTime.of(2026, 6, 21, 4, 53), day.sunrise)
        assertClose(LocalDateTime.of(2026, 6, 21, 20, 58), day.sunset)
        val winter = assertNotNull(SunTimes.compute(LocalDate.of(2026, 12, 21), vienna))
        assertClose(LocalDateTime.of(2026, 12, 21, 7, 43), winter.sunrise)
        assertClose(LocalDateTime.of(2026, 12, 21, 16, 2), winter.sunset)
    }

    @Test
    fun `sunset to sunrise falls back to custom times without sunset`() {
        val tromso = GeoPoint(69.65, 18.96)
        assertNull(SunTimes.compute(LocalDate.of(2026, 6, 21), tromso))
        val config = custom.copy(mode = NightMode.Sun)
        val june = LocalDateTime.of(2026, 6, 21, 23, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(NightLightLogic.evaluate(june, zone, config, null, 100, tromso).on)
        // Unknown location: custom times too.
        assertTrue(NightLightLogic.evaluate(at(14, 23), zone, config, null, 100, null).on)
        // Vienna in November: on after sunset (about 16:30), off before.
        assertTrue(eval(at(14, 17), config).on)
        assertFalse(eval(at(14, 15), config).on)
    }

    @Test
    fun `brightness changed at night holds until the night ends`() {
        val now = at(14, 23)
        val filter = eval(now)
        val override = assertNotNull(NightLightLogic.withBrightness(now, zone, custom, null, vienna, filter, 55))
        assertEquals(at(15, 7), override.until)
        assertEquals(55, eval(at(15, 3), override = override).brightness)
        assertEquals(50, eval(at(15, 3), override = override).intensity)
        // The next morning the override is over: day brightness, and the next night starts from the settings.
        assertEquals(100, eval(at(15, 7, 1), override = override).brightness)
        assertEquals(80, eval(at(15, 23), override = override).brightness)
        // By day the brightness belongs to the dimmer.
        assertNull(NightLightLogic.withBrightness(at(15, 12), zone, custom, null, vienna, eval(at(15, 12)), 55))
    }

    @Test
    fun `changes during the fade freeze the other value`() {
        val config = custom.copy(fadeMinutes = 60)
        val now = at(14, 22, 30)
        val filter = eval(now, config)
        assertEquals(25, filter.intensity)
        val override = NightLightLogic.withIntensity(now, zone, config, null, vienna, filter, 26)
        // Later in the fade, nothing moves any more.
        val later = eval(at(14, 22, 50), config, override)
        assertEquals(26, later.intensity)
        assertEquals(filter.brightness, later.brightness)
        // Fades out before the end of the night.
        assertEquals(13, eval(at(15, 6, 30), config, override).intensity)
    }

    @Test
    fun `switched off at night stays off until the next night`() {
        val now = at(14, 23)
        val off = assertNotNull(NightLightLogic.toggle(now, zone, custom, null, vienna, eval(now)))
        assertFalse(off.forcedOn)
        assertFalse(eval(at(15, 2), override = off).on)
        assertEquals(at(15, 22), eval(at(15, 2), override = off).nextSwitch)
        assertTrue(eval(at(15, 22, 1), override = off).on)
        // On again the same night.
        val on = assertNotNull(NightLightLogic.toggle(at(15, 2), zone, custom, off, vienna, eval(at(15, 2), override = off)))
        assertTrue(eval(at(15, 3), override = on).on)
    }

    @Test
    fun `switched on by day stays on until the next scheduled end`() {
        val now = at(14, 12)
        val on = assertNotNull(NightLightLogic.toggle(now, zone, custom, null, vienna, eval(now)))
        assertEquals(at(15, 7), on.until)
        assertTrue(eval(at(14, 18), override = on).on)
        assertFalse(eval(at(15, 8), override = on).on)
        // Without a schedule it stays on until switched off.
        val off = custom.copy(mode = NightMode.Off)
        val always = assertNotNull(NightLightLogic.toggle(now, zone, off, null, vienna, eval(now, off)))
        assertEquals(Long.MAX_VALUE, always.until)
        val filter = eval(at(20, 3), off, always)
        assertTrue(filter.on)
        assertNull(NightLightLogic.toggle(at(20, 3), zone, off, always, vienna, filter))
    }

    @Test
    fun `intensity from quick settings switches it on`() {
        val now = at(14, 12)
        val override = NightLightLogic.withIntensity(now, zone, custom, null, vienna, eval(now), 51)
        assertTrue(override.forcedOn)
        assertEquals(51, eval(at(14, 13), override = override).intensity)
        // With a schedule it switches off by itself when the next night ends.
        assertEquals(at(15, 7), override.until)
        assertTrue(eval(at(15, 6, 59), override = override).on)
        assertFalse(eval(at(15, 7), override = override).on)
        assertEquals(at(15, 22), eval(at(15, 7), override = override).nextSwitch)
    }

    private fun withState(now: Long, state: NightState, override: NightLightOverride? = null, config: NightLightConfig = custom) =
        NightLightLogic.withState(now, zone, config, override, vienna, eval(now, config, override), state)

    @Test
    fun `settings states off, on until the schedule ends and on for good`() {
        val day = at(14, 12)
        assertEquals(NightState.Off, NightLightLogic.state(null, day, eval(day)))
        // On until the schedule: like quick settings, off when the next night ends.
        val untilSchedule = assertNotNull(withState(day, NightState.UntilSchedule))
        assertEquals(at(15, 7), untilSchedule.until)
        assertEquals(NightState.UntilSchedule, NightLightLogic.state(untilSchedule, day, eval(day, override = untilSchedule)))
        // On: stays on through the morning and the next days.
        val on = assertNotNull(withState(day, NightState.On))
        assertEquals(NightState.On, NightLightLogic.state(on, day, eval(day, override = on)))
        assertTrue(eval(at(15, 12), override = on).on)
        assertTrue(eval(at(20, 9), override = on).on)
        // Off by day: back to the schedule.
        assertNull(withState(at(15, 12), NightState.Off, on))
        // Off at night: off until the next night.
        val night = at(14, 23)
        val off = withState(night, NightState.Off)
        assertFalse(eval(at(15, 3), override = off).on)
        assertTrue(eval(at(15, 23), override = off).on)
        // The scheduled night needs no change for "until schedule"; turned off, it switches back on.
        assertNull(withState(night, NightState.UntilSchedule))
        assertTrue(eval(at(15, 3), override = withState(night, NightState.UntilSchedule, off)).on)
    }

    @Test
    fun `values fixed in quick settings stay when switching between the kinds of on`() {
        val now = at(14, 23)
        val fixed = NightLightLogic.withIntensity(now, zone, custom, null, vienna, eval(now), 70)
        val on = assertNotNull(withState(now, NightState.On, fixed))
        assertEquals(70, eval(at(15, 12), override = on).intensity)
        val back = assertNotNull(withState(at(15, 12), NightState.UntilSchedule, on))
        assertEquals(70, back.intensity)
        assertEquals(at(16, 7), back.until)
    }

    @Test
    fun `without a schedule there is no until schedule`() {
        val off = custom.copy(mode = NightMode.Off)
        assertEquals(listOf(NightState.Off, NightState.On), NightLightLogic.states(off))
        assertEquals(NightState.entries, NightLightLogic.states(custom))
    }

    @Test
    fun `kelvin colors`() {
        assertEquals(0xFF4400, NightLightLogic.kelvinToRgb(1000))
        assertEquals(0xFFFFFF, NightLightLogic.kelvinToRgb(6600))
        val warm = NightLightLogic.kelvinToRgb(3400)
        assertEquals(255, warm shr 16)
        assertTrue((warm and 0xFF) < 200)
    }

    @Test
    fun `steps stay in range`() {
        assertEquals(NightSteps.MAX_K, NightSteps.temperature(6000, +1))
        assertEquals(1000, NightSteps.temperature(1000, -1))
        assertEquals(23 * 60 + 55, NightSteps.time(0, -1))
        assertEquals(NightMode.Off, NightSteps.mode(NightMode.Sun, +1))
        assertEquals("07:05", NightSteps.formatTime(425))
    }

    private fun assertClose(expected: LocalDateTime, actual: Long) {
        val ms = expected.atZone(zone).toInstant().toEpochMilli()
        assertTrue(abs(ms - actual) <= 4 * 60_000, "expected $expected, was ${java.time.Instant.ofEpochMilli(actual).atZone(zone)}")
    }
}
