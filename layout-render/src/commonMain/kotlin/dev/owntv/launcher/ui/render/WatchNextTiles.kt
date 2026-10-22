package dev.owntv.launcher.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.TileStyle
import dev.owntv.layout.model.WatchNext
import dev.owntv.layout.model.WatchNextEntry

/** One "continue watching" tile: the entry's poster, a progress bar along the bottom, OK resumes it. */
@Composable
fun WatchNextTileView(entry: WatchNextEntry, tile: TileStyle, showProgress: Boolean, sizing: TileSizing, modifier: Modifier = Modifier) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val catalog = LocalApps.current
    val ancestors = LocalAncestors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val appLabel = catalog[entry.packageName]?.let(catalog::labelOf)
    val description = remember(entry, appLabel) { WatchNext.description(entry, appLabel).ifEmpty { null } }

    ReportFocus(focused) { FocusedTile(entry.title, null, description, entry.posterUri, ancestors) }

    TileFrame(tile, sizing, entry.title, focused, modifier) { surfaceModifier ->
        TileSurface(
            onClick = { handler.openWatchNext(entry) },
            onLongClick = {},
            modifier = surfaceModifier,
            color = theme.surface,
            interactionSource = interaction,
            cornerRadius = tile.cornerRadius?.dp,
            rim = tile.rim,
        ) {
            if (entry.posterUri != null) {
                AsyncImage(imageModel(entry.posterUri!!), contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                PosterFallback(entry.title)
            }
            if (showProgress) entry.progress?.let { ProgressBar(it) }
        }
    }
}

/** The tile a row shows until the TV lets OwnTV read the entries: OK asks for permission. */
@Composable
fun AllowWatchNextTile(tile: TileStyle, sizing: TileSizing, modifier: Modifier = Modifier, onAsked: () -> Unit = {}) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = "Allow access to continue watching"
    TileFrame(tile, sizing, label, focused, modifier, showOutsideLabel = false) { surfaceModifier ->
        TileSurface(
            onClick = { onAsked(); handler.allowWatchNext() },
            onLongClick = { onAsked(); handler.allowWatchNext() },
            modifier = surfaceModifier,
            color = theme.surface,
            interactionSource = interaction,
            cornerRadius = tile.cornerRadius?.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = theme.text, modifier = Modifier.size(28.dp))
                Text(label, style = theme.textStyle(TextStyleName.Caption), color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Stand-in for an entry without a poster: a color taken from the title, with the title on it. */
@Composable
private fun BoxScope.PosterFallback(title: String) {
    val theme = LocalOwnTheme.current
    val hue = remember(title) { (title.hashCode().toUInt() % 360u).toFloat() }
    val top = remember(hue) { Color.hsv(hue, 0.45f, 0.55f) }
    val bottom = remember(hue) { Color.hsv((hue + 30f) % 360f, 0.55f, 0.3f) }
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        Text(
            title,
            style = theme.textStyle(TextStyleName.Caption, bold = true),
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

/** How far the entry is watched, a thin bar along the bottom edge of the tile. */
@Composable
private fun BoxScope.ProgressBar(progress: Float) {
    val theme = LocalOwnTheme.current
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(theme.primary))
    }
}
