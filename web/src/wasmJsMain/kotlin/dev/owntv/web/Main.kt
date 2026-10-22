package dev.owntv.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import dev.owntv.launcher.ui.render.FontFamilies
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalAppImages
import dev.owntv.launcher.ui.render.LocalApps
import dev.owntv.launcher.ui.render.LocalFontFamilies
import dev.owntv.launcher.ui.render.LocalPreviewMode
import dev.owntv.launcher.ui.render.LocalRenderMetrics
import dev.owntv.layout.codec.DecodeResult
import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.StyleResolver
import dev.owntv.layout.model.screensaverPage
import kotlinx.browser.document
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The TV screen in dp, as on the `owntv_gtv` emulator (1080p). */
private const val TV_WIDTH = 960
private const val TV_HEIGHT = 540

/** What the page shows: the last layout that decoded, its page, and whether metrics are on. */
class PreviewState {
    var doc by mutableStateOf<LayoutDocument?>(null)
    var pageId by mutableStateOf<String?>(null)
    var catalog by mutableStateOf(DemoApps.catalogFor(null))
    var metricsOn by mutableStateOf(false)

    /** Set once a layout contains emoji; the 25 MB emoji font is only loaded then. */
    var needsEmoji by mutableStateOf(false)
    val metrics = Metrics()
    private var lastResult: DecodeResult? = null

    fun render(text: String) {
        if (!needsEmoji && text.any { it.isSurrogate() || it.code in 0x2600..0x27BF }) needsEmoji = true
        val start = TimeSource.Monotonic.markNow()
        val result = LayoutCodec.decode(text)
        val decode = start.elapsedNow()
        lastResult = result
        val decoded = result.document
        if (decoded != null) {
            val resolveStart = TimeSource.Monotonic.markNow()
            val resolve: Duration? = runCatching { StyleResolver.resolve(decoded) }.map { resolveStart.elapsedNow() }.getOrNull()
            metrics.lastEdit = decoded.stats(decode, resolve, text.encodeToByteArray().size)
            metrics.startEdit()
            doc = decoded
            catalog = DemoApps.catalogFor(decoded, result.extractedLists)
            if (decoded.pages.none { it.id == pageId }) pageId = decoded.pages.first().id
        }
        report()
    }

    fun selectPage(id: String) {
        if (doc?.pages?.any { it.id == id } == true) pageId = id
        report()
    }

    /** Tells the page's script about issues and pages. `stale` means the preview still shows an older layout. */
    private fun report() {
        val result = lastResult ?: return
        val shown = doc
        val payload = buildJsonObject {
            put("ok", result.ok)
            put("stale", !result.ok && shown != null)
            putJsonArray("issues") {
                result.issues.forEach { issue ->
                    addJsonObject {
                        put("severity", issue.severity.name.lowercase())
                        put("path", issue.path)
                        put("message", issue.message)
                    }
                }
            }
            putJsonArray("pages") {
                val screensaver = shown?.screensaverPage
                shown?.pages?.forEach { page ->
                    addJsonObject {
                        put("id", page.id)
                        val title = page.title ?: page.id
                        put("title", if (page === screensaver && !title.contains("screensaver", ignoreCase = true)) "$title (screensaver)" else title)
                    }
                }
            }
            put("page", pageId)
        }
        postResult(payload.toString())
    }
}

fun main() {
    val state = PreviewState()
    ComposeViewport(document.getElementById("tv")!!) {
        PreviewApp(state)
    }
    installBridge(
        render = state::render,
        setPage = state::selectPage,
        setMetrics = { state.metricsOn = it },
    )
}

@Composable
private fun PreviewApp(state: PreviewState) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build()
    }
    val resolver = LocalFontFamilyResolver.current
    var sans by remember { mutableStateOf<FontFamily?>(null) }
    var fonts by remember { mutableStateOf<FontFamilies?>(null) }
    // Bumped when the emoji font arrives, so text is laid out again with it as a fallback.
    var fontGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val base = WebFonts.sans()
        sans = base
        fonts = FontFamilies(sans = base, serif = base, mono = base, cursive = base)
        hideLoading()
        state.metrics.rendererReady()
        fonts = WebFonts.all(base)
        fontGeneration++
    }
    if (state.needsEmoji) {
        LaunchedEffect(Unit) {
            runCatching { resolver.preload(WebFonts.emoji()) }.onSuccess { fontGeneration++ }
        }
    }

    val loadedSans = sans ?: return
    val loadedFonts = fonts ?: return
    val measurer = rememberTextMeasurer()
    val images = remember(measurer, loadedSans) { DemoAppImages(measurer, loadedSans) }
    val doc = state.doc

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0A0B0E)), contentAlignment = Alignment.Center) {
        val scale = minOf(maxWidth.value / TV_WIDTH, maxHeight.value / TV_HEIGHT)
        Box(Modifier.size((TV_WIDTH * scale).dp, (TV_HEIGHT * scale).dp).clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
            if (doc != null) {
                CompositionLocalProvider(
                    LocalPreviewMode provides true,
                    LocalApps provides state.catalog,
                    LocalAppImages provides images,
                    LocalFontFamilies provides loadedFonts,
                    LocalRenderMetrics provides state.metrics,
                ) {
                    key(fontGeneration) {
                        Box(Modifier.requiredSize(TV_WIDTH.dp, TV_HEIGHT.dp).graphicsLayer(scaleX = scale, scaleY = scale)) {
                            LayoutView(doc, pageId = state.pageId, onSelectPage = {}, focusTrigger = 0)
                        }
                    }
                    // Runs after the frame that composed this layout, so it measures until it is on screen.
                    LaunchedEffect(doc) {
                        withFrameNanos { }
                        state.metrics.frameDrawn()
                    }
                }
            }
        }
        if (state.metricsOn) {
            MetricsOverlay(state.metrics, loadedFonts.mono, Modifier.align(Alignment.TopStart).padding(12.dp))
        }
    }
}
