package dev.owntv.launcher.ui.render

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.ColorBackground
import dev.owntv.layout.model.FocusLinks
import dev.owntv.layout.model.GlassBackground
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.NodeCommon
import dev.owntv.layout.model.ScrollAlign
import dev.owntv.layout.model.ScrollCurve
import dev.owntv.layout.model.ScrollMotion
import dev.owntv.layout.model.SlideshowBackground
import dev.owntv.layout.model.VideoBackground
import kotlin.math.abs

/**
 * Draws a background filling [modifier]. A slideshow uses [slideshow] when given (the screen's, which
 * next_wallpaper and dynamic colors follow), else runs its own.
 */
@Composable
fun BackgroundFill(background: Background, modifier: Modifier, shape: Shape = RectangleShape, slideshow: SlideshowState? = null) {
    val theme = LocalOwnTheme.current
    when (background) {
        is ColorBackground -> Box(modifier.background(theme.color(background.color) ?: Color.Transparent, shape))
        is GradientBackground -> Box(
            modifier.clip(shape).drawWithCache {
                val brush = theme.gradientBrush(background, size)
                onDrawBehind { drawRect(brush) }
            },
        )
        is ImageBackground -> with(background) { MediaFill(src, video = false, dim, blur, fit, adjust, modifier.clip(shape)) }
        is VideoBackground -> with(background) { MediaFill(src, video = true, dim, blur, fit, adjust, modifier.clip(shape)) }
        is SlideshowBackground -> SlideshowFill(slideshow ?: rememberSlideshow(background), background.transition, modifier, shape)
        is GlassBackground -> Box(modifier.glassBackground(background, shape as? RoundedCornerShape ?: RoundedCornerShape(0.dp)))
    }
}

/** True for backgrounds drawn as content under a node (pictures, videos, slideshows) rather than as a modifier. */
val Background.isMedia: Boolean get() = this is ImageBackground || this is VideoBackground || this is SlideshowBackground

/** Shadow, opacity, color or gradient background and border of a node. Media backgrounds ([isMedia]) are drawn by the caller. */
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

/**
 * Scrolls the whole node into view when something inside it gets focus. Requests from inside the
 * node (a focused tile, a nested row) are replaced by one for the node's own bounds, so the
 * containing scroll never sees two competing targets.
 */
fun Modifier.revealOnFocus(enabled: Boolean): Modifier = if (enabled) this then RevealElement else this

private data object RevealElement : ModifierNodeElement<RevealNode>() {
    override fun create() = RevealNode()
    override fun update(node: RevealNode) = Unit
}

private class RevealNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        bringIntoView()
    }
}

/**
 * How far a focused item of a given size reaches past its bounds: half of what its zoom adds, plus
 * border and glow. Scroll containers keep at least this much room at their edges, so a focused tile
 * is never cut off. [minPx] is the least room kept (an app row keeps its tiles inside its own bounds).
 */
class FocusReach(private val scale: Float, private val outlinePx: Float, private val minPx: Float = 0f) {
    fun of(size: Float): Float = maxOf(minPx, size * (scale - 1f).coerceAtLeast(0f) / 2f + outlinePx)
}

/**
 * How a scroll container moves a focused child into view, keeping [reach] free at its edges and
 * moving with [motion].
 */
fun scrollSpec(align: ScrollAlign, marginPx: Float, reach: FocusReach, motion: ScrollMotion?): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        scrollDistance(align, marginPx, reach.of(size), offset, size, containerSize)

    @Deprecated("Animation spec customization is no longer supported.")
    override val scrollAnimationSpec: AnimationSpec<Float> = (motion ?: ScrollMotion()).animationSpec()
}

/**
 * The animation for a [ScrollMotion]. The default spring (120 ms) is stiff enough for a row to keep
 * up with a held remote: when the scroll trails behind, focus runs past the laid-out tiles and every
 * press has to build and measure the next one first. For the spring, the duration sets the stiffness.
 */
fun ScrollMotion.animationSpec(): AnimationSpec<Float> {
    val seconds = duration / 1000f
    return when (curve) {
        ScrollCurve.Spring -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = (6.6f / seconds).let { it * it })
        ScrollCurve.EaseOut -> tween(duration, easing = LinearOutSlowInEasing)
        ScrollCurve.EaseInOut -> tween(duration, easing = FastOutSlowInEasing)
        ScrollCurve.Linear -> tween(duration, easing = LinearEasing)
    }
}

/**
 * The scroll distance for an item at [offset] with [size] in a viewport of [containerSize], keeping
 * [edge] free on both sides. An item too big for that room gets what is left, split evenly.
 */
internal fun scrollDistance(align: ScrollAlign, marginPx: Float, edge: Float, offset: Float, size: Float, containerSize: Float): Float {
    val room = edge.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
    val nearest = { NearestSpec.calculateScrollDistance(offset - room, size + 2 * room, containerSize) }
    return when (align) {
        ScrollAlign.Nearest -> nearest()
        ScrollAlign.Start -> {
            val lead = maxOf(marginPx, room)
            if (lead + size + room > containerSize) nearest() else offset - lead
        }
        ScrollAlign.Center -> offset + size / 2f - containerSize / 2f
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
