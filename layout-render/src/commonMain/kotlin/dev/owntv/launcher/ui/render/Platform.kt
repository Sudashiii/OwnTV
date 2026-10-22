package dev.owntv.launcher.ui.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import coil3.request.ImageRequest
import dev.owntv.layout.model.ImageFit
import dev.owntv.layout.model.Node
import dev.owntv.layout.wallpaper.FolderFile

// What the renderer needs from the platform. The TV app and the web preview each provide these.

/** An installed app as the renderer sees it. [activityName] is the class of its launcher activity. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val activityName: String,
    val isGame: Boolean,
    /** True when the app has a TV (leanback) launcher entry; false for phone apps sideloaded onto the TV. */
    val isTvApp: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
)

/** App banners and icons. The TV reads them from the package manager; the web preview draws stand-ins. */
interface AppImages {
    enum class Kind { Banner, Icon }

    fun cached(app: AppInfo, kind: Kind): ImageBitmap?

    suspend fun load(app: AppInfo, kind: Kind): ImageBitmap?
}

/**
 * Draws the focusable, clickable parts of a layout. The TV app provides one built on tv-material.
 * Without one (and in preview mode) tiles and page tabs are plain boxes.
 */
interface InteractiveSurfaces {
    @Composable
    fun Tile(
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        modifier: Modifier,
        shape: Shape,
        color: Color,
        focusedScale: Float,
        interactionSource: MutableInteractionSource,
        content: @Composable BoxScope.() -> Unit,
    )

    /** A page tab; [content] gets the color its label should use. */
    @Composable
    fun PageTab(
        onClick: () -> Unit,
        selected: Boolean,
        modifier: Modifier,
        content: @Composable (contentColor: Color) -> Unit,
    )
}

val LocalInteractiveSurfaces = staticCompositionLocalOf<InteractiveSurfaces?> { null }

/** The font families behind [dev.owntv.layout.model.FontName]. The web has no system fonts and bundles its own. */
data class FontFamilies(
    val sans: FontFamily = FontFamily.SansSerif,
    val serif: FontFamily = FontFamily.Serif,
    val mono: FontFamily = FontFamily.Monospace,
    val cursive: FontFamily = FontFamily.Cursive,
)

val LocalFontFamilies = staticCompositionLocalOf { FontFamilies() }

/** How the device is online, for the network widget. */
enum class NetworkState { Wifi, Ethernet, Offline }

/** The device's connection. The TV app follows the system; the web preview shows Wi-Fi. */
val LocalNetworkState = compositionLocalOf { NetworkState.Wifi }

/** Counts renderer work for the web preview's metrics. Null on the TV. */
interface RenderMetrics {
    /** [node] was composed (first time or again). Called from a side effect on the main thread. */
    fun nodeComposed(node: Node)
}

val LocalRenderMetrics = staticCompositionLocalOf<RenderMetrics?> { null }

/**
 * Pictures and videos in a folder on the device, for slideshow backgrounds. The TV reads its shared
 * storage; the web preview has none.
 */
interface WallpaperFolders {
    /** Changes (as snapshot state) when the folders may read differently, e.g. once access is granted. */
    val generation: Int

    /** The pictures and videos in [path] and its subfolders, by name; empty when there are none or they cannot be read. */
    suspend fun list(path: String): List<FolderFile>
}

val LocalWallpaperFolders = staticCompositionLocalOf<WallpaperFolders?> { null }

/** Plays video backgrounds. The TV app provides one; without it videos show a placeholder. */
interface VideoPlayers {
    /**
     * A silent, looping video filling [modifier], scaled by [fit] and colored by [colorMatrix]
     * (see [dev.owntv.layout.wallpaper.Wallpapers.colorMatrix]). It plays only while [playing].
     */
    @Composable
    fun Video(src: String, fit: ImageFit, colorMatrix: FloatArray?, playing: Boolean, modifier: Modifier)
}

val LocalVideoPlayers = staticCompositionLocalOf<VideoPlayers?> { null }

/**
 * True while the layout is covered by another screen (the TV keeps the home screen composed under
 * its other screens): videos pause and slideshows wait.
 */
val LocalBackgroundPaused = compositionLocalOf { false }

/** Turns a layout `asset:` path into a URL the image loader understands. */
expect fun assetUrl(path: String): String

/** Asks for an image whose pixels can be read (on Android: not a hardware bitmap). */
internal expect fun ImageRequest.Builder.readablePixels(): ImageRequest.Builder

/** A loaded image as a Compose bitmap. */
internal expect fun coil3.Image.toImageBitmap(): ImageBitmap

/** Text without a Material library: [BasicText] with the color and overrides the renderer uses. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = TextStyle.Default,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(color = color.takeOrElse { style.color }, fontSize = fontSize, textAlign = textAlign),
        overflow = overflow,
        maxLines = maxLines,
    )
}

/** A vector icon tinted with [tint]. */
@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    Image(imageVector, contentDescription, modifier, colorFilter = ColorFilter.tint(tint))
}
