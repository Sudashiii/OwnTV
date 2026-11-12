package dev.owntv.launcher.quick

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickSettingsLogicTest {
    @Test
    fun `brightness steps by ten and never goes fully dark`() {
        assertEquals(90, BrightnessSteps.step(100, -1))
        assertEquals(100, BrightnessSteps.step(100, +1))
        assertEquals(10, BrightnessSteps.step(10, -1))
        assertEquals(0f, BrightnessSteps.dimAlpha(100))
        assertEquals(0.9f, BrightnessSteps.dimAlpha(10), 0.0001f)
        assertEquals(0.9f, BrightnessSteps.dimAlpha(0), 0.0001f)
    }

    @Test
    fun `sleep timer moves between its choices from the time left`() {
        assertEquals(15, SleepSteps.step(null, +1))
        assertEquals(0, SleepSteps.step(null, -1))
        assertEquals(45, SleepSteps.step(42, +1))
        assertEquals(30, SleepSteps.step(42, -1))
        assertEquals(0, SleepSteps.step(15, -1))
        assertEquals(0, SleepSteps.step(3, -1))
        assertEquals(120, SleepSteps.step(120, +1))
    }

    @Test
    fun `sleep timer rounds up and formats hours`() {
        assertEquals(1, SleepSteps.remainingMinutes(1_000))
        assertEquals(15, SleepSteps.remainingMinutes(15 * 60_000L))
        assertEquals(16, SleepSteps.remainingMinutes(15 * 60_000L + 1))
        assertEquals("Off", SleepSteps.format(null))
        assertEquals("42 min", SleepSteps.format(42))
        assertEquals("1 h", SleepSteps.format(60))
        assertEquals("1 h 30 min", SleepSteps.format(90))
    }

    @Test
    fun `volume moves about five percent and stays in range`() {
        assertEquals(1, VolumeSteps.step(0, 15, +1))
        assertEquals(0, VolumeSteps.step(0, 15, -1))
        assertEquals(25, VolumeSteps.step(20, 100, +1))
        assertEquals(100, VolumeSteps.step(98, 100, +1))
        assertEquals(53, VolumeSteps.percent(8, 15))
        assertEquals(0, VolumeSteps.percent(3, 0))
    }
}
