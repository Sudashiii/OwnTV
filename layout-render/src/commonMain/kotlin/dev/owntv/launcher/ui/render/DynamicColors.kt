package dev.owntv.launcher.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.owntv.layout.color.DynamicPalette
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.ColorBackground
import dev.owntv.layout.model.DynamicColorSource
import dev.owntv.layout.model.DynamicColors
import dev.owntv.layout.model.GlassBackground
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.ImageAdjust
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.SlideshowBackground
import dev.owntv.layout.model.VideoBackground
import dev.owntv.layout.model.Theme
import dev.owntv.layout.model.ThemeColors
import dev.owntv.layout.wallpaper.Wallpapers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** [theme] with its colors turned to [seed] (`theme.dynamicColors`); unchanged without one. */
fun Theme.withDynamicSeed(seed: Int?): Theme {
    val dynamic = dynamicColors ?: return this
    return if (seed == null) this else copy(colors = DynamicPalette.apply(colors, seed, dynamic))
}

/**
 * The color `theme.dynamicColors` follows now: from the [wallpaper], or from the tile [tracker] says
 * is focused. Null keeps the layout's own colors. Focus changes apply once focus settles, so moving
 * along a row does not repaint the screen on every press.
 */
@Composable
internal fun rememberDynamicSeed(dynamic: DynamicColors?, wallpaper: Background?, colors: ThemeColors, tracker: FocusTracker): Int? {
    val wallpaperImage = remember(wallpaper, colors) { wallpaper?.seedImage(colors) }
    var seed by remember(dynamic, wallpaperImage) { mutableStateOf(wallpaperImage?.let(SeedCache::get)) }
    if (dynamic == null) return null
    val context = LocalPlatformContext.current
    val catalog = LocalApps.current
    val images = LocalAppImages.current
    LaunchedEffect(dynamic.source, wallpaperImage, catalog, images) {
        val targets = if (dynamic.source == DynamicColorSource.Focused) {
            snapshotFlow { tracker.current?.seedImage(catalog) ?: wallpaperImage }
        } else {
            flowOf(wallpaperImage)
        }
        var first = true
        targets.distinctUntilChanged().collectLatest { image ->
            if (!first) delay(FOCUS_SETTLE_MS)
            first = false
            seed = image?.let { SeedCache.get(it) ?: it.loadSeed(context, images)?.also { found -> SeedCache.put(it, found) } }
        }
    }
    return seed
}

/** Where a seed color comes from. */
private sealed interface SeedImage {
    data class Solid(val colors: List<Int>) : SeedImage
    /** A picture, colored by [adjust] as it is drawn. */
    data class Url(val src: String, val adjust: ImageAdjust? = null) : SeedImage
    data class Banner(val app: AppInfo) : SeedImage
}

private fun Background.seedImage(colors: ThemeColors): SeedImage? = when (this) {
    is ImageBackground -> SeedImage.Url(src, adjust)
    is ColorBackground -> colors.resolve(color)?.let { SeedImage.Solid(listOf(it)) }
    is GradientBackground -> SeedImage.Solid(this.colors.mapNotNull(colors::resolve)).takeIf { it.colors.isNotEmpty() }
    is GlassBackground, is VideoBackground, is SlideshowBackground -> null
}

private fun FocusedTile.seedImage(catalog: AppCatalog): SeedImage? =
    backdrop?.let(SeedImage::Url)
        ?: app?.let { app -> catalog.override(app.packageName)?.banner?.let(SeedImage::Url) ?: SeedImage.Banner(app) }

private suspend fun SeedImage.loadSeed(context: PlatformContext, images: AppImages?): Int? {
    val pixels = when (this) {
        is SeedImage.Solid -> colors.toIntArray()
        is SeedImage.Url -> {
            val request = ImageRequest.Builder(context).data(imageModel(src)).size(SAMPLE_SIZE).readablePixels().build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
            val pixels = runCatching { result.image.toImageBitmap().pixels() }.getOrNull() ?: return null
            Wallpapers.colorMatrix(adjust)?.let { matrix -> for (i in pixels.indices) pixels[i] = Wallpapers.applyMatrix(pixels[i], matrix) }
            pixels
        }
        is SeedImage.Banner -> images?.load(app, AppImages.Kind.Banner)?.let { runCatching { it.pixels() }.getOrNull() } ?: return null
    }
    return DynamicPalette.seed(pixels)
}

private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }

/** Seeds of the images seen lately, so returning to the home screen keeps its colors from the first frame. */
private object SeedCache {
    private const val MAX_ENTRIES = 48
    private val seeds = LinkedHashMap<SeedImage, Int>()

    fun get(image: SeedImage): Int? = seeds[image]

    fun put(image: SeedImage, seed: Int) {
        seeds.remove(image)
        seeds[image] = seed
        if (seeds.size > MAX_ENTRIES) seeds.remove(seeds.keys.first())
    }
}

/** Images are read at about this size: enough for their colors. */
private const val SAMPLE_SIZE = 96
private const val FOCUS_SETTLE_MS = 350L
