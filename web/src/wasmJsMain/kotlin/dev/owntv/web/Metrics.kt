package dev.owntv.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.owntv.launcher.ui.render.RenderMetrics
import dev.owntv.launcher.ui.render.Text
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.walkNodes
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the last edit cost, and the shape of the layout it produced. */
data class EditStats(
    val decode: Duration,
    val resolve: Duration?,
    val jsonBytes: Int,
    val nodes: Int,
    val depth: Int,
    val pages: Int,
)

fun LayoutDocument.stats(decode: Duration, resolve: Duration?, jsonBytes: Int): EditStats {
    var nodes = 0
    var depth = 0
    walkNodes { _, _, _, d ->
        nodes++
        depth = maxOf(depth, d + 1)
    }
    return EditStats(decode, resolve, jsonBytes, nodes, depth, pages.size)
}

/** Counts renderer work and frame times for the overlay. */
class Metrics : RenderMetrics {
    var compositions = 0L
        private set

    var lastEdit by mutableStateOf<EditStats?>(null)
    var firstFrame by mutableStateOf<Duration?>(null)

    /** Set when a new layout is handed to the renderer; cleared by the first frame that draws it. */
    var pendingEdit: TimeMark? = null

    override fun nodeComposed() {
        compositions++
    }

    fun frameDrawn() {
        pendingEdit?.let {
            firstFrame = it.elapsedNow()
            pendingEdit = null
        }
    }

    fun startEdit() {
        pendingEdit = TimeSource.Monotonic.markNow()
    }

    /** Restarts a pending measurement once the renderer can draw, so font loading is not counted. */
    fun rendererReady() {
        if (pendingEdit != null) startEdit()
    }
}

private data class FrameStats(val fps: Int, val avg: Double, val p95: Double, val max: Double, val history: List<Double>, val compositionsPerSecond: Int)

/** FPS, frame times, edit costs and layout size, drawn over the TV preview while metrics are on. */
@Composable
fun MetricsOverlay(metrics: Metrics, mono: FontFamily, modifier: Modifier = Modifier) {
    var frames by remember { mutableStateOf<FrameStats?>(null) }
    LaunchedEffect(metrics) {
        val intervals = ArrayDeque<Double>()
        var last = 0L
        var windowStart = 0L
        var compositionsAtStart = metrics.compositions
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    intervals.addLast((now - last) / 1_000_000.0)
                    if (intervals.size > 120) intervals.removeFirst()
                }
                last = now
                if (windowStart == 0L) windowStart = now
                val window = now - windowStart
                if (window >= 500_000_000L && intervals.isNotEmpty()) {
                    val sorted = intervals.sorted()
                    val avg = intervals.average()
                    val perSecond = ((metrics.compositions - compositionsAtStart) * 1e9 / window).roundToInt()
                    frames = FrameStats(
                        fps = (1000.0 / avg).roundToInt(),
                        avg = avg,
                        p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)],
                        max = sorted.last(),
                        history = intervals.toList(),
                        compositionsPerSecond = perSecond,
                    )
                    windowStart = now
                    compositionsAtStart = metrics.compositions
                }
            }
        }
    }

    val style = TextStyle(color = Color(0xFFE6E8EC), fontSize = 12.sp, lineHeight = 17.sp, fontFamily = mono)
    val muted = style.copy(color = Color(0xFF9AA0AA), fontSize = 11.sp)
    Column(
        modifier
            .width(360.dp)
            .background(Color(0xE6101216), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val f = frames
        Text(if (f == null) "Measuring frames..." else "${f.fps} fps · frames ${f.avg.ms()} avg, ${f.p95.ms()} p95", style = style)
        if (f != null) Text("Slowest frame of the last 120: ${f.max.ms()}", style = style)
        if (f != null) FrameGraph(f.history, Modifier.fillMaxWidth().height(36.dp).padding(vertical = 6.dp))
        Text("Recompositions: ${f?.compositionsPerSecond ?: 0} nodes/s · ${metrics.compositions} total", style = style)
        metrics.lastEdit?.let { edit ->
            Text("Parse + validate ${edit.decode.ms()} · styles ${edit.resolve?.ms() ?: "-"}", style = style)
            Text("Edit to screen ${metrics.firstFrame?.ms() ?: "-"}", style = style)
            Text("${edit.nodes} nodes · depth ${edit.depth} · ${edit.pages} page${if (edit.pages == 1) "" else "s"} · ${edit.jsonBytes.kb()}", style = style)
        }
        val heap = jsHeapBytes()
        if (heap > 0) Text("JS heap ${(heap / 1_048_576).roundToInt()} MB", style = style)
        Text("Browser timings, for comparing layouts. They are not TV timings.", style = muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FrameGraph(history: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        val budget = 1000.0 / 60
        val top = maxOf(history.maxOrNull() ?: budget, budget * 2)
        val step = size.width / 120f
        history.forEachIndexed { i, ms ->
            val h = (ms / top * size.height).toFloat()
            val x = i * step
            drawLine(
                color = if (ms > budget * 1.5) Color(0xFFFF6B6B) else Color(0xFF6BD49A),
                start = Offset(x, size.height),
                end = Offset(x, size.height - h),
                strokeWidth = step * 0.7f,
            )
        }
        val y = (size.height - budget / top * size.height).toFloat()
        drawLine(Color(0x66FFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

/** Milliseconds with one decimal, or two below 1 ms. */
private fun Double.ms(): String =
    if (this < 1) "${(this * 100).roundToInt() / 100.0} ms" else "${(this * 10).roundToInt() / 10.0} ms"

private fun Duration.ms(): String = (inWholeMicroseconds / 1000.0).ms()

private fun Int.kb(): String = if (this < 1024) "$this B" else "${(this / 102.4).roundToInt() / 10.0} KB"
