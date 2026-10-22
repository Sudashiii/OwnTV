package dev.owntv.launcher.ui.render

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.owntv.layout.model.FocusMotion
import dev.owntv.layout.model.LabelMode
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.ListRef
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

private fun TileStyle.labelMode(): LabelMode = showLabel ?: if (shape == TileShape.Banner) LabelMode.Focused else LabelMode.Always

internal fun TileStyle.ratio(): Float = aspectRatio ?: if (shape == TileShape.Icon) 1f else BANNER_RATIO

internal fun TileStyle.fixedWidth(): Dp = width?.dp ?: if (shape == TileShape.Icon) size.iconSize() else size.bannerWidth()

/**
 * How a tile is sized. Fixed tiles use the [TileStyle] size; adaptive tiles fill the width their
 * parent gives them (grid cells, explicit widths) and keep the tile's aspect ratio unless [fixedHeight] is set.
 */
data class TileSizing(val adaptive: Boolean, val fixedHeight: Boolean)

/**
 * A focusable surface with the layout's focus style, drawn by [LocalInteractiveSurfaces]. In preview
 * mode, or without surfaces, it is a plain, unfocusable box.
 */
@Composable
fun TileSurface(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    color: Color,
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp? = null,
    focusedScale: Float? = null,
    rim: Boolean = false,
    focusedColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalOwnTheme.current
    val shape = RoundedCornerShape(cornerRadius ?: theme.cornerRadius)
    val surfaces = LocalInteractiveSurfaces.current
    if (LocalPreviewMode.current || surfaces == null) {
        Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center) {
            content()
            if (rim) TileRim(shape)
        }
        return
    }
    val focused by interactionSource.collectIsFocusedAsState()
    val effects = theme.theme.focus
    val phase = rememberSwayPhase(focused && effects.motion == FocusMotion.Sway)
    val tileColor = if (focused && focusedColor != null) focusedColor else color
    // The zoom is animated here and only read while drawing, so a tile zooming in or out does not
    // recompose every frame (tv-material's own scale does, which stutters when the remote is held).
    val zoom = animateFloatAsState(if (focused) focusedScale ?: theme.focusScale else 1f, tween(if (focused) 150 else 200))
    val zoomLayer = Modifier.graphicsLayer {
        scaleX = zoom.value
        scaleY = zoom.value
    }
    surfaces.Tile(onClick, onLongClick, modifier.then(zoomLayer).sway(effects.motion, focused, phase), shape, tileColor, 1f, interactionSource) {
        content()
        if (rim) TileRim(shape)
        if (focused && effects.highlight) FocusSheen(phase)
    }
}

/** Tells the page's [FocusTracker] which tile is focused, for heroes and the {focused} placeholder. */
@Composable
fun ReportFocus(focused: Boolean, tile: () -> FocusedTile) {
    val tracker = LocalFocusTracker.current ?: return
    LaunchedEffect(focused) {
        if (focused) tracker.current = tile()
    }
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
    val catalog = LocalApps.current
    val ancestors = LocalAncestors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val app = (entry as? AppEntry.Installed)?.app
    val override = catalog.override(entry.packageName)
    val label = labelOverride ?: app?.let(catalog::labelOf) ?: entry.packageName
    val list = LocalListContext.current
    val arranger = LocalAppArranger.current
    val moving = list != null && arranger != null && arranger.moving == MovingApp(list.ref, entry.packageName)

    ReportFocus(focused) { FocusedTile(label, app, override?.description, override?.backdrop, ancestors) }

    // While moving, the tile keeps focus wherever it lands (in a grid it may change lines and
    // be composed anew), and the remote moves it instead of the focus.
    val moveFocus = remember { FocusRequester() }
    if (moving) {
        val position = list!!.order.indexOf(entry.packageName)
        LaunchedEffect(position) { runCatching { moveFocus.requestFocus() } }
    }
    val moveKeys = if (!moving) Modifier else Modifier.focusRequester(moveFocus).onPreviewKeyEvent { event ->
        val order = list!!.order
        val delta = when (event.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            Key.DirectionUp -> -list.columns
            Key.DirectionDown -> list.columns
            else -> null
        }
        when {
            delta != null -> {
                // Rows only move sideways; Up and Down stay put so focus cannot leave mid-move.
                if (event.type == KeyEventType.KeyDown && (list.columns > 1 || delta == -1 || delta == 1)) arranger!!.step(order, delta)
                true
            }
            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                if (event.type == KeyEventType.KeyUp) arranger!!.drop()
                true
            }
            event.key == Key.Back || event.key == Key.Escape -> {
                if (event.type == KeyEventType.KeyUp) arranger!!.cancel()
                true
            }
            else -> false
        }
    }

    TileFrame(tile, sizing, label, focused, modifier.then(moveKeys)) { surfaceModifier ->
        TileSurface(
            onClick = { if (app != null) handler.openApp(app) else handler.openMissing(entry.packageName) },
            // The menu target is built on demand: a new one per composition would keep the tile from skipping.
            onLongClick = { if (!moving) handler.longPress(ContextTarget(label, app, entry.packageName, ancestors.lastOrNull(), list)) },
            modifier = surfaceModifier,
            color = theme.color(override?.color) ?: theme.surface,
            interactionSource = interaction,
            cornerRadius = tile.cornerRadius?.dp,
            rim = tile.rim,
        ) {
            if (catalog.showHidden && catalog.isHidden(entry.packageName)) DimmedOverlay()
            if (moving) MovingOverlay()
            when {
                app == null -> MissingContent(label)
                tile.shape == TileShape.Icon -> {
                    val custom = override?.icon
                    if (custom != null) {
                        AsyncImage(imageModel(custom), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        rememberAppImage(app, AppImages.Kind.Icon)?.let {
                            Image(it, contentDescription = label, modifier = Modifier.fillMaxSize().padding(8.dp))
                        }
                    }
                }
                else -> {
                    val custom = override?.banner
                    val banner = if (custom == null) rememberAppImage(app, AppImages.Kind.Banner) else null
                    when {
                        custom != null -> AsyncImage(imageModel(custom), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        banner != null -> Image(banner, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else -> IconFallbackContent(app, label)
                    }
                }
            }
        }
    }
}

/** Marks a tile that is being moved: an accent outline and arrows. Drawn under the artwork's edge. */
@Composable
private fun BoxScope.MovingOverlay() {
    val theme = LocalOwnTheme.current
    Box(Modifier.matchParentSize().zIndex(2f).border(3.dp, theme.primary, RoundedCornerShape(theme.cornerRadius)))
    Row(
        Modifier.matchParentSize().zIndex(2f).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("‹", "›").forEach {
            Text(it, color = Color.White, style = TextStyle(fontSize = 22.sp), modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp))
        }
    }
}

/** Dims a hidden app in the app drawer. */
@Composable
private fun BoxScope.DimmedOverlay() {
    Box(Modifier.matchParentSize().zIndex(2f).background(Color(0x99000000)))
}

/** The tile an empty list shows: OK opens the screen to pick its apps. */
@Composable
fun AddAppsTile(ref: ListRef, title: String?, tile: TileStyle, sizing: TileSizing, modifier: Modifier = Modifier) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    TileFrame(tile, sizing, "Add apps", focused, modifier, showOutsideLabel = tile.shape == TileShape.Icon) { surfaceModifier ->
        TileSurface(
            onClick = { handler.editList(ref, title) },
            onLongClick = { handler.editList(ref, title) },
            modifier = surfaceModifier,
            color = theme.surface,
            interactionSource = interaction,
            cornerRadius = tile.cornerRadius?.dp,
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = theme.text, modifier = Modifier.size(28.dp))
                if (tile.shape != TileShape.Icon) Text("Add apps", style = theme.textStyle(TextStyleName.Caption), color = theme.text)
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
    val ancestors = LocalAncestors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val launchPackage = (node.action as? LaunchAppAction)?.packageName
    val labelInside = node.tile.shape != TileShape.Icon

    ReportFocus(focused) {
        FocusedTile(node.label, launchPackage?.let { catalog[it] }, node.description, node.backdrop, ancestors)
    }

    val iconColor = theme.color(node.iconColor)
    val contentColor = if (focused && !LocalPreviewMode.current) theme.color(node.focusIconColor) ?: iconColor else iconColor

    TileFrame(node.tile, sizing, node.label, focused, modifier, showOutsideLabel = !labelInside) { surfaceModifier ->
        TileSurface(
            onClick = { handler.perform(node.action) },
            onLongClick = { handler.longPress(ContextTarget(node.label, launchPackage?.let { catalog[it] }, launchPackage, ancestors.lastOrNull())) },
            modifier = surfaceModifier,
            color = theme.color(node.color) ?: theme.surface,
            interactionSource = interaction,
            cornerRadius = node.tile.cornerRadius?.dp,
            rim = node.tile.rim,
            focusedColor = theme.color(node.focusColor),
        ) {
            CompositionLocalProvider(LocalTileContentColor provides contentColor) {
                if (node.image != null) {
                    AsyncImage(
                        model = imageModel(node.image!!),
                        contentDescription = node.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (labelInside && node.tile.showLabel != LabelMode.Never) {
                    IconAndLabel(node.icon, node.label)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ShortcutIcon(node.icon ?: "star", minOf(maxWidth, maxHeight) * 0.55f)
                    }
                }
            }
        }
    }
}

/** Icon above a label, or beside it when the tile is very wide. */
@Composable
fun IconAndLabel(icon: String?, label: String) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        val wide = maxWidth > maxHeight * 3
        val iconSize = if (wide) maxHeight * 0.6f else minOf(maxHeight * 0.45f, 56.dp)
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                icon?.let { ShortcutIcon(it, iconSize); Spacer(Modifier.width(12.dp)) }
                TileLabel(label, TextStyleName.Title)
            }
        } else {
            // Small tiles (round input buttons, say) get a smaller label so short names still fit.
            val labelStyle = if (maxWidth < 64.dp) TextStyleName.Caption else TextStyleName.Body
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                icon?.let { ShortcutIcon(it, iconSize); Spacer(Modifier.height(6.dp)) }
                TileLabel(label, labelStyle)
            }
        }
    }
}

/** Lays out a tile surface with its optional label below, sized per [sizing]. */
@Composable
fun TileFrame(
    tile: TileStyle,
    sizing: TileSizing,
    label: String,
    focused: Boolean,
    modifier: Modifier,
    showOutsideLabel: Boolean = true,
    surface: @Composable (Modifier) -> Unit,
) {
    val theme = LocalOwnTheme.current
    val isIcon = tile.shape == TileShape.Icon
    val outsideLabel = showOutsideLabel && tile.labelMode() != LabelMode.Never
    val width = tile.fixedWidth()

    Column(
        modifier = modifier.zIndex(if (focused) 1f else 0f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val surfaceModifier = when {
            sizing.fixedHeight -> Modifier.fillMaxWidth().weight(1f)
            isIcon && tile.width == null -> Modifier.size(tile.size.iconSize())
            sizing.adaptive -> Modifier.fillMaxWidth().aspectRatio(tile.ratio())
            else -> Modifier.size(width, width / tile.ratio())
        }
        surface(surfaceModifier)
        if (outsideLabel) {
            val visible = tile.labelMode() == LabelMode.Always || focused
            val labelWidth = if (sizing.adaptive) Modifier.fillMaxWidth() else Modifier.width(maxOf(width, 88.dp))
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
private fun IconFallbackContent(app: AppInfo, label: String) {
    val theme = LocalOwnTheme.current
    val icon = rememberAppImage(app, AppImages.Kind.Icon)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(6.dp)) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(label, style = theme.textStyle(TextStyleName.Caption), color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TileLabel(text: String, style: TextStyleName) {
    val theme = LocalOwnTheme.current
    Text(text, style = theme.textStyle(style, bold = true), color = LocalTileContentColor.current ?: theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
    "info_outline" to ExtraIcons.InfoOutline,
    "add_circle" to ExtraIcons.AddCircle,
    "tv" to ExtraIcons.Tv,
    "live_tv" to ExtraIcons.LiveTv,
    "input" to ExtraIcons.Input,
    "hdmi" to ExtraIcons.Hdmi,
    "smartphone" to ExtraIcons.Smartphone,
    "display" to ExtraIcons.Display,
    "system" to ExtraIcons.System,
    "accessibility" to ExtraIcons.Accessibility,
    "android" to ExtraIcons.Android,
    "wallpaper" to ExtraIcons.Wallpaper,
    "wifi" to ExtraIcons.Wifi,
    "wifi_off" to ExtraIcons.WifiOff,
    "power" to ExtraIcons.Power,
    "ethernet" to ExtraIcons.Ethernet,
)

/** The metrics of the TV's default body text, so emoji icons sit where they always did. */
private val EmojiStyle = TextStyle(
    lineHeight = 24.sp,
    letterSpacing = 0.5.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Color of icons and labels drawn on a tile; null means the theme's text color. */
private val LocalTileContentColor = compositionLocalOf<Color?> { null }

/** A named Material icon, or the string itself (an emoji) as text. [tint] defaults to the tile's content color. */
@Composable
fun ShortcutIcon(icon: String, size: Dp, tint: Color? = null) {
    val color = tint ?: LocalTileContentColor.current ?: LocalOwnTheme.current.text
    val vector = namedIcons[icon]
    if (vector != null) {
        Icon(vector, contentDescription = null, tint = color, modifier = Modifier.size(size))
    } else {
        Text(icon, fontSize = (size.value * 0.8f).sp, color = color, maxLines = 1, style = EmojiStyle)
    }
}

/** Maps a layout image source to something Coil can load. */
fun imageModel(src: String): String =
    if (src.startsWith("asset:")) assetUrl(src.removePrefix("asset:").trimStart('/')) else src
