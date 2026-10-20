package dev.owntv.launcher.ui.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.Arrange
import dev.owntv.layout.model.ClockNode
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.GlassBackground
import dev.owntv.layout.model.GridNode
import dev.owntv.layout.model.HAlign
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.ImageFit
import dev.owntv.layout.model.ImageNode
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.Missing
import dev.owntv.layout.model.NetworkNode
import dev.owntv.layout.model.listRef
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.ScrollAlign
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.Size
import dev.owntv.layout.model.SpacerNode
import dev.owntv.layout.model.StackNode
import dev.owntv.layout.model.TextAlignment
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.TileShape
import dev.owntv.layout.model.TileStyle
import dev.owntv.layout.model.UnknownNode
import dev.owntv.layout.model.VAlign
import dev.owntv.layout.model.isSingleTile
import dev.owntv.layout.model.visible
import dev.owntv.layout.time.Clocks
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import dev.owntv.layout.model.Alignment as NodeAlignment

/**
 * Renders one node. [modifier] carries what the parent decides (weight, align); this function adds
 * the node's own size, focus behavior, decoration and padding. [fillByDefault] makes width and
 * height fill when unset; [fillWidthByDefault] only the width (grid cells).
 */
@Composable
fun RenderNode(node: Node, modifier: Modifier = Modifier, fillByDefault: Boolean = false, fillWidthByDefault: Boolean = false) {
    if (!node.visible) return
    val showWhenFocused = node.common.showWhenFocused
    if (showWhenFocused == null) {
        NodeBody(node, modifier, fillByDefault, fillWidthByDefault)
        return
    }
    // Shown only while focus is inside another node. Previews have no focus, so it stays hidden there.
    val tracker = LocalFocusTracker.current
    val shown = !LocalPreviewMode.current && tracker?.current?.ancestors?.contains(showWhenFocused) == true
    // While it fades out, {focused} and {description} keep naming the last tile focused inside, not the new one.
    val held = remember { FocusTracker() }
    SideEffect { if (shown) held.current = tracker?.current }
    CompositionLocalProvider(LocalPlaceholderFocus provides held) {
        if (node.common.keepSpace) {
            // Keeps its place in the layout and only fades, so the nodes around it stay put.
            val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200))
            val fade = Modifier.graphicsLayer {
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            NodeBody(node, modifier.then(fade), fillByDefault, fillWidthByDefault)
        } else {
            AnimatedVisibility(
                visible = shown,
                modifier = modifier,
                enter = fadeIn(tween(200)) + expandVertically(tween(200), expandFrom = ComposeAlignment.Top),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200), shrinkTowards = ComposeAlignment.Top),
            ) {
                NodeBody(node, Modifier, fillByDefault, fillWidthByDefault)
            }
        }
    }
}

@Composable
private fun NodeBody(node: Node, modifier: Modifier, fillByDefault: Boolean, fillWidthByDefault: Boolean) {
    val metrics = LocalRenderMetrics.current
    if (metrics != null) SideEffect { metrics.nodeComposed(node) }
    val theme = LocalOwnTheme.current
    val registry = LocalFocusRegistry.current
    val common = node.common
    val shape = RoundedCornerShape((common.cornerRadius ?: 0).dp)
    val fillWidth = fillByDefault || fillWidthByDefault || node is AppRowNode || node is AppGridNode || node is HeroNode
    val selected = LocalEditorSelection.current == node.id
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }

    var outer = modifier.nodeSize(node, fillWidth = fillWidth, fillHeight = fillByDefault)
    common.unfocusedOpacity?.let { if (!LocalPreviewMode.current) outer = outer.then(dimUnlessFocused(it)) }
    if (selected) outer = outer.bringIntoViewRequester(bringIntoView).border(3.dp, Color(0xFFFF4FD8), shape)

    // Focus: targets of links get a requester; containers with links or targets become focus groups.
    val isTarget = registry?.isTarget(node.id) == true
    val tile = node.isSingleTile
    if (tile) {
        if (isTarget) outer = outer.focusRequester(registry!!.requester(node.id))
        outer = outer.tileFocusLinks(common.focus, registry)
    } else if (isTarget || common.focus != null) {
        if (isTarget) outer = outer.focusRequester(registry!!.requester(node.id))
        outer = outer.containerFocusLinks(common.focus, registry).focusGroup()
    }
    outer = outer.revealOnFocus(common.revealOnFocus)
    outer = outer.nodeDecoration(common, theme, shape)
    (common.background as? GlassBackground)?.let { outer = outer.glassBackground(it, shape) }
    val padding = common.padding?.let { Modifier.padding(it.start.dp, it.top.dp, it.end.dp, it.bottom.dp) } ?: Modifier

    // Tiles adapt to the width they get when the node or its parent sets one.
    val sizing = TileSizing(adaptive = common.width != null || fillWidthByDefault, fixedHeight = common.height is Size.Fixed)
    val image = common.background as? ImageBackground

    CompositionLocalProvider(LocalAncestors provides LocalAncestors.current + node.id) {
        if (image != null) {
            Box(outer, propagateMinConstraints = true) {
                BackgroundFill(image, Modifier.matchParentSize().glassSource(IMAGE_GLASS_LAYER), shape)
                NodeContent(node, Modifier, padding, sizing)
            }
        } else {
            NodeContent(node, outer, padding, sizing)
        }
    }
}

@Composable
private fun NodeContent(node: Node, outer: Modifier, padding: Modifier, sizing: TileSizing) {
    val theme = LocalOwnTheme.current
    val modifier = outer.then(padding)
    when (node) {
        // Scrolling containers clip to their viewport, so their padding goes inside it: otherwise
        // a focused tile at the edge (scaled up, with its glow) is cut off at the padding's edge.
        is ColumnNode -> ColumnView(node, outer, padding)
        is RowNode -> RowView(node, outer, padding)
        is GridNode -> GridView(node, modifier)
        is StackNode -> StackView(node, modifier)
        is AppTileNode -> {
            val catalog = LocalApps.current
            val app = catalog[node.packageName]
            val entry = when {
                app != null -> AppEntry.Installed(app)
                node.missing == Missing.Placeholder && catalog.loaded -> AppEntry.NotInstalled(node.packageName)
                else -> null
            }
            if (entry != null) AppTileView(entry, node.tile, sizing, modifier, labelOverride = node.label)
        }
        is AppRowNode -> AppRowView(node, modifier)
        is AppGridNode -> AppGridView(node, modifier)
        is FolderNode -> FolderView(node, sizing, modifier)
        is ShortcutNode -> ShortcutView(node, sizing, modifier)
        is HeroNode -> HeroView(node, modifier)
        is TextNode -> TextView(node, modifier)
        is ClockNode -> ClockView(node, modifier)
        is NetworkNode -> NetworkView(node, modifier)
        is ImageNode -> AsyncImage(
            model = imageModel(node.src),
            contentDescription = null,
            contentScale = node.fit.toContentScale(),
            modifier = modifier.clip(RoundedCornerShape((node.common.cornerRadius ?: 0).dp)).alpha(node.alpha).glassSource(IMAGE_GLASS_LAYER),
        )
        is SpacerNode -> Spacer(modifier)
        is UnknownNode -> Box(modifier.background(theme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text("Unknown widget \"${node.type}\"", style = theme.textStyle(TextStyleName.Caption), color = theme.textMuted)
        }
    }
}

// ---------------------------------------------------------------- containers

/**
 * Provides [align] to the scroll container itself and restores the outer behavior for its
 * children, so nested rows keep their own scrolling.
 */
@Composable
private fun ScrollSpecScope(align: ScrollAlign, margin: Dp, container: @Composable (children: @Composable (@Composable () -> Unit) -> Unit) -> Unit) {
    val outer = LocalBringIntoViewSpec.current
    val marginPx = with(LocalDensity.current) { margin.toPx() }
    val spec = remember(align, marginPx) { scrollSpec(align, marginPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        container { content -> CompositionLocalProvider(LocalBringIntoViewSpec provides outer) { content() } }
    }
}

@Composable
private fun ColumnView(node: ColumnNode, modifier: Modifier, padding: Modifier) {
    ScrollSpecScope(node.scrollAlign, node.scrollMargin.dp) { restore ->
        val scroll = if (node.scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = modifier.then(scroll).then(padding),
            verticalArrangement = node.arrange.vertical(node.spacing.dp),
            horizontalAlignment = node.contentAlign.toCompose(),
        ) {
            restore {
                node.children.forEachIndexed { index, child ->
                    key(index, child.id) { RenderNode(child, childModifier(child)) }
                }
            }
        }
    }
}

@Composable
private fun RowView(node: RowNode, modifier: Modifier, padding: Modifier) {
    ScrollSpecScope(node.scrollAlign, node.scrollMargin.dp) { restore ->
        val scroll = if (node.scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier
        Row(
            modifier = modifier.then(scroll).then(padding),
            horizontalArrangement = node.arrange.horizontal(node.spacing.dp),
            verticalAlignment = node.contentAlign.toCompose(),
        ) {
            restore {
                node.children.forEachIndexed { index, child ->
                    key(index, child.id) { RenderNode(child, childModifier(child)) }
                }
            }
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
                    val span = (child.common.span ?: 1).coerceIn(1, node.columns)
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
        val span = (child.common.span ?: 1).coerceIn(1, columns)
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
    Box(modifier, contentAlignment = node.contentAlign.toComposeAlignment()) {
        node.children.forEachIndexed { index, child ->
            key(index, child.id) {
                RenderNode(child, child.common.align?.let { Modifier.align(it.toComposeAlignment()) } ?: Modifier)
            }
        }
    }
}

// ---------------------------------------------------------------- app widgets

/**
 * The stored order behind [source] with everything it shows now, for long-press actions and move
 * mode; null when the source's order is computed.
 */
@Composable
internal fun rememberListContext(source: AppSource, missing: Missing, title: String?, columns: Int): ListContext? {
    val catalog = LocalApps.current
    return remember(catalog, source, missing, title, columns) {
        source.listRef()?.let { ref -> ListContext(ref, title, catalog.resolve(source, missing, null).map { it.packageName }, columns) }
    }
}

@Composable
private fun AppRowView(node: AppRowNode, modifier: Modifier) {
    val catalog = LocalApps.current
    val entries = remember(catalog, node.source, node.missing, node.limit, node.skip) { catalog.resolve(node.source, node.missing, node.limit, node.skip) }
    val list = rememberListContext(node.source, node.missing, node.title, 1)
    val bleed = focusBleed(LocalOwnTheme.current, node.tile)
    val empty = entries.isEmpty()
    if (empty && node.source !is ListSource) return
    Column(modifier) {
        node.title?.let { SectionTitle(it, node.titleIcon, gap = 6.dp + focusGrowth(LocalOwnTheme.current, node.tile)) }
        if (empty) {
            Box(Modifier.padding(vertical = 6.dp)) { AddAppsTile(list?.ref ?: return@Box, node.title, node.tile, TileSizing(adaptive = false, fixedHeight = false)) }
            return@Column
        }
        CompositionLocalProvider(LocalListContext provides list) {
            ScrollSpecScope(node.scrollAlign, node.scrollMargin.dp) { restore ->
                LazyRow(
                    modifier = Modifier.fillMaxWidth().bleed(bleed, bleed).focusRestorer(),
                    contentPadding = PaddingValues(horizontal = bleed, vertical = bleed),
                    horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
                ) {
                    items(entries, key = { it.packageName }) { entry ->
                        restore { AppTileView(entry, node.tile, TileSizing(adaptive = false, fixedHeight = false)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGridView(node: AppGridNode, modifier: Modifier) {
    val catalog = LocalApps.current
    val entries = remember(catalog, node.source, node.missing, node.limit, node.skip) { catalog.resolve(node.source, node.missing, node.limit, node.skip) }
    val list = rememberListContext(node.source, node.missing, node.title, node.columns)
    val empty = entries.isEmpty()
    if (empty && node.source !is ListSource) return
    Column(modifier) {
        node.title?.let { SectionTitle(it, node.titleIcon) }
        if (empty) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
                AddAppsTile(list?.ref ?: return@Row, node.title, node.tile, TileSizing(adaptive = true, fixedHeight = false), Modifier.weight(1f))
                repeat(node.columns - 1) { Spacer(Modifier.weight(1f)) }
            }
            return@Column
        }
        CompositionLocalProvider(LocalListContext provides list) {
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
}

@Composable
private fun SectionTitle(text: String, icon: String?, gap: Dp = 6.dp) {
    val theme = LocalOwnTheme.current
    val style = theme.textStyle(TextStyleName.Title, bold = true)
    Row(Modifier.padding(bottom = gap), verticalAlignment = ComposeAlignment.CenterVertically) {
        if (icon != null) {
            val size = with(LocalDensity.current) { style.fontSize.toDp() } * 1.15f
            ShortcutIcon(icon, size, tint = theme.text)
            Spacer(Modifier.width(size * 0.45f))
        }
        Text(text = text, style = style, color = theme.text)
    }
}

// ---------------------------------------------------------------- content widgets

@Composable
private fun ClockView(node: ClockNode, modifier: Modifier) {
    val theme = LocalOwnTheme.current
    val hasSeconds = node.format.contains('s')
    var now by remember { mutableStateOf(Clocks.now()) }
    LaunchedEffect(hasSeconds) {
        while (true) {
            now = Clocks.now()
            val millis = Clocks.nowMillis()
            delay(if (hasSeconds) 1000 - millis % 1000 else 60_000 - millis % 60_000)
        }
    }
    val text = remember(node.format, now) { Clocks.format(node.format, now) ?: "--:--" }
    val base = theme.textStyle(node.textStyle, node.bold, node.font)
    Text(
        text = text,
        style = base.copy(
            fontSize = node.fontSize?.let { (it * theme.theme.fontScale).sp } ?: base.fontSize,
            lineHeight = node.fontSize?.let { (it * theme.theme.fontScale * 1.2f).sp } ?: base.lineHeight,
            letterSpacing = node.letterSpacing?.sp ?: base.letterSpacing,
            textAlign = node.textAlign.toComposeTextAlign(),
        ),
        color = theme.color(node.color) ?: theme.text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- modifiers and mappings

/**
 * Fades the node to [opacity] while focus is outside it and back to full opacity when focus enters.
 * The alpha goes onto each drawing instead of an offscreen buffer: that buffer is only as big as the
 * node and would cut off a focused tile that grows past the node's edge while the fade runs.
 */
@Composable
private fun dimUnlessFocused(opacity: Float): Modifier {
    var focusInside by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (focusInside) 1f else opacity, tween(220))
    return Modifier.onFocusChanged { focusInside = it.hasFocus }.graphicsLayer {
        this.alpha = alpha
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

private val MIN_TILE_BLEED = 14.dp

/** How far a focused tile of a row grows above its top edge, so the row's title stays clear of it. */
private fun focusGrowth(theme: OwnTheme, tile: TileStyle): Dp {
    val height = if (tile.shape == TileShape.Icon && tile.width == null) tile.size.iconSize() else tile.fixedWidth() / tile.ratio()
    return height * ((theme.focusScale - 1f).coerceAtLeast(0f) / 2f)
}

/** How far a focused tile reaches past its bounds: its scale-up, border and glow. */
private fun focusBleed(theme: OwnTheme, tile: TileStyle): Dp {
    val grow = tile.fixedWidth() * ((theme.focusScale - 1f).coerceAtLeast(0f) / 2f)
    return maxOf(MIN_TILE_BLEED, grow + theme.focusBorderWidth + if (theme.glow) theme.glowSize else 0.dp)
}

/** Images sit above the page background in the glass source order. */
private const val IMAGE_GLASS_LAYER = 1f

/**
 * Grows a child by [horizontal] and [vertical] on every side without changing the space it takes
 * in the layout, so focus scaling and glow are not clipped at the edges of a lazy row.
 */
private fun Modifier.bleed(horizontal: Dp, vertical: Dp) = layout { measurable, constraints ->
    val extraX = if (constraints.hasBoundedWidth) horizontal.roundToPx() else 0
    val extraY = vertical.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + 2 * extraX,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extraX else constraints.maxWidth,
            minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * extraY else constraints.maxHeight,
        ),
    )
    layout(placeable.width - 2 * extraX, (placeable.height - 2 * extraY).coerceAtLeast(0)) { placeable.place(-extraX, -extraY) }
}

private fun Modifier.nodeSize(node: Node, fillWidth: Boolean, fillHeight: Boolean): Modifier {
    val withWidth = when (val w = node.common.width) {
        Size.Fill -> fillMaxWidth()
        Size.Wrap -> wrapContentWidth()
        is Size.Fixed -> width(w.dp.dp)
        null -> if (fillWidth) fillMaxWidth() else this
    }
    return when (val h = node.common.height) {
        Size.Fill -> withWidth.fillMaxHeight()
        Size.Wrap -> withWidth.wrapContentHeight()
        is Size.Fixed -> withWidth.height(h.dp.dp)
        null -> when {
            fillHeight -> withWidth.fillMaxHeight()
            node is HeroNode -> withWidth.height(HERO_DEFAULT_HEIGHT)
            else -> withWidth
        }
    }
}

private val HERO_DEFAULT_HEIGHT = 280.dp

private fun ColumnScope.childModifier(child: Node): Modifier {
    var m: Modifier = Modifier
    child.common.weight?.takeIf { it > 0f }?.let { m = m.weight(it) }
    child.common.align?.let { m = m.align(it.horizontalPart()) }
    return m
}

private fun RowScope.childModifier(child: Node): Modifier {
    var m: Modifier = Modifier
    child.common.weight?.takeIf { it > 0f }?.let { m = m.weight(it) }
    child.common.align?.let { m = m.align(it.verticalPart()) }
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

fun NodeAlignment.toComposeAlignment(): ComposeAlignment = when (this) {
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

fun TextAlignment.toComposeTextAlign(): ComposeTextAlign = when (this) {
    TextAlignment.Start -> ComposeTextAlign.Start
    TextAlignment.Center -> ComposeTextAlign.Center
    TextAlignment.End -> ComposeTextAlign.End
}

fun ImageFit.toContentScale(): ContentScale = when (this) {
    ImageFit.Cover -> ContentScale.Crop
    ImageFit.Contain -> ContentScale.Fit
    ImageFit.Fill -> ContentScale.FillBounds
}
