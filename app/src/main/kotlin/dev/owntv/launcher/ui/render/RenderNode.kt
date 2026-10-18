package dev.owntv.launcher.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.Arrange
import dev.owntv.layout.model.ClockNode
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.GridNode
import dev.owntv.layout.model.HAlign
import dev.owntv.layout.model.ImageFit
import dev.owntv.layout.model.ImageNode
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.Size
import dev.owntv.layout.model.SpacerNode
import dev.owntv.layout.model.StackNode
import dev.owntv.layout.model.TextAlignment
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.UnknownNode
import dev.owntv.layout.model.VAlign
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import dev.owntv.layout.model.Alignment as NodeAlignment

/**
 * Renders one node. [modifier] carries what the parent decides (weight, align); this function adds
 * the node's own size, background and padding. [fillByDefault] makes width and height fill when unset.
 */
@Composable
fun RenderNode(node: Node, modifier: Modifier = Modifier, fillByDefault: Boolean = false, fillWidthByDefault: Boolean = false) {
    if (!node.visible) return
    val theme = LocalOwnTheme.current
    val fillWidth = fillByDefault || fillWidthByDefault || node is AppRowNode || node is AppGridNode
    var base = modifier
        .nodeSize(node, fillWidth = fillWidth, fillHeight = fillByDefault)
        .then(nodeBackground(node, theme))
        .then(node.padding?.let { Modifier.padding(it.start.dp, it.top.dp, it.end.dp, it.bottom.dp) } ?: Modifier)

    LocalFocusTargets.current?.let { targets ->
        if (targets.targetId != null && targets.targetId == node.id) {
            base = if (node is AppTileNode || node is ShortcutNode) {
                base.focusRequester(targets.requester)
            } else {
                base.focusRequester(targets.requester).focusGroup()
            }
        }
    }

    // Tiles adapt to the width they get when the node or its parent sets one.
    val sizing = TileSizing(adaptive = node.width != null || fillWidthByDefault, fixedHeight = node.height is Size.Fixed)

    when (node) {
        is ColumnNode -> ColumnView(node, base)
        is RowNode -> RowView(node, base)
        is GridNode -> GridView(node, base)
        is StackNode -> StackView(node, base)
        is AppTileNode -> {
            val catalog = LocalApps.current
            val app = catalog[node.packageName]
            val entry = when {
                app != null -> AppEntry.Installed(app)
                node.missing == dev.owntv.layout.model.Missing.Placeholder && catalog.loaded -> AppEntry.NotInstalled(node.packageName)
                else -> null
            }
            if (entry != null) AppTileView(entry, node.tile, sizing, base, labelOverride = node.label)
        }
        is AppRowNode -> AppRowView(node, base)
        is AppGridNode -> AppGridView(node, base)
        is ShortcutNode -> ShortcutView(node, sizing, base)
        is TextNode -> Text(
            text = node.text,
            style = theme.textStyle(node.style, node.bold).copy(textAlign = node.textAlign.toCompose()),
            color = theme.color(node.color) ?: theme.text,
            maxLines = node.maxLines ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = base,
        )
        is ClockNode -> ClockView(node, base)
        is ImageNode -> AsyncImage(
            model = imageModel(node.src),
            contentDescription = null,
            contentScale = node.fit.toContentScale(),
            modifier = base.clip(RoundedCornerShape((node.cornerRadius ?: 0).dp)).alpha(node.alpha),
        )
        is SpacerNode -> Spacer(base)
        is UnknownNode -> UnknownView(node, base)
    }
}

// ---------------------------------------------------------------- containers

@Composable
private fun ColumnView(node: ColumnNode, modifier: Modifier) {
    val scroll = if (node.scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(
        modifier = modifier.then(scroll),
        verticalArrangement = node.arrange.vertical(node.spacing.dp),
        horizontalAlignment = node.contentAlign.toCompose(),
    ) {
        node.children.forEachIndexed { index, child ->
            key(index, child.id) { RenderNode(child, childModifier(child)) }
        }
    }
}

@Composable
private fun RowView(node: RowNode, modifier: Modifier) {
    val scroll = if (node.scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    Row(
        modifier = modifier.then(scroll),
        horizontalArrangement = node.arrange.horizontal(node.spacing.dp),
        verticalAlignment = node.contentAlign.toCompose(),
    ) {
        node.children.forEachIndexed { index, child ->
            key(index, child.id) { RenderNode(child, childModifier(child)) }
        }
    }
}

@Composable
private fun GridView(node: GridNode, modifier: Modifier) {
    val rows = remember(node.children, node.columns) { packGrid(node.children.filter { it.visible }, node.columns) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
                var used = 0
                row.forEach { child ->
                    val span = (child.span ?: 1).coerceIn(1, node.columns)
                    used += span
                    key(child.id) { RenderNode(child, Modifier.weight(span.toFloat()), fillWidthByDefault = true) }
                }
                if (used < node.columns) Spacer(Modifier.weight((node.columns - used).toFloat()))
            }
        }
    }
}

private fun packGrid(children: List<Node>, columns: Int): List<List<Node>> {
    val rows = mutableListOf<MutableList<Node>>()
    var used = columns
    for (child in children) {
        val span = (child.span ?: 1).coerceIn(1, columns)
        if (used + span > columns) {
            rows += mutableListOf<Node>()
            used = 0
        }
        rows.last() += child
        used += span
    }
    return rows
}

@Composable
private fun StackView(node: StackNode, modifier: Modifier) {
    Box(modifier, contentAlignment = node.contentAlign.toCompose()) {
        node.children.forEachIndexed { index, child ->
            key(index, child.id) {
                RenderNode(child, child.align?.let { Modifier.align(it.toCompose()) } ?: Modifier)
            }
        }
    }
}

// ---------------------------------------------------------------- app widgets

@Composable
private fun AppRowView(node: AppRowNode, modifier: Modifier) {
    val catalog = LocalApps.current
    val entries = remember(catalog, node.source, node.missing, node.limit) { catalog.resolve(node.source, node.missing, node.limit) }
    if (entries.isEmpty()) return
    Column(modifier) {
        node.title?.let { SectionTitle(it) }
        LazyRow(
            modifier = Modifier.fillMaxWidth().bleedHorizontally(TILE_BLEED).focusRestorer(),
            contentPadding = PaddingValues(horizontal = TILE_BLEED, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
        ) {
            items(entries, key = { it.packageName }) { entry ->
                AppTileView(entry, node.tile, TileSizing(adaptive = false, fixedHeight = false))
            }
        }
    }
}

@Composable
private fun AppGridView(node: AppGridNode, modifier: Modifier) {
    val catalog = LocalApps.current
    val entries = remember(catalog, node.source, node.missing, node.limit) { catalog.resolve(node.source, node.missing, node.limit) }
    if (entries.isEmpty()) return
    Column(modifier) {
        node.title?.let { SectionTitle(it) }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
            entries.chunked(node.columns).forEach { rowEntries ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
                    rowEntries.forEach { entry ->
                        key(entry.packageName) {
                            AppTileView(entry, node.tile, TileSizing(adaptive = true, fixedHeight = false), Modifier.weight(1f))
                        }
                    }
                    repeat(node.columns - rowEntries.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val theme = LocalOwnTheme.current
    Text(
        text = text,
        style = theme.textStyle(TextStyleName.Title, bold = true),
        color = theme.text,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

// ---------------------------------------------------------------- content widgets

@Composable
private fun ClockView(node: ClockNode, modifier: Modifier) {
    val theme = LocalOwnTheme.current
    val formatter = remember(node.format) {
        runCatching { DateTimeFormatter.ofPattern(node.format, Locale.getDefault()) }.getOrNull()
    }
    val hasSeconds = node.format.contains('s')
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(hasSeconds) {
        while (true) {
            now = LocalDateTime.now()
            val millis = System.currentTimeMillis()
            delay(if (hasSeconds) 1000 - millis % 1000 else 60_000 - millis % 60_000)
        }
    }
    Text(
        text = formatter?.let { runCatching { now.format(it) }.getOrNull() } ?: "--:--",
        style = theme.textStyle(node.style, node.bold).copy(textAlign = node.textAlign.toCompose()),
        color = theme.color(node.color) ?: theme.text,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun UnknownView(node: UnknownNode, modifier: Modifier) {
    val theme = LocalOwnTheme.current
    Box(modifier.background(theme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(
            text = "Unknown widget \"${node.type}\"",
            style = theme.textStyle(TextStyleName.Caption),
            color = theme.textMuted,
        )
    }
}

// ---------------------------------------------------------------- modifiers and mappings

private val TILE_BLEED = 14.dp

/** Widens a child by [amount] on both sides so focus scaling is not clipped at the row's edges. */
private fun Modifier.bleedHorizontally(amount: Dp) = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = amount.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra),
    )
    layout(placeable.width - 2 * extra, placeable.height) { placeable.place(-extra, 0) }
}

private fun Modifier.nodeSize(node: Node, fillWidth: Boolean, fillHeight: Boolean): Modifier {
    val withWidth = when (val w = node.width) {
        Size.Fill -> fillMaxWidth()
        Size.Wrap -> wrapContentWidth()
        is Size.Fixed -> width(w.dp.dp)
        null -> if (fillWidth) fillMaxWidth() else this
    }
    return when (val h = node.height) {
        Size.Fill -> withWidth.fillMaxHeight()
        Size.Wrap -> withWidth.wrapContentHeight()
        is Size.Fixed -> withWidth.height(h.dp.dp)
        null -> if (fillHeight) withWidth.fillMaxHeight() else withWidth
    }
}

private fun nodeBackground(node: Node, theme: OwnTheme): Modifier {
    val color = theme.color(node.background) ?: return Modifier
    // Tiles draw their own surface; for them, background means the area around the tile.
    return Modifier.background(color, RoundedCornerShape((node.cornerRadius ?: 0).dp))
}

private fun ColumnScope.childModifier(child: Node): Modifier {
    var m: Modifier = Modifier
    child.weight?.takeIf { it > 0f }?.let { m = m.weight(it) }
    child.align?.let { m = m.align(it.horizontalPart()) }
    return m
}

private fun RowScope.childModifier(child: Node): Modifier {
    var m: Modifier = Modifier
    child.weight?.takeIf { it > 0f }?.let { m = m.weight(it) }
    child.align?.let { m = m.align(it.verticalPart()) }
    return m
}

private fun Arrange.vertical(spacing: Dp): Arrangement.Vertical = when (this) {
    Arrange.Start -> Arrangement.spacedBy(spacing, ComposeAlignment.Top)
    Arrange.Center -> Arrangement.spacedBy(spacing, ComposeAlignment.CenterVertically)
    Arrange.End -> Arrangement.spacedBy(spacing, ComposeAlignment.Bottom)
    Arrange.SpaceBetween -> Arrangement.SpaceBetween
    Arrange.SpaceAround -> Arrangement.SpaceAround
    Arrange.SpaceEvenly -> Arrangement.SpaceEvenly
}

private fun Arrange.horizontal(spacing: Dp): Arrangement.Horizontal = when (this) {
    Arrange.Start -> Arrangement.spacedBy(spacing, ComposeAlignment.Start)
    Arrange.Center -> Arrangement.spacedBy(spacing, ComposeAlignment.CenterHorizontally)
    Arrange.End -> Arrangement.spacedBy(spacing, ComposeAlignment.End)
    Arrange.SpaceBetween -> Arrangement.SpaceBetween
    Arrange.SpaceAround -> Arrangement.SpaceAround
    Arrange.SpaceEvenly -> Arrangement.SpaceEvenly
}

private fun HAlign.toCompose(): ComposeAlignment.Horizontal = when (this) {
    HAlign.Start -> ComposeAlignment.Start
    HAlign.Center -> ComposeAlignment.CenterHorizontally
    HAlign.End -> ComposeAlignment.End
}

private fun VAlign.toCompose(): ComposeAlignment.Vertical = when (this) {
    VAlign.Top -> ComposeAlignment.Top
    VAlign.Center -> ComposeAlignment.CenterVertically
    VAlign.Bottom -> ComposeAlignment.Bottom
}

private fun NodeAlignment.toCompose(): ComposeAlignment = when (this) {
    NodeAlignment.TopStart -> ComposeAlignment.TopStart
    NodeAlignment.Top -> ComposeAlignment.TopCenter
    NodeAlignment.TopEnd -> ComposeAlignment.TopEnd
    NodeAlignment.Start -> ComposeAlignment.CenterStart
    NodeAlignment.Center -> ComposeAlignment.Center
    NodeAlignment.End -> ComposeAlignment.CenterEnd
    NodeAlignment.BottomStart -> ComposeAlignment.BottomStart
    NodeAlignment.Bottom -> ComposeAlignment.BottomCenter
    NodeAlignment.BottomEnd -> ComposeAlignment.BottomEnd
}

private fun NodeAlignment.horizontalPart(): ComposeAlignment.Horizontal = when (this) {
    NodeAlignment.TopStart, NodeAlignment.Start, NodeAlignment.BottomStart -> ComposeAlignment.Start
    NodeAlignment.Top, NodeAlignment.Center, NodeAlignment.Bottom -> ComposeAlignment.CenterHorizontally
    NodeAlignment.TopEnd, NodeAlignment.End, NodeAlignment.BottomEnd -> ComposeAlignment.End
}

private fun NodeAlignment.verticalPart(): ComposeAlignment.Vertical = when (this) {
    NodeAlignment.TopStart, NodeAlignment.Top, NodeAlignment.TopEnd -> ComposeAlignment.Top
    NodeAlignment.Start, NodeAlignment.Center, NodeAlignment.End -> ComposeAlignment.CenterVertically
    NodeAlignment.BottomStart, NodeAlignment.Bottom, NodeAlignment.BottomEnd -> ComposeAlignment.Bottom
}

private fun TextAlignment.toCompose(): ComposeTextAlign = when (this) {
    TextAlignment.Start -> ComposeTextAlign.Start
    TextAlignment.Center -> ComposeTextAlign.Center
    TextAlignment.End -> ComposeTextAlign.End
}

fun ImageFit.toContentScale(): ContentScale = when (this) {
    ImageFit.Cover -> ContentScale.Crop
    ImageFit.Contain -> ContentScale.Fit
    ImageFit.Fill -> ContentScale.FillBounds
}

