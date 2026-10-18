@file:OptIn(ExperimentalHazeApi::class)

package dev.owntv.launcher.ui.render

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.owntv.layout.model.FocusMotion
import dev.owntv.layout.model.GlassBackground
import dev.owntv.layout.model.GlassVariant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The page's glass state: heroes, images and the page background are drawn into it so glass nodes can blur them. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Marks content that glass backgrounds above it blur and refract. Later layers use a higher [zIndex]. */
@Composable
fun Modifier.glassSource(zIndex: Float): Modifier {
    val state = LocalHazeState.current ?: return this
    return hazeSource(state, zIndex = zIndex)
}

/**
 * A Liquid Glass background in [shape]. In preview mode (scaled editor and look previews) and
 * without a page glass state it falls back to the tint with a light rim.
 */
@Composable
fun Modifier.glassBackground(glass: GlassBackground, shape: RoundedCornerShape): Modifier {
    val theme = LocalOwnTheme.current
    val tint = theme.color(glass.tint) ?: Color.White.copy(alpha = 0.12f)
    val state = LocalHazeState.current
    if (state == null || LocalPreviewMode.current) {
        return background(tint, shape).border(1.dp, rimBrush(), shape)
    }
    val base = if (glass.variant == GlassVariant.Clear) GlassStyle.clear else GlassStyle.regular
    val style = remember(base, tint, shape) {
        base.then {
            shape(shape)
            tint(tint)
        }
    }
    return hazeGlass(input = HazeInput.Sources(state), style = style)
}

/** A thin edge that is brighter at the top, like light catching the rim of glass. */
fun rimBrush(): Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.08f)))

/** Draws the light rim over a tile. */
@Composable
fun BoxScope.TileRim(shape: Shape) {
    Box(Modifier.matchParentSize().border(1.dp, rimBrush(), shape))
}

/**
 * Phase of the focused tile's sway, 0 to 1, running only while [active]. A fixed phase is returned
 * otherwise, so the sheen still sits at a pleasant angle.
 */
@Composable
fun rememberSwayPhase(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0.125f) }
    val transition = rememberInfiniteTransition(label = "sway")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWAY_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "swayPhase",
    )
}

/** Tilts the focused tile in a slow circle, like the tvOS parallax effect when the remote's touch surface moves. */
fun Modifier.sway(motion: FocusMotion, focused: Boolean, phase: State<Float>): Modifier {
    if (motion != FocusMotion.Sway || !focused) return this
    return graphicsLayer {
        val angle = phase.value * 2f * PI.toFloat()
        rotationX = sin(angle) * SWAY_DEGREES * 0.6f
        rotationY = cos(angle) * SWAY_DEGREES
        cameraDistance = 24f * density
    }
}

/** A soft diagonal band of light over the focused tile that moves with the sway. */
@Composable
fun BoxScope.FocusSheen(phase: State<Float>) {
    Box(
        Modifier.matchParentSize().drawWithCache {
            onDrawBehind {
                val angle = phase.value * 2f * PI.toFloat()
                // The light centre circles around the upper part of the tile.
                val center = Offset(size.width * (0.5f + 0.35f * cos(angle)), size.height * (0.1f + 0.25f * sin(angle)))
                val reach = size.width * 0.9f
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = reach,
                    ),
                )
            }
        },
    )
}

private const val SWAY_PERIOD_MS = 5200
private const val SWAY_DEGREES = 3.5f
