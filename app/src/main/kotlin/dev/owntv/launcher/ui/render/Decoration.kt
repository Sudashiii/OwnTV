package dev.owntv.launcher.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.ColorBackground
import dev.owntv.layout.model.FocusLinks
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.NodeCommon
import dev.owntv.layout.model.ScrollAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Draws a color, gradient or image background filling [modifier]. */
@Composable
fun BackgroundFill(background: Background, modifier: Modifier, shape: Shape = RectangleShape) {
    val theme = LocalOwnTheme.current
    when (background) {
        is ColorBackground -> Box(modifier.background(theme.color(background.color) ?: Color.Transparent, shape))
        is GradientBackground -> Box(
            modifier.clip(shape).drawWithCache {
                val brush = theme.gradientBrush(background, size)
                onDrawBehind { drawRect(brush) }
            },
        )
        is ImageBackground -> Box(modifier.clip(shape)) {
            AsyncImage(
                model = imageModel(background.src),
                contentDescription = null,
                contentScale = background.fit.toContentScale(),
                modifier = Modifier.matchParentSize().then(if (background.blur > 0) Modifier.blur(background.blur.dp) else Modifier),
            )
            if (background.dim > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = background.dim)))
        }
    }
}

/** Shadow, opacity, color or gradient background and border of a node. Image backgrounds are drawn by the caller. */
fun Modifier.nodeDecoration(common: NodeCommon, theme: OwnTheme, shape: Shape): Modifier {
    var m = this
    common.shadow?.takeIf { it > 0 }?.let { m = m.shadow(it.dp, shape, clip = false) }
    common.opacity?.let { m = m.alpha(it.coerceIn(0f, 1f)) }
    when (val bg = common.background) {
        is ColorBackground -> theme.color(bg.color)?.let { m = m.background(it, shape) }
        is GradientBackground -> m = m.clip(shape).drawWithCache {
            val brush = theme.gradientBrush(bg, size)
            onDrawBehind { drawRect(brush) }
        }
        else -> Unit
    }
    common.border?.takeIf { it.width > 0 }?.let { border ->
        m = m.border(border.width.dp, theme.color(border.color) ?: theme.textMuted, shape)
    }
    return m
}

/** Explicit D-pad targets on a single focusable tile. */
fun Modifier.tileFocusLinks(links: FocusLinks?, registry: FocusRegistry?): Modifier {
    if (links == null || registry == null) return this
    return focusProperties {
        links.up?.let { up = registry.requester(it) }
        links.down?.let { down = registry.requester(it) }
        links.left?.let { left = registry.requester(it) }
        links.right?.let { right = registry.requester(it) }
    }
}

/** Explicit D-pad targets on a container: they apply when focus leaves the container in that direction. */
fun Modifier.containerFocusLinks(links: FocusLinks?, registry: FocusRegistry?): Modifier {
    if (links == null || registry == null) return this
    return focusProperties {
        onExit = {
            val target = when (requestedFocusDirection) {
                FocusDirection.Up -> links.up
                FocusDirection.Down -> links.down
                FocusDirection.Left -> links.left
                FocusDirection.Right -> links.right
                else -> null
            }
            if (target != null) {
                cancelFocusChange()
                registry.requester(target).requestFocus()
            }
        }
    }
}

/** Scrolls the whole node into view when something inside it gets focus. */
@Composable
fun Modifier.revealOnFocus(enabled: Boolean): Modifier {
    if (!enabled) return this
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            if (state.hasFocus) {
                scope.launch {
                    // Let the focused child's own scroll request go first, then show the whole node.
                    delay(30)
                    requester.bringIntoView()
                }
            }
        }
}

/** How a scroll container moves a focused child into view. */
fun scrollSpec(align: ScrollAlign, marginPx: Float): BringIntoViewSpec = when (align) {
    ScrollAlign.Nearest -> NearestSpec
    ScrollAlign.Start -> object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (size + marginPx > containerSize) NearestSpec.calculateScrollDistance(offset, size, containerSize) else offset - marginPx
    }
    ScrollAlign.Center -> object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            offset + size / 2f - containerSize / 2f
    }
}

/** Scrolls just enough to show the child: the classic behavior, also on TVs (where Compose defaults to a pivot). */
object NearestSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}
