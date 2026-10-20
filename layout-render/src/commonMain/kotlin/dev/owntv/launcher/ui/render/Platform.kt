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
import dev.owntv.layout.model.Node

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

/** Turns a layout `asset:` path into a URL the image loader understands. */
expect fun assetUrl(path: String): String

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
