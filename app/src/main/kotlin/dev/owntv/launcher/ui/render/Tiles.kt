package dev.owntv.launcher.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owntv.launcher.data.apps.AppImageLoader
import dev.owntv.layout.model.LabelMode
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.TileShape
import dev.owntv.layout.model.TileSize
import dev.owntv.layout.model.TileStyle

private const val BANNER_RATIO = 16f / 9f

fun TileSize.bannerWidth(): Dp = when (this) {
    TileSize.Small -> 128.dp
    TileSize.Medium -> 160.dp
    TileSize.Large -> 208.dp
}

fun TileSize.iconSize(): Dp = when (this) {
    TileSize.Small -> 56.dp
    TileSize.Medium -> 72.dp
    TileSize.Large -> 96.dp
}

private fun TileStyle.labelMode(): LabelMode = showLabel ?: if (style == TileShape.Banner) LabelMode.Focused else LabelMode.Always

/**
 * How a tile is sized. Fixed tiles use the [TileSize] preset; adaptive tiles fill the width their
 * parent gives them (grid cells, explicit widths) and keep a 16:9 ratio unless [fixedHeight] is set.
 */
data class TileSizing(val adaptive: Boolean, val fixedHeight: Boolean)

/** A focusable surface with the layout's focus style. In preview mode it is a plain, unfocusable box. */
@Composable
fun TileSurface(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    color: Color,
    interactionSource: MutableInteractionSource,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalOwnTheme.current
    val shape = RoundedCornerShape(theme.cornerRadius)
    if (LocalPreviewMode.current) {
        Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center, content = content)
        return
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            contentColor = theme.text,
            focusedContainerColor = color,
            focusedContentColor = theme.text,
            pressedContainerColor = color,
            pressedContentColor = theme.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = theme.focusScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(theme.focusBorderWidth, theme.focusBorderColor), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = if (theme.glow) Glow(elevationColor = theme.focusBorderColor.copy(alpha = 0.55f), elevation = 10.dp) else Glow.None,
        ),
        interactionSource = interactionSource,
        content = content,
    )
}

/** One app tile: banner, icon or card. [entry] may be an app that is not installed. */
@Composable
fun AppTileView(
    entry: AppEntry,
    tile: TileStyle,
    sizing: TileSizing,
    modifier: Modifier = Modifier,
    labelOverride: String? = null,
) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val app = (entry as? AppEntry.Installed)?.app
    val label = labelOverride ?: app?.label ?: entry.packageName
    val target = ContextTarget(label, app, entry.packageName)

    TileFrame(tile, sizing, label, focused, modifier) { surfaceModifier ->
        TileSurface(
            onClick = { if (app != null) handler.openApp(app) else handler.openMissing(entry.packageName) },
            onLongClick = { handler.longPress(target) },
            modifier = surfaceModifier,
            color = theme.surface,
            interactionSource = interaction,
        ) {
            when {
                app == null -> MissingContent(label)
                tile.style == TileShape.Icon -> {
                    val icon = rememberAppImage(app, AppImageLoader.Kind.Icon)
                    if (icon != null) {
                        Image(icon, contentDescription = label, modifier = Modifier.fillMaxSize().padding(8.dp))
                    }
                }
                else -> {
                    val banner = rememberAppImage(app, AppImageLoader.Kind.Banner)
                    if (banner != null) {
                        Image(banner, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        IconFallbackContent(app, label)
                    }
                }
            }
        }
    }
}

/** A shortcut tile with an icon, emoji or image and a label. */
@Composable
fun ShortcutView(node: ShortcutNode, sizing: TileSizing, modifier: Modifier = Modifier) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val catalog = LocalApps.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val launchPackage = (node.action as? LaunchAppAction)?.packageName
    val target = ContextTarget(node.label, launchPackage?.let { catalog[it] }, launchPackage)
    val labelInside = node.tile.style != TileShape.Icon

    TileFrame(node.tile, sizing, node.label, focused, modifier, showOutsideLabel = !labelInside) { surfaceModifier ->
        TileSurface(
            onClick = { handler.perform(node.action) },
            onLongClick = { handler.longPress(target) },
            modifier = surfaceModifier,
            color = theme.color(node.color) ?: theme.surface,
            interactionSource = interaction,
        ) {
            if (node.image != null) {
                AsyncImage(
                    model = imageModel(node.image!!),
                    contentDescription = node.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (labelInside) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                    val wide = maxWidth > maxHeight * 3
                    val iconSize = if (wide) maxHeight * 0.6f else minOf(maxHeight * 0.45f, 56.dp)
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            node.icon?.let { ShortcutIcon(it, iconSize); Spacer(Modifier.width(12.dp)) }
                            TileLabel(node.label, TextStyleName.Title)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            node.icon?.let { ShortcutIcon(it, iconSize); Spacer(Modifier.height(6.dp)) }
                            TileLabel(node.label, TextStyleName.Body)
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ShortcutIcon(node.icon ?: "star", minOf(maxWidth, maxHeight) * 0.55f)
                }
            }
        }
    }
}

/** Lays out a tile surface with its optional label below, sized per [sizing]. */
@Composable
private fun TileFrame(
    tile: TileStyle,
    sizing: TileSizing,
    label: String,
    focused: Boolean,
    modifier: Modifier,
    showOutsideLabel: Boolean = true,
    surface: @Composable (Modifier) -> Unit,
) {
    val theme = LocalOwnTheme.current
    val isIcon = tile.style == TileShape.Icon
    val outsideLabel = showOutsideLabel && tile.labelMode() != LabelMode.Never
    val fixedWidth = if (isIcon) tile.size.iconSize() else tile.size.bannerWidth()

    Column(
        modifier = modifier.zIndex(if (focused) 1f else 0f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val surfaceModifier = when {
            sizing.fixedHeight -> Modifier.fillMaxWidth().weight(1f)
            isIcon -> Modifier.size(tile.size.iconSize())
            sizing.adaptive -> Modifier.fillMaxWidth().aspectRatio(BANNER_RATIO)
            else -> Modifier.size(fixedWidth, fixedWidth / BANNER_RATIO)
        }
        surface(surfaceModifier)
        if (outsideLabel) {
            val visible = tile.labelMode() == LabelMode.Always || focused
            val labelWidth = if (sizing.adaptive) Modifier.fillMaxWidth() else Modifier.width(maxOf(fixedWidth, 88.dp))
            Text(
                text = label,
                style = theme.textStyle(TextStyleName.Caption).copy(fontSize = 13.sp * theme.theme.fontScale),
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = labelWidth.padding(top = 8.dp).alpha(if (visible) 1f else 0f),
            )
        }
    }
}

@Composable
private fun MissingContent(label: String) {
    val theme = LocalOwnTheme.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        Text(label, style = theme.textStyle(TextStyleName.Caption), color = theme.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("Not installed", style = theme.textStyle(TextStyleName.Caption), color = theme.textMuted.copy(alpha = 0.7f))
    }
}

@Composable
private fun IconFallbackContent(app: dev.owntv.launcher.data.apps.AppInfo, label: String) {
    val theme = LocalOwnTheme.current
    val icon = rememberAppImage(app, AppImageLoader.Kind.Icon)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(6.dp)) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(label, style = theme.textStyle(TextStyleName.Caption), color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TileLabel(text: String, style: TextStyleName) {
    val theme = LocalOwnTheme.current
    Text(text, style = theme.textStyle(style, bold = true), color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}

private val namedIcons: Map<String, ImageVector> = mapOf(
    "settings" to Icons.Filled.Settings,
    "home" to Icons.Filled.Home,
    "search" to Icons.Filled.Search,
    "star" to Icons.Filled.Star,
    "info" to Icons.Filled.Info,
    "play" to Icons.Filled.PlayArrow,
    "favorite" to Icons.Filled.Favorite,
    "apps" to Icons.AutoMirrored.Filled.List,
    "add" to Icons.Filled.Add,
    "refresh" to Icons.Filled.Refresh,
    "notifications" to Icons.Filled.Notifications,
    "person" to Icons.Filled.Person,
    "lock" to Icons.Filled.Lock,
    "menu" to Icons.Filled.Menu,
)

/** A named Material icon, or the string itself (an emoji) as text. */
@Composable
fun ShortcutIcon(icon: String, size: Dp) {
    val theme = LocalOwnTheme.current
    val vector = namedIcons[icon]
    if (vector != null) {
        Icon(vector, contentDescription = null, tint = theme.text, modifier = Modifier.size(size))
    } else {
        Text(icon, fontSize = (size.value * 0.8f).sp, color = theme.text, maxLines = 1)
    }
}

/** Maps a layout image source to something Coil can load. */
fun imageModel(src: String): String =
    if (src.startsWith("asset:")) "file:///android_asset/" + src.removePrefix("asset:").trimStart('/') else src
