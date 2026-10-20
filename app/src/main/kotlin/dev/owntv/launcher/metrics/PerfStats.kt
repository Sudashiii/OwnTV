package dev.owntv.launcher.metrics

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where a frame's time went, from Android's FrameMetrics. */
enum class FramePhase(val label: String, val short: String) {
    Waiting("Main thread busy before the frame", "wait"),
    Input("Input handling", "input"),
    Composition("Composition and animation", "compose"),
    Layout("Measure and layout", "layout"),
    Draw("Draw (recording)", "draw"),
    Upload("Sync and bitmap upload", "upload"),
    Gpu("GPU commands and swap", "gpu"),
}

/** One drawn frame. [phases] holds milliseconds per [FramePhase], in its order. */
class FrameSample(
    val uptimeMs: Long,
    val wallMs: Long,
    val totalMs: Double,
    val phases: DoubleArray,
    val screen: String,
) {
    val dominant: FramePhase get() = FramePhase.entries[phases.indices.maxBy { phases[it] }]

    /** "layout 30.1 · draw 6.0", the phases that took at least a millisecond, largest first. */
    fun breakdown(limit: Int = 3): String =
        phases.indices.sortedByDescending { phases[it] }.filter { phases[it] >= 1.0 }.take(limit)
            .joinToString(" · ") { "${FramePhase.entries[it].short} ${phases[it].ms(false)}" }
            .ifEmpty { "no phase over 1 ms" }
}

/** A fixed-size histogram, so a session can run for days without growing. Values above the last bucket keep their maximum. */
class Histogram(private val bucketMs: Double, buckets: Int) {
    private val counts = LongArray(buckets + 1)
    var count = 0L
        private set
    var sum = 0.0
        private set
    var max = 0.0
        private set

    val mean: Double get() = if (count == 0L) 0.0 else sum / count

    fun add(ms: Double) {
        val index = (ms / bucketMs).toInt().coerceIn(0, counts.lastIndex)
        counts[index]++
        count++
        sum += ms
        if (ms > max) max = ms
    }

    /** The value [p] (0 to 1) of all values lie at or below, to the bucket's upper edge. */
    fun percentile(p: Double): Double {
        if (count == 0L) return 0.0
        val rank = (p * count).roundToLong().coerceIn(1, count)
        var seen = 0L
        for (i in counts.indices) {
            seen += counts[i]
            if (seen >= rank) return if (i == counts.lastIndex) max else minOf((i + 1) * bucketMs, max)
        }
        return max
    }

    fun clear() {
        counts.fill(0)
        count = 0
        sum = 0.0
        max = 0.0
    }
}

data class MemorySample(
    val heapUsed: Long,
    val heapMax: Long,
    val nativeHeap: Long,
    /** Resident set size, null where /proc cannot be read. */
    val rss: Long?,
    val layoutImageCache: Long,
    val appImageCache: Long,
    val gcCount: Long,
    val gcTimeMs: Long,
)

data class ImageLoad(val url: String, val ms: Double, val source: String, val width: Int, val height: Int)

/** What the live overlay shows: the last second of frames and the latest readings. */
data class LiveStats(
    val fps: Int,
    val p95: Double?,
    val slowPercent: Int,
    val history: List<Double>,
    val budgetMs: Double,
    val lastSlow: FrameSample?,
    val lastSlowAgoMs: Long,
    val keyMs: Double?,
    val compositions: Long,
    val memory: MemorySample?,
    val screen: String,
)

/**
 * Collects the performance numbers of one session. Thread safe: frames arrive on a background
 * thread, compositions and keys on the main thread, images on Coil's threads.
 */
class PerfStats(private val uptime: () -> Long) {
    private class ScreenFrames {
        val frames = Histogram(0.5, 500)
        var slow = 0L
        val slowPhases = DoubleArray(FramePhase.entries.size)
    }

    private var startedAt = uptime()
    private val frames = Histogram(0.5, 500)
    private var slow = 0L
    private var frozen = 0L
    private var droppedReports = 0L
    private var budgetMs = 1000.0 / 60
    private val phaseSums = DoubleArray(FramePhase.entries.size)
    private val slowPhaseSums = DoubleArray(FramePhase.entries.size)
    private val screens = LinkedHashMap<String, ScreenFrames>()
    private val slowest = ArrayList<FrameSample>()
    private val recent = ArrayDeque<FrameSample>()

    private val keyLatency = Histogram(1.0, 2000)
    private val keyHandling = Histogram(0.25, 400)
    private var lastKeyMs: Double? = null

    private var compositions = 0L
    private val composedNodes = HashMap<String, Long>()

    private val imageSources = LinkedHashMap<String, Long>()
    private val imageTimes = Histogram(5.0, 600)
    private var imageFailures = 0L
    private val slowestImages = ArrayList<ImageLoad>()
    private val largestImages = ArrayList<ImageLoad>()
    private val appImageTimes = Histogram(0.5, 400)

    private var memory: MemorySample? = null
    private var memoryAtStart: MemorySample? = null
    private var peakHeap = 0L
    private var peakNative = 0L
    private var peakRss = 0L

    /** Kept across [reset], since start-up happens once per process. */
    private var firstFrameMs: Double? = null
    private var homeReadyMs: Double? = null
    private var layoutDecodeMs: Double? = null
    private var layoutNodes = 0

    @Synchronized
    fun addFrame(sample: FrameSample, budgetMs: Double) {
        this.budgetMs = budgetMs
        frames.add(sample.totalMs)
        sample.phases.forEachIndexed { i, ms -> phaseSums[i] += ms }
        val screen = screens.getOrPut(sample.screen) { ScreenFrames() }
        screen.frames.add(sample.totalMs)
        if (sample.totalMs > budgetMs) {
            slow++
            screen.slow++
            sample.phases.forEachIndexed { i, ms ->
                slowPhaseSums[i] += ms
                screen.slowPhases[i] += ms
            }
        }
        if (sample.totalMs > FROZEN_MS) frozen++
        if (slowest.size < MAX_SLOWEST || sample.totalMs > slowest.last().totalMs) {
            slowest += sample
            slowest.sortByDescending { it.totalMs }
            if (slowest.size > MAX_SLOWEST) slowest.removeAt(slowest.lastIndex)
        }
        recent.addLast(sample)
        if (recent.size > MAX_RECENT) recent.removeFirst()
    }

    @Synchronized
    fun reportsDropped(count: Int) {
        droppedReports += count
    }

    @Synchronized
    fun keyPressed(latencyMs: Double?, handlingMs: Double) {
        keyHandling.add(handlingMs)
        if (latencyMs != null) {
            keyLatency.add(latencyMs)
            lastKeyMs = latencyMs
        }
    }

    @Synchronized
    fun nodeComposed(key: String) {
        compositions++
        composedNodes[key] = (composedNodes[key] ?: 0L) + 1
    }

    @Synchronized
    fun imageLoaded(load: ImageLoad) {
        imageSources[load.source] = (imageSources[load.source] ?: 0L) + 1
        // Memory cache hits cost nothing; their time is mostly waiting for layout and would hide the real loads.
        if (load.source != "memory") {
            imageTimes.add(load.ms)
            insertTop(slowestImages, load, MAX_IMAGES) { it.ms }
        }
        if (load.width > 0) insertTop(largestImages, load, MAX_IMAGES) { it.width.toDouble() * it.height }
    }

    @Synchronized
    fun imageFailed() {
        imageFailures++
    }

    @Synchronized
    fun appImageLoaded(ms: Double) {
        appImageTimes.add(ms)
    }

    @Synchronized
    fun memorySampled(sample: MemorySample) {
        memory = sample
        if (memoryAtStart == null) memoryAtStart = sample
        peakHeap = maxOf(peakHeap, sample.heapUsed)
        peakNative = maxOf(peakNative, sample.nativeHeap)
        sample.rss?.let { peakRss = maxOf(peakRss, it) }
    }

    @Synchronized
    fun startup(firstFrameMs: Double? = null, homeReadyMs: Double? = null) {
        if (firstFrameMs != null) this.firstFrameMs = firstFrameMs
        if (homeReadyMs != null) this.homeReadyMs = homeReadyMs
    }

    @Synchronized
    fun layoutDecoded(ms: Double, nodes: Int) {
        layoutDecodeMs = ms
        layoutNodes = nodes
    }

    @Synchronized
    fun reset() {
        startedAt = uptime()
        frames.clear()
        slow = 0
        frozen = 0
        droppedReports = 0
        phaseSums.fill(0.0)
        slowPhaseSums.fill(0.0)
        screens.clear()
        slowest.clear()
        recent.clear()
        keyLatency.clear()
        keyHandling.clear()
        lastKeyMs = null
        compositions = 0
        composedNodes.clear()
        imageSources.clear()
        imageTimes.clear()
        imageFailures = 0
        slowestImages.clear()
        largestImages.clear()
        appImageTimes.clear()
        memoryAtStart = memory
        peakHeap = memory?.heapUsed ?: 0
        peakNative = memory?.nativeHeap ?: 0
        peakRss = memory?.rss ?: 0
    }

    @Synchronized
    fun live(screen: String): LiveStats {
        val now = uptime()
        val lastSecond = recent.filter { now - it.uptimeMs <= 1000 }
        val sorted = lastSecond.map { it.totalMs }.sorted()
        val lastSlow = recent.lastOrNull { it.totalMs > budgetMs }
        return LiveStats(
            fps = lastSecond.size,
            p95 = sorted.takeIf { it.isNotEmpty() }?.let { it[(it.size * 0.95).toInt().coerceAtMost(it.lastIndex)] },
            slowPercent = if (lastSecond.isEmpty()) 0 else (lastSecond.count { it.totalMs > budgetMs } * 100.0 / lastSecond.size).roundToInt(),
            history = recent.takeLast(LIVE_HISTORY).map { it.totalMs },
            budgetMs = budgetMs,
            lastSlow = lastSlow,
            lastSlowAgoMs = lastSlow?.let { now - it.uptimeMs } ?: 0,
            keyMs = lastKeyMs,
            compositions = compositions,
            memory = memory,
            screen = screen,
        )
    }

    @Synchronized
    fun report(header: String): PerfReport = PerfReport(
        header = header,
        sessionMs = uptime() - startedAt,
        budgetMs = budgetMs,
        frameCount = frames.count,
        slow = slow,
        frozen = frozen,
        droppedReports = droppedReports,
        p50 = frames.percentile(0.5),
        p90 = frames.percentile(0.9),
        p95 = frames.percentile(0.95),
        p99 = frames.percentile(0.99),
        maxFrame = frames.max,
        phaseAvg = phaseSums.map { if (frames.count == 0L) 0.0 else it / frames.count },
        slowPhaseAvg = slowPhaseSums.map { if (slow == 0L) 0.0 else it / slow },
        screens = screens.map { (name, s) ->
            ScreenReport(
                name = name,
                frames = s.frames.count,
                slow = s.slow,
                p95 = s.frames.percentile(0.95),
                dominant = if (s.slow == 0L) null else FramePhase.entries[s.slowPhases.indices.maxBy { s.slowPhases[it] }],
            )
        }.sortedByDescending { it.frames },
        slowest = slowest.toList(),
        keys = keyHandling.count,
        keyP50 = keyLatency.percentile(0.5).takeIf { keyLatency.count > 0 },
        keyP95 = keyLatency.percentile(0.95).takeIf { keyLatency.count > 0 },
        keyMax = keyLatency.max,
        keyHandlingP50 = keyHandling.percentile(0.5),
        keyHandlingP95 = keyHandling.percentile(0.95),
        compositions = compositions,
        topComposed = composedNodes.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
        imageSources = imageSources.toMap(),
        imageFailures = imageFailures,
        imageP50 = imageTimes.percentile(0.5),
        imageP95 = imageTimes.percentile(0.95),
        slowestImages = slowestImages.toList(),
        largestImages = largestImages.toList(),
        appImages = appImageTimes.count,
        appImageP50 = appImageTimes.percentile(0.5),
        appImageP95 = appImageTimes.percentile(0.95),
        memory = memory,
        peakHeap = peakHeap,
        peakNative = peakNative,
        peakRss = peakRss,
        gcCount = memory?.let { it.gcCount - (memoryAtStart?.gcCount ?: it.gcCount) } ?: 0,
        gcTimeMs = memory?.let { it.gcTimeMs - (memoryAtStart?.gcTimeMs ?: it.gcTimeMs) } ?: 0,
        firstFrameMs = firstFrameMs,
        homeReadyMs = homeReadyMs,
        layoutDecodeMs = layoutDecodeMs,
        layoutNodes = layoutNodes,
    )

    private fun <T> insertTop(list: MutableList<T>, item: T, max: Int, by: (T) -> Double) {
        list += item
        list.sortByDescending(by)
        if (list.size > max) list.removeAt(list.lastIndex)
    }

    companion object {
        /** Android vitals calls frames over 700 ms frozen. */
        const val FROZEN_MS = 700.0
        private const val MAX_SLOWEST = 12
        private const val MAX_RECENT = 240
        private const val MAX_IMAGES = 5
        const val LIVE_HISTORY = 90
    }
}

data class ScreenReport(val name: String, val frames: Long, val slow: Long, val p95: Double, val dominant: FramePhase?)

/** A snapshot of [PerfStats], with the hints and the text shown in Settings and written to the report file. */
data class PerfReport(
    val header: String,
    val sessionMs: Long,
    val budgetMs: Double,
    val frameCount: Long,
    val slow: Long,
    val frozen: Long,
    val droppedReports: Long,
    val p50: Double,
    val p90: Double,
    val p95: Double,
    val p99: Double,
    val maxFrame: Double,
    val phaseAvg: List<Double>,
    val slowPhaseAvg: List<Double>,
    val screens: List<ScreenReport>,
    val slowest: List<FrameSample>,
    val keys: Long,
    val keyP50: Double?,
    val keyP95: Double?,
    val keyMax: Double,
    val keyHandlingP50: Double,
    val keyHandlingP95: Double,
    val compositions: Long,
    val topComposed: List<Pair<String, Long>>,
    val imageSources: Map<String, Long>,
    val imageFailures: Long,
    val imageP50: Double,
    val imageP95: Double,
    val slowestImages: List<ImageLoad>,
    val largestImages: List<ImageLoad>,
    val appImages: Long,
    val appImageP50: Double,
    val appImageP95: Double,
    val memory: MemorySample?,
    val peakHeap: Long,
    val peakNative: Long,
    val peakRss: Long,
    val gcCount: Long,
    val gcTimeMs: Long,
    val firstFrameMs: Double?,
    val homeReadyMs: Double?,
    val layoutDecodeMs: Double?,
    val layoutNodes: Int,
) {
    val slowShare: Double get() = if (frameCount == 0L) 0.0 else slow.toDouble() / frameCount

    private val slowDominant: FramePhase? get() =
        if (slow == 0L) null else FramePhase.entries[slowPhaseAvg.indices.maxBy { slowPhaseAvg[it] }]

    /** Plain-language findings, most important first. */
    fun hints(): List<String> {
        val out = mutableListOf<String>()
        if (frameCount < MIN_FRAMES) {
            out += "Not enough frames yet. Use the launcher for a while: move around, switch pages, open and leave apps, then come back here."
        } else if (slowShare >= 0.05) {
            val phase = slowDominant!!
            out += "${slowShare.pct()} of frames missed the ${budgetMs.ms()} deadline. In those frames most time went to " +
                "${phase.label.lowercase()} (${slowPhaseAvg[phase.ordinal].ms()} on average). ${advice(phase)}"
        } else {
            out += "Frames are smooth: ${slowShare.pct()} missed the ${budgetMs.ms()} deadline (p95 ${p95.ms()})."
        }
        if (frozen > 0) out += "$frozen frozen frame${plural(frozen)} (over ${PerfStats.FROZEN_MS.roundToInt()} ms). Slowest frames shows where and when."
        val worst = screens.filter { it.frames >= MIN_FRAMES && it.slow > 0 }.maxByOrNull { it.slow.toDouble() / it.frames }
        if (worst != null && screens.size > 1) {
            val share = worst.slow.toDouble() / worst.frames
            if (share >= 0.05 && share >= slowShare * 1.5) {
                out += "${worst.name} is the slowest screen: ${share.pct()} slow frames, mostly ${worst.dominant?.label?.lowercase()}."
            }
        }
        if (slowDominant == FramePhase.Composition && topComposed.isNotEmpty()) {
            out += "Nodes that recompose most: ${topComposed.take(3).joinToString { "${it.first} (${it.second.grouped()})" }}."
        }
        if (keyP95 != null && keyP95 > 150) {
            var text = "Remote presses take ${keyP95.ms()} until the screen changes (95th percentile)."
            if (keyHandlingP95 > 30) text += " Handling the key itself takes ${keyHandlingP95.ms()}: focus search over many nodes or work in a click handler."
            out += text
        }
        val network = imageSources["network"] ?: 0
        if (network > 0 && imageP95 > 1000) {
            out += "$network image${plural(network)} came from the network and loads take up to ${imageP95.ms()} (p95). They are cached on the TV afterwards."
        }
        largestImages.firstOrNull()?.let { image ->
            if (image.width.toLong() * image.height > 2L * 1920 * 1080) {
                out += "The largest image is ${image.width}x${image.height} (${image.url}). Decoding and uploading it costs time and memory; " +
                    "use images close to the size they are shown at."
            }
        }
        if (appImages > 0 && appImageP95 > 50) {
            out += "App banners and icons take ${appImageP95.ms()} to load from the system (p95); they are cached after the first time."
        }
        memory?.let { m ->
            if (m.heapMax > 0 && m.heapUsed > m.heapMax * 0.8) {
                out += "The Java heap is at ${m.heapUsed.mb()} of ${m.heapMax.mb()}; the launcher may be close to running out of memory."
            }
        }
        val minutes = sessionMs / 60_000.0
        if (minutes >= 1 && gcTimeMs / minutes > 500) {
            out += "Garbage collection took ${gcTimeMs.grouped()} ms in ${minutes.roundToInt()} min: something allocates a lot."
        }
        if (homeReadyMs != null && homeReadyMs > 3000) {
            out += "Start-up took ${(homeReadyMs / 1000).oneDecimal()} s until the home screen was ready" +
                (layoutDecodeMs?.let { " (reading the layout: ${it.ms()})" } ?: "") + "."
        }
        return out
    }

    /** Titled sections, shared by the Settings screen and the report file. */
    fun sections(): List<Pair<String, String>> = buildList {
        add("Findings" to hints().joinToString("\n") { "• $it" })
        add(
            "Frames" to listOf(
                "${frameCount.grouped()} frames in ${sessionMs.duration()} · ${slowShare.pct()} missed the ${budgetMs.ms()} deadline · $frozen frozen",
                "Frame time: p50 ${p50.ms()} · p90 ${p90.ms()} · p95 ${p95.ms()} · p99 ${p99.ms()} · max ${maxFrame.ms()}",
                "Average frame: ${phaseLine(phaseAvg)}",
                if (slow > 0) "Average slow frame: ${phaseLine(slowPhaseAvg)}" else null,
                if (droppedReports > 0) "${droppedReports.grouped()} frame reports were dropped by Android" else null,
            ).filterNotNull().joinToString("\n"),
        )
        if (screens.isNotEmpty()) {
            add(
                "Screens" to screens.joinToString("\n") { s ->
                    val share = if (s.frames == 0L) 0.0 else s.slow.toDouble() / s.frames
                    "${s.name}: ${s.frames.grouped()} frames · ${share.pct()} slow · p95 ${s.p95.ms()}" +
                        (s.dominant?.let { " · slow frames mostly ${it.short}" } ?: "")
                },
            )
        }
        if (slowest.isNotEmpty()) {
            add("Slowest frames" to slowest.joinToString("\n") { "${it.wallMs.clock()} ${it.screen}: ${it.totalMs.ms()} (${it.breakdown()})" })
        }
        add(
            "Remote" to if (keys == 0L) "No key presses yet." else listOf(
                "${keys.grouped()} key presses",
                keyP50?.let { "Press to screen change: p50 ${it.ms()} · p95 ${keyP95!!.ms()} · max ${keyMax.ms()}" },
                "Key handling: p50 ${keyHandlingP50.ms()} · p95 ${keyHandlingP95.ms()}",
            ).filterNotNull().joinToString("\n"),
        )
        add(
            "Recomposition" to if (compositions == 0L) "No layout nodes composed yet." else
                "${compositions.grouped()} node compositions\n" + topComposed.joinToString("\n") { "${it.first}: ${it.second.grouped()}" },
        )
        val loads = imageSources.values.sum()
        add(
            "Images" to listOf(
                if (loads == 0L) "No layout images loaded yet." else
                    "${loads.grouped()} layout image loads: " + imageSources.entries.joinToString(" · ") { "${it.key} ${it.value.grouped()}" } +
                        (if (imageFailures > 0) " · failed $imageFailures" else ""),
                if (loads > (imageSources["memory"] ?: 0)) "Load time (not from memory): p50 ${imageP50.ms()} · p95 ${imageP95.ms()}" else null,
                slowestImages.firstOrNull()?.let { "Slowest: ${it.url} (${it.ms.ms()}, ${it.source})" },
                largestImages.firstOrNull()?.let { "Largest: ${it.url} (${it.width}x${it.height})" },
                if (appImages > 0) "App banners and icons: ${appImages.grouped()} loads · p50 ${appImageP50.ms()} · p95 ${appImageP95.ms()}" else null,
            ).filterNotNull().joinToString("\n"),
        )
        add(
            "Memory" to (memory?.let { m ->
                listOfNotNull(
                    "Java heap ${m.heapUsed.mb()} of ${m.heapMax.mb()} (peak ${peakHeap.mb()}) · native ${m.nativeHeap.mb()} (peak ${peakNative.mb()})",
                    m.rss?.let { "Resident (RSS) ${it.mb()} (peak ${peakRss.mb()})" },
                    "Image caches: layout images ${m.layoutImageCache.mb()} · app images ${m.appImageCache.mb()}",
                    "Garbage collection: ${gcCount.grouped()} runs, ${gcTimeMs.grouped()} ms this session",
                ).joinToString("\n")
            } ?: "No reading yet."),
        )
        add(
            "Start-up" to listOf(
                firstFrameMs?.let { "Process start to first frame: ${it.ms()}" },
                homeReadyMs?.let { "Process start to home screen ready: ${it.ms()}" },
                layoutDecodeMs?.let { "Reading the layout: ${it.ms()} (${layoutNodes.toLong().grouped()} nodes)" },
            ).filterNotNull().joinToString("\n").ifEmpty { "Measured when OwnTV starts with metrics on." },
        )
    }

    fun toText(): String = buildString {
        appendLine(header)
        sections().forEach { (title, body) ->
            appendLine()
            appendLine("== $title ==")
            appendLine(body)
        }
    }

    private fun phaseLine(values: List<Double>): String =
        FramePhase.entries.joinToString(" · ") { "${it.short} ${values[it.ordinal].ms(false)}" }

    private fun advice(phase: FramePhase): String = when (phase) {
        FramePhase.Waiting -> "The main thread was busy before frames could start: file or network access, JSON parsing or image decoding on the main thread."
        FramePhase.Input -> "Input handling is slow: work in key handlers or focus changes."
        FramePhase.Composition -> "Too much recomposition: see Recomposition for the nodes that recompose most. Animations and state read high up in the tree cause it."
        FramePhase.Layout -> "Measuring is expensive: deep nesting, many nodes on one page, or rows and grids that measure all their items."
        FramePhase.Draw -> "Drawing is expensive: many shadows, gradients, rounded clips or text."
        FramePhase.Upload -> "Bitmaps are uploaded to the GPU: large or many new images. Smaller images help."
        FramePhase.Gpu -> "The GPU is the limit: glass blur, large translucent layers, shadows and full-screen backgrounds are expensive on TV chips."
    }

    companion object {
        const val MIN_FRAMES = 60
    }
}

/** Milliseconds: "12.3 ms", "0.45 ms", or "1,234 ms" for long ones. */
internal fun Double.ms(unit: Boolean = true): String {
    val number = when {
        this >= 100 -> roundToLong().grouped()
        this < 1 -> ((this * 100).roundToInt() / 100.0).toString()
        else -> oneDecimal()
    }
    return if (unit) "$number ms" else number
}

internal fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()

internal fun Double.pct(): String = if (this > 0 && this < 0.01) "<1%" else "${(this * 100).roundToInt()}%"

internal fun Long.mb(): String = "${(this / 1_048_576.0).roundToInt()} MB"

internal fun Long.grouped(): String = String.format(java.util.Locale.ROOT, "%,d", this)

private fun plural(n: Long) = if (n == 1L) "" else "s"

private fun Long.duration(): String {
    val s = this / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun Long.clock(): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(this))
