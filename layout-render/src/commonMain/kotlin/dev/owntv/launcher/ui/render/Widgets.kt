package dev.owntv.launcher.ui.render

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import dev.owntv.layout.model.TileShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.HeroFollow
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.HeroSlide
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.Missing
import dev.owntv.layout.model.Placeholders
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.time.Clocks
import dev.owntv.layout.time.WallClock
import kotlinx.coroutines.delay
import dev.owntv.layout.model.Alignment as NodeAlignment

// ---------------------------------------------------------------- hero

/** What a hero shows at one moment. */
private data class HeroContent(
    val title: String?,
    val description: String?,
    val background: Background? = null,
    val backdrop: String? = null,
    val app: AppInfo? = null,
)

private fun FocusedTile.toHero() = HeroContent(label, description, backdrop = backdrop, app = app)

private fun HeroSlide.toHero() = HeroContent(title, description, background = background)

@Composable
fun HeroView(node: HeroNode, modifier: Modifier) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val tracker = LocalFocusTracker.current
    val preview = LocalPreviewMode.current
    val nodeId = LocalAncestors.current.lastOrNull()
    val slides = node.slides

    var slideIndex by remember(slides) { mutableIntStateOf(0) }
    if (slides.size > 1) {
        LaunchedEffect(slides, node.interval) {
            while (true) {
                delay(node.interval * 1000L)
                slideIndex = (slideIndex + 1) % slides.size
            }
        }
    }

    // With follow focus, keep showing the last matching tile while focus is elsewhere.
    var followed by remember { mutableStateOf<FocusedTile?>(null) }
    val focused = tracker?.current
    LaunchedEffect(focused) {
        if (node.follow == HeroFollow.Focus && focused != null && (node.target == null || node.target in focused.ancestors)) {
            followed = focused
        }
    }
    val content = followed?.toHero() ?: slides.getOrNull(slideIndex)?.toHero() ?: HeroContent(null, null)
    val shape = RoundedCornerShape((node.common.cornerRadius ?: 0).dp)
    // Glass nodes above the hero blur it; the fade lets it melt into whatever is below.
    val layered = Modifier.glassSource(HERO_GLASS_LAYER).fadeBottom(node.fade)

    val body: @Composable BoxScope.() -> Unit = {
        Crossfade(targetState = content, animationSpec = tween(500), label = "hero") { shown ->
            HeroLayer(shown, node)
        }
        if (node.follow == HeroFollow.Slides && slides.size > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                slides.indices.forEach { i ->
                    Box(Modifier.size(if (i == slideIndex) 10.dp else 7.dp).clip(CircleShape).background(theme.text.copy(alpha = if (i == slideIndex) 0.95f else 0.45f)))
                }
            }
        }
    }

    val interactive = node.follow == HeroFollow.Slides && slides.any { it.action != null } && !preview
    if (interactive) {
        val interaction = remember { MutableInteractionSource() }
        TileSurface(
            onClick = { slides.getOrNull(slideIndex)?.action?.let(handler::perform) },
            onLongClick = { handler.longPress(ContextTarget(content.title ?: "Hero", null, null, nodeId)) },
            modifier = modifier.then(layered).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || slides.size < 2) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { slideIndex = (slideIndex - 1 + slides.size) % slides.size; true }
                    Key.DirectionRight -> { slideIndex = (slideIndex + 1) % slides.size; true }
                    else -> false
                }
            },
            color = Color.Transparent,
            interactionSource = interaction,
            cornerRadius = node.common.cornerRadius?.dp ?: 0.dp,
            focusedScale = 1f,
            content = body,
        )
    } else {
        Box(modifier.clip(shape).then(layered), content = body)
    }
}

private const val HERO_GLASS_LAYER = 1f

/** Fades the bottom [fraction] of the content out to transparent. */
private fun Modifier.fadeBottom(fraction: Float): Modifier {
    if (fraction <= 0f) return this
    val start = (1f - fraction).coerceIn(0f, 1f)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Black, start to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

@Composable
private fun HeroLayer(content: HeroContent, node: HeroNode) {
    val theme = LocalOwnTheme.current
    Box(Modifier.fillMaxSize()) {
        when {
            content.backdrop != null -> AsyncImage(
                model = imageModel(content.backdrop),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            content.background != null -> BackgroundFill(content.background, Modifier.fillMaxSize())
            content.app != null -> AppSpotlight(content.app, raised = node.fade > 0f)
        }
        if (node.showText && (content.title != null || content.description != null)) {
            val bottom = node.contentAlign in setOf(NodeAlignment.BottomStart, NodeAlignment.Bottom, NodeAlignment.BottomEnd)
            val scrim = Color.Black.copy(alpha = node.scrim)
            Box(
                Modifier.fillMaxSize().background(
                    if (bottom) Brush.verticalGradient(listOf(Color.Transparent, scrim)) else Brush.horizontalGradient(listOf(scrim, Color.Transparent)),
                ),
            )
            Column(
                Modifier.align(node.contentAlign.toComposeAlignment()).padding(horizontal = 40.dp, vertical = 28.dp).widthIn(max = 560.dp),
            ) {
                content.title?.let {
                    Text(rememberPlaceholderText(it), style = theme.textStyle(TextStyleName.Headline, bold = true), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                content.description?.let {
                    Text(
                        rememberPlaceholderText(it),
                        style = theme.textStyle(TextStyleName.Body),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * For apps without a backdrop: the app's banner, blurred as a backdrop and sharp on the side.
 * [raised] keeps the sharp banner in the upper part, for heroes whose bottom fades behind a dock.
 */
@Composable
private fun AppSpotlight(app: AppInfo, raised: Boolean) {
    val banner = rememberAppImage(app, AppImages.Kind.Banner) ?: rememberAppImage(app, AppImages.Kind.Icon) ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = minOf(162.dp, maxHeight * if (raised) 0.4f else 0.6f)
        Image(banner, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(36.dp))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Image(
            banner,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(if (raised) BiasAlignment(1f, -0.45f) else Alignment.CenterEnd)
                .padding(end = 56.dp)
                .size(height * 16f / 9f, height)
                .clip(RoundedCornerShape(14.dp)),
        )
    }
}

// ---------------------------------------------------------------- folder

@Composable
fun FolderView(node: FolderNode, sizing: TileSizing, modifier: Modifier) {
    val theme = LocalOwnTheme.current
    val handler = LocalActionHandler.current
    val catalog = LocalApps.current
    val ancestors = LocalAncestors.current
    val entries = remember(catalog, node.source) { catalog.resolve(node.source, Missing.Hide, null) }
    val list = rememberListContext(node.source, Missing.Hide, node.label, node.columns)
    if (entries.isEmpty() && node.source !is ListSource) return
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    ReportFocus(focused) { FocusedTile(node.label, null, "${entries.size} apps", null, ancestors) }

    TileFrame(node.tile, sizing, node.label, focused, modifier) { surfaceModifier ->
        TileSurface(
            onClick = { if (entries.isEmpty()) list?.let { handler.editList(it.ref, node.label) } else open = true },
            onLongClick = { handler.longPress(ContextTarget(node.label, null, null, ancestors.lastOrNull())) },
            modifier = surfaceModifier,
            color = theme.color(node.color) ?: theme.surface,
            interactionSource = interaction,
            cornerRadius = node.tile.cornerRadius?.dp,
            rim = node.tile.rim,
        ) {
            val apps = entries.mapNotNull { (it as? AppEntry.Installed)?.app }
            when {
                apps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "Add apps", tint = theme.text, modifier = Modifier.size(28.dp))
                }
                node.tile.shape == TileShape.Icon -> FolderPreview(apps.take(6))
                else -> FolderBannerPreview(apps.take(9))
            }
        }
    }
    if (open && entries.isNotEmpty()) {
        CompositionLocalProvider(LocalListContext provides list) { FolderPanel(node, entries, onClose = { open = false }) }
    }
}

/** Up to six small app icons in a 3 x 2 grid. */
@Composable
private fun FolderPreview(apps: List<AppInfo>) {
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        apps.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                row.forEach { app ->
                    rememberAppImage(app, AppImages.Kind.Icon)?.let {
                        Image(it, contentDescription = null, modifier = Modifier.weight(1f, fill = false).size(30.dp).clip(RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}

/** Up to nine small app banners in a 3 x 3 grid, like a tvOS folder. */
@Composable
private fun FolderBannerPreview(apps: List<AppInfo>) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        apps.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { app ->
                    val image = rememberAppImage(app, AppImages.Kind.Banner) ?: rememberAppImage(app, AppImages.Kind.Icon)
                    Box(Modifier.weight(1f).aspectRatio(5f / 3f).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f))) {
                        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FolderPanel(node: FolderNode, entries: List<AppEntry>, onClose: () -> Unit) {
    val theme = LocalOwnTheme.current
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(theme.background.copy(alpha = 0.94f))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 40.dp)) {
                Text(node.label, style = theme.textStyle(TextStyleName.Headline, bold = true), color = theme.text)
                Spacer(Modifier.height(24.dp))
                Column(Modifier.focusRequester(first).focusGroup(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    entries.chunked(node.columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            row.forEach { entry ->
                                key(entry.packageName) {
                                    AppTileView(entry, node.itemTile, TileSizing(adaptive = true, fixedHeight = false), Modifier.weight(1f))
                                }
                            }
                            repeat(node.columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

// ---------------------------------------------------------------- text

/** Resolves {placeholders}; recomposes every minute when the text shows the time. */
@Composable
fun rememberPlaceholderText(raw: String): String {
    val parts = remember(raw) { Placeholders.parse(raw) }
    val tokens = parts.filterIsInstance<Placeholders.Part.Token>()
    if (tokens.isEmpty()) return raw
    val needsClock = tokens.any { it.name == "greeting" || it.name == "time" || it.name == "date" }
    var now by remember { mutableStateOf(Clocks.now()) }
    if (needsClock) {
        LaunchedEffect(Unit) {
            while (true) {
                now = Clocks.now()
                delay(60_000 - Clocks.nowMillis() % 60_000)
            }
        }
    }
    val focused = if (tokens.any { it.name == "focused" }) LocalFocusTracker.current?.current else null
    val page = LocalPageTitle.current
    return buildString {
        parts.forEach { part ->
            when (part) {
                is Placeholders.Part.Literal -> append(part.text)
                is Placeholders.Part.Token -> append(
                    when (part.name) {
                        "greeting" -> Placeholders.greeting(now.hour)
                        "time" -> formatOrRaw(now, part.argument ?: "HH:mm")
                        "date" -> formatOrRaw(now, part.argument ?: "EEEE, d MMMM")
                        "focused" -> focused?.label.orEmpty()
                        "page" -> page.orEmpty()
                        else -> "{${part.name}${part.argument?.let { ":$it" }.orEmpty()}}"
                    },
                )
            }
        }
    }
}

@Composable
fun TextView(node: TextNode, modifier: Modifier) {
    val theme = LocalOwnTheme.current
    val text = rememberPlaceholderText(node.text)
    val base = theme.textStyle(node.textStyle, node.bold, node.font)
    Text(
        text = if (node.uppercase) text.uppercase() else text,
        style = base.copy(
            fontSize = node.fontSize?.let { (it * theme.theme.fontScale).sp } ?: base.fontSize,
            lineHeight = node.fontSize?.let { (it * theme.theme.fontScale * 1.2f).sp } ?: base.lineHeight,
            letterSpacing = node.letterSpacing?.sp ?: base.letterSpacing,
            textAlign = node.textAlign.toComposeTextAlign(),
        ),
        color = theme.color(node.color) ?: theme.text,
        maxLines = node.maxLines ?: Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun formatOrRaw(time: WallClock, pattern: String): String = Clocks.format(pattern, time) ?: pattern
