package dev.owntv.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.metrics.LiveStats
import dev.owntv.launcher.metrics.MetricsMode
import dev.owntv.launcher.metrics.PerfStats
import dev.owntv.launcher.metrics.PerformanceMonitor
import dev.owntv.launcher.metrics.ms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun metricsSummary(mode: MetricsMode): String = when (mode) {
    MetricsMode.Off -> "Off · find out what makes the launcher slow"
    MetricsMode.Background -> "Measuring in the background · see the results"
    MetricsMode.Live -> "Live overlay on · see the results"
}

/** Settings > Performance metrics: the mode, and the report of what was measured so far. */
@Composable
fun PerformanceScreen(container: AppContainer) {
    val perf = container.perf
    val mode by perf.mode.collectAsStateWithLifecycle()
    var resets by remember { mutableIntStateOf(0) }
    val report by produceState(perf.report(), resets) {
        while (true) {
            value = withContext(Dispatchers.Default) { perf.report() }
            delay(1000)
        }
    }

    SettingsScaffold(
        "Performance metrics",
        "Measures frames, remote response, images and memory to find what makes the launcher slow. Measuring costs a little itself.",
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                val initial = rememberInitialFocus()
                ModeItem("Off", "Nothing is measured", MetricsMode.Off, mode, perf, if (mode == MetricsMode.Off) initial else Modifier)
                ModeItem("Background", "Measures quietly; the results are here", MetricsMode.Background, mode, perf, if (mode == MetricsMode.Background) initial else Modifier)
                ModeItem("Live overlay", "Also shows a small see-through panel over the launcher", MetricsMode.Live, mode, perf, if (mode == MetricsMode.Live) initial else Modifier)
                SettingsItem("Reset", "Start a new measurement session", enabled = report.frameCount > 0 || report.keys > 0) {
                    perf.reset()
                    resets++
                }
                SettingsItem("Save report file", "adb shell run-as dev.owntv.launcher cat files/metrics/report.txt") {
                    perf.saveReport()
                    container.actions.toast("Saved files/metrics/report.txt")
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                if (mode == MetricsMode.Off && report.frameCount == 0L) {
                    ReportSection("Nothing measured", "Choose Background or Live overlay, use the launcher for a while, then come back here.")
                } else {
                    report.sections().forEach { (title, body) -> ReportSection(title, body) }
                }
            }
        }
    }
}

@Composable
private fun ModeItem(
    title: String,
    subtitle: String,
    value: MetricsMode,
    current: MetricsMode,
    perf: PerformanceMonitor,
    modifier: Modifier,
) {
    ListItem(
        selected = value == current,
        onClick = { perf.setMode(value) },
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = if (value == current) {
            { Icon(Icons.Default.Check, contentDescription = "Selected") }
        } else {
            null
        },
    )
}

/** Focusable, so the remote can scroll through the report. */
@Composable
private fun ReportSection(title: String, body: String) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
    )
}

/** The live panel: last second of frames, the last slow frame, remote response and memory. It never takes focus. */
@Composable
fun PerformanceOverlay(perf: PerformanceMonitor, modifier: Modifier = Modifier) {
    val live by produceState<Pair<LiveStats, Int>?>(null) {
        var previous = -1L
        while (true) {
            val stats = perf.live()
            val perSecond = if (previous < 0) 0 else ((stats.compositions - previous) * 1000 / OVERLAY_INTERVAL_MS).toInt()
            previous = stats.compositions
            value = stats to perSecond
            delay(OVERLAY_INTERVAL_MS)
        }
    }
    val (stats, perSecond) = live ?: return
    val style = TextStyle(color = Color(0xE6FFFFFF), fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
    val muted = style.copy(color = Color(0xB3FFFFFF))
    Column(
        modifier
            // Its own layer: an update redraws the panel only, not the launcher under it.
            .graphicsLayer()
            .width(260.dp)
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            if (stats.fps == 0) "idle · no frames in the last second"
            else "${stats.fps} fps · p95 ${stats.p95?.ms()} · ${stats.slowPercent}% slow",
            style = style,
        )
        FrameGraph(stats.history, stats.budgetMs, Modifier.fillMaxWidth().height(22.dp).padding(vertical = 3.dp))
        stats.lastSlow?.let {
            Text("last slow frame ${it.totalMs.ms()}, ${stats.lastSlowAgoMs / 1000} s ago", style = muted)
            Text("  ${it.breakdown(2)}", style = muted)
        }
        Text("remote ${stats.keyMs?.ms() ?: "-"} · recompose $perSecond/s", style = style)
        stats.memory?.let { m ->
            Text(
                "heap ${m.heapUsed / 1_048_576}/${m.heapMax / 1_048_576} MB" + (m.rss?.let { " · rss ${it / 1_048_576} MB" } ?: ""),
                style = style,
            )
        }
        Text(stats.screen, style = muted)
    }
}

@Composable
private fun FrameGraph(history: List<Double>, budgetMs: Double, modifier: Modifier) {
    Canvas(modifier) {
        val top = maxOf(history.maxOrNull() ?: budgetMs, budgetMs * 2)
        val step = size.width / PerfStats.LIVE_HISTORY
        history.forEachIndexed { i, ms ->
            val h = (ms / top * size.height).toFloat()
            drawLine(
                color = if (ms > budgetMs) Color(0xCCFF6B6B) else Color(0xCC6BD49A),
                start = Offset(i * step, size.height),
                end = Offset(i * step, size.height - h),
                strokeWidth = step * 0.7f,
            )
        }
        val y = (size.height - budgetMs / top * size.height).toFloat()
        drawLine(Color(0x66FFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

private const val OVERLAY_INTERVAL_MS = 500L
