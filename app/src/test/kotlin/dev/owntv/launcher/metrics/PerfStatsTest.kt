package dev.owntv.launcher.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerfStatsTest {
    private var now = 0L
    private val stats = PerfStats { now }
    private val budget = 1000.0 / 60

    private fun frame(total: Double, screen: String = "Home", vararg phases: Pair<FramePhase, Double>): FrameSample {
        val values = DoubleArray(FramePhase.entries.size)
        phases.forEach { (phase, ms) -> values[phase.ordinal] = ms }
        now += 16
        return FrameSample(now, now, total, values, screen)
    }

    @Test
    fun `histogram percentiles land on the bucket that holds them`() {
        val h = Histogram(0.5, 500)
        repeat(90) { h.add(8.2) }
        repeat(10) { h.add(40.1) }
        assertEquals(8.5, h.percentile(0.5))
        assertEquals(8.5, h.percentile(0.9))
        assertEquals(40.1, h.percentile(0.95), "capped at the maximum")
        h.add(5000.0)
        assertEquals(5000.0, h.percentile(1.0), "values past the last bucket keep their maximum")
        assertEquals(0.0, Histogram(1.0, 10).percentile(0.5))
    }

    @Test
    fun `slow frames are attributed to their dominant phase and screen`() {
        repeat(90) { stats.addFrame(frame(8.0, "Home (apps)", FramePhase.Draw to 3.0), budget) }
        repeat(10) { stats.addFrame(frame(40.0, "Home (apps)", FramePhase.Layout to 30.0, FramePhase.Draw to 6.0), budget) }
        repeat(80) { stats.addFrame(frame(6.0, "Settings"), budget) }
        val report = stats.report("test")
        assertEquals(180, report.frameCount)
        assertEquals(10, report.slow)
        assertEquals(30.0, report.slowPhaseAvg[FramePhase.Layout.ordinal])
        val home = report.screens.first { it.name == "Home (apps)" }
        assertEquals(FramePhase.Layout, home.dominant)
        val hints = report.hints()
        assertTrue(hints[0].contains("measure and layout"), hints[0])
        assertTrue(hints.any { it.startsWith("Home (apps) is the slowest screen") }, hints.toString())
        assertEquals("layout 30.0 · draw 6.0", report.slowest.first().breakdown())
    }

    @Test
    fun `smooth sessions say so and few frames ask for more use`() {
        assertTrue(stats.report("test").hints().single().startsWith("Not enough frames"))
        repeat(200) { stats.addFrame(frame(7.0), budget) }
        assertTrue(stats.report("test").hints().first().startsWith("Frames are smooth"))
    }

    @Test
    fun `recomposition hotspots are named when composition dominates`() {
        repeat(50) { stats.nodeComposed("clock \"time\"") }
        stats.nodeComposed("row \"apps\"")
        repeat(100) { stats.addFrame(frame(30.0, "Home", FramePhase.Composition to 25.0), budget) }
        val report = stats.report("test")
        assertEquals("clock \"time\"" to 50L, report.topComposed.first())
        assertTrue(report.hints().any { it.startsWith("Nodes that recompose most: clock \"time\" (50)") }, report.hints().toString())
    }

    @Test
    fun `memory cache hits do not count as image load time`() {
        stats.imageLoaded(ImageLoad("https://a/x.jpg", 0.1, "memory", 100, 100))
        stats.imageLoaded(ImageLoad("https://a/big.jpg", 1800.0, "network", 7680, 4320))
        val report = stats.report("test")
        assertEquals(mapOf("memory" to 1L, "network" to 1L), report.imageSources)
        assertEquals(1800.0, report.imageP50)
        assertTrue(report.hints().any { it.startsWith("The largest image is 7680x4320") }, report.hints().toString())
    }

    @Test
    fun `live stats cover the last second only`() {
        repeat(100) { stats.addFrame(frame(if (it % 10 == 0) 30.0 else 8.0), budget) }
        now += 400
        val live = stats.live("Home")
        assertEquals(38, live.fps, "frames within 1000 ms of now")
        assertEquals(PerfStats.LIVE_HISTORY, live.history.size)
        assertEquals(30.0, live.lastSlow?.totalMs)
    }

    @Test
    fun `reset starts a new session but keeps start-up`() {
        stats.startup(firstFrameMs = 900.0, homeReadyMs = 1500.0)
        repeat(10) { stats.addFrame(frame(50.0), budget) }
        stats.keyPressed(120.0, 4.0)
        stats.reset()
        val report = stats.report("test")
        assertEquals(0, report.frameCount)
        assertEquals(0, report.keys)
        assertEquals(1500.0, report.homeReadyMs)
        assertTrue(report.toText().contains("== Start-up ==\nProcess start to first frame: 900 ms"), report.toText())
    }
}
