package dev.owntv.launcher.metrics

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Display
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import coil3.EventListener
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.owntv.launcher.ui.render.RenderMetrics
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.NodeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Settings > Performance metrics. */
enum class MetricsMode {
    /** Nothing is measured. */
    Off,

    /** Measures while OwnTV is in front; the results are in Settings and in the report file. */
    Background,

    /** Like [Background], plus a small see-through panel over the launcher. */
    Live,
}

/**
 * Measures the launcher while [mode] is not [MetricsMode.Off]: every frame of the main window (Android's
 * FrameMetrics, split into phases), remote presses until the screen changes, layout node compositions,
 * image loads, memory and start-up. The numbers go to [stats], the Settings screen and [reportFile].
 */
class PerformanceMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appImageCacheBytes: () -> Long,
) : RenderMetrics {
    private val prefs = context.getSharedPreferences("metrics", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        prefs.getString(KEY_MODE, null)?.let { name -> MetricsMode.entries.firstOrNull { it.name == name } } ?: MetricsMode.Off,
    )
    val mode: StateFlow<MetricsMode> = _mode.asStateFlow()
    val enabled: Boolean get() = _mode.value != MetricsMode.Off

    val stats = PerfStats(SystemClock::uptimeMillis)

    /** What is on screen, for attributing frames ("Home (apps)", "Settings"). Set by the UI. */
    @Volatile
    var screen: String = "Home"

    private val dir = File(context.filesDir, "metrics")
    val reportFile = File(dir, "report.txt")
    private val previousReportFile = File(dir, "previous-report.txt")
    private var rotated = false

    private val processStart = Process.getStartUptimeMillis()

    /** Start-up only counts when metrics were on as the process started. */
    private val measureStartup = enabled
    private var homeSeen = false
    private var window: Window? = null
    private var modeJob: Job? = null
    private var listening = false
    private var foreground = false
    private var samplerJob: Job? = null
    private var budgetMs = 1000.0 / 60
    private var refreshRate = 60f
    private val frameThread by lazy { HandlerThread("owntv-frame-metrics").apply { start() } }

    @Volatile private var keyDownAt = 0L
    @Volatile private var keyDispatchedAt = 0L
    @Volatile private var keyHandlingMs = 0.0
    @Volatile private var homeReadyAt = 0L
    @Volatile private var startupDone = false
    private var firstFrameSeen = false

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (dropped > 0) stats.reportsDropped(dropped)
        onFrame(metrics)
    }

    fun setMode(mode: MetricsMode) {
        if (mode == _mode.value) return
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        if (_mode.value == MetricsMode.Off) stats.reset()
        _mode.value = mode
        if (mode == MetricsMode.Off) saveReport()
    }

    fun reset() = stats.reset()

    /** Follows [mode] for [activity]'s window. Call from `onCreate`, before `setContent`. */
    fun attach(activity: Activity) {
        window = activity.window
        refreshRate = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        budgetMs = 1000.0 / refreshRate
        modeJob?.cancel()
        // Main.immediate: the listener is in place for the very first frame.
        modeJob = scope.launch(Dispatchers.Main.immediate) {
            mode.collect { updateListener(); updateSampler() }
        }
    }

    fun detach(activity: Activity) {
        if (window !== activity.window) return
        modeJob?.cancel()
        modeJob = null
        if (listening) runCatching { window?.removeOnFrameMetricsAvailableListener(frameListener) }
        listening = false
        window = null
    }

    fun onStart() {
        foreground = true
        updateSampler()
    }

    fun onStop() {
        foreground = false
        updateSampler()
        if (enabled) saveReport()
    }

    /** Wraps the activity's key dispatch: [dispatch] is the default handling. */
    inline fun dispatchKey(event: KeyEvent, dispatch: () -> Boolean): Boolean {
        if (!enabled || event.action != KeyEvent.ACTION_DOWN) return dispatch()
        val start = System.nanoTime()
        val handled = dispatch()
        keyHandled(event, start, System.nanoTime())
        return handled
    }

    @PublishedApi
    internal fun keyHandled(event: KeyEvent, startNanos: Long, endNanos: Long) {
        // An unanswered earlier press saw no frame, so only its handling time counts.
        if (keyDownAt != 0L) stats.keyPressed(null, keyHandlingMs)
        keyHandlingMs = (endNanos - startNanos) / 1e6
        keyDispatchedAt = startNanos
        keyDownAt = event.eventTime
    }

    /** The home screen shows the loaded layout and apps. The next frame ends start-up. */
    fun homeReady() {
        if (homeSeen) return
        homeSeen = true
        if (measureStartup && enabled) homeReadyAt = SystemClock.uptimeMillis()
    }

    fun layoutDecoded(ms: Double, nodes: Int) = stats.layoutDecoded(ms, nodes)

    fun appImageLoaded(ms: Double) {
        if (enabled) stats.appImageLoaded(ms)
    }

    override fun nodeComposed(node: Node) {
        stats.nodeComposed("${NodeRegistry.forNode(node)?.type ?: "node"} \"${node.id}\"")
    }

    /** Times every image the layout loads through Coil. */
    val imageEvents = object : EventListener.Factory {
        override fun create(request: ImageRequest): EventListener = if (!enabled) EventListener.NONE else object : EventListener() {
            private val start = System.nanoTime()

            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                val ms = (System.nanoTime() - start) / 1e6
                stats.imageLoaded(ImageLoad(label(request.data), ms, result.dataSource.label(), result.image.width, result.image.height))
            }

            override fun onError(request: ImageRequest, result: ErrorResult) = stats.imageFailed()
        }
    }

    fun live(): LiveStats = stats.live(screen)

    fun report(): PerfReport = stats.report(header())

    /** Writes the report for `adb shell run-as dev.owntv.launcher cat files/metrics/report.txt`. */
    fun saveReport() {
        val text = report().toText()
        scope.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                if (!rotated) {
                    rotated = true
                    if (reportFile.exists()) reportFile.renameTo(previousReportFile)
                }
                reportFile.writeText(text)
            }
        }
    }

    private fun updateListener() {
        val w = window ?: return
        if (enabled && !listening) {
            w.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper))
            listening = true
        } else if (!enabled && listening) {
            runCatching { w.removeOnFrameMetricsAvailableListener(frameListener) }
            listening = false
        }
    }

    private fun updateSampler() {
        val run = enabled && foreground
        if (run && samplerJob == null) {
            samplerJob = scope.launch(Dispatchers.Default) {
                var tick = 0
                while (isActive) {
                    sampleMemory()
                    if (tick > 0 && tick % 15 == 0) saveReport()
                    tick++
                    delay(2000)
                }
            }
        } else if (!run) {
            samplerJob?.cancel()
            samplerJob = null
        }
    }

    private fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        stats.memorySampled(
            MemorySample(
                heapUsed = runtime.totalMemory() - runtime.freeMemory(),
                heapMax = runtime.maxMemory(),
                nativeHeap = Debug.getNativeHeapAllocatedSize(),
                rss = residentBytes(),
                layoutImageCache = runCatching { SingletonImageLoader.get(context).memoryCache?.size ?: 0L }.getOrDefault(0L),
                appImageCache = appImageCacheBytes(),
                gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0,
                gcTimeMs = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0,
            ),
        )
    }

    /**
     * Resident memory from `/proc/self/statm`. Not PSS: reading PSS walks every memory mapping while
     * holding the process's memory map lock, which stalled frames for about 200 ms on the emulator.
     */
    private fun residentBytes(): Long? = runCatching {
        File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * Os.sysconf(OsConstants._SC_PAGESIZE)
    }.getOrNull()

    private fun onFrame(m: FrameMetrics) {
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
        val vsyncNanos = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val endMs = (vsyncNanos / 1e6 + total)
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
            // The first frame of a window is mostly one-off set-up: it counts for start-up only.
            if (measureStartup && !firstFrameSeen) stats.startup(firstFrameMs = endMs - processStart)
            firstFrameSeen = true
            return
        }
        if (homeReadyAt != 0L && !startupDone && vsyncNanos / 1_000_000 >= homeReadyAt) {
            startupDone = true
            stats.startup(homeReadyMs = endMs - processStart)
        }
        val phases = DoubleArray(FramePhase.entries.size)
        phases[FramePhase.Waiting.ordinal] = m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) / 1e6
        phases[FramePhase.Input.ordinal] = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) / 1e6
        phases[FramePhase.Composition.ordinal] = m.getMetric(FrameMetrics.ANIMATION_DURATION) / 1e6
        phases[FramePhase.Layout.ordinal] = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6
        phases[FramePhase.Draw.ordinal] = m.getMetric(FrameMetrics.DRAW_DURATION) / 1e6
        phases[FramePhase.Upload.ordinal] = m.getMetric(FrameMetrics.SYNC_DURATION) / 1e6
        phases[FramePhase.Gpu.ordinal] =
            (m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)) / 1e6
        val budget = if (Build.VERSION.SDK_INT >= 31) {
            m.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 }?.let { it / 1e6 } ?: budgetMs
        } else {
            budgetMs
        }
        val sample = FrameSample(SystemClock.uptimeMillis(), System.currentTimeMillis(), total, phases, screen)
        stats.addFrame(sample, budget)
        if (total > PerfStats.FROZEN_MS) Log.w(TAG, "Frozen frame on ${sample.screen}: ${total.ms()} (${sample.breakdown()})")

        val keyAt = keyDownAt
        if (keyAt != 0L && vsyncNanos >= keyDispatchedAt) {
            keyDownAt = 0L
            val latency = endMs - keyAt
            stats.keyPressed(latency.takeIf { it in 0.0..KEY_TIMEOUT_MS }, keyHandlingMs)
        }
    }

    private fun header(): String {
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        return "OwnTV performance report · $started · ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
            "${refreshRate.toInt()} Hz · mode ${_mode.value.name.lowercase()}"
    }

    private fun label(data: Any): String {
        val text = data.toString()
        return if (text.length <= 72) text else text.take(32) + "…" + text.takeLast(38)
    }

    private fun coil3.decode.DataSource.label() = when (this) {
        coil3.decode.DataSource.MEMORY_CACHE -> "memory"
        coil3.decode.DataSource.MEMORY -> "in-memory data"
        coil3.decode.DataSource.DISK -> "disk"
        coil3.decode.DataSource.NETWORK -> "network"
    }

    companion object {
        private const val TAG = "OwnTvPerf"
        private const val KEY_MODE = "mode"

        /** A press with no frame within this time changed nothing on screen. */
        private const val KEY_TIMEOUT_MS = 1500.0
    }
}
