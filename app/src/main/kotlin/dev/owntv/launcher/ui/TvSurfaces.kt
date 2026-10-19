package dev.owntv.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import dev.owntv.launcher.ui.render.InteractiveSurfaces
import dev.owntv.launcher.ui.render.LocalOwnTheme

/** Focusable tiles and page tabs for the TV, built on tv-material with the layout's focus style. */
object TvSurfaces : InteractiveSurfaces {
    @Composable
    override fun Tile(
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        modifier: Modifier,
        shape: Shape,
        color: Color,
        focusedScale: Float,
        interactionSource: MutableInteractionSource,
        content: @Composable BoxScope.() -> Unit,
    ) {
        val theme = LocalOwnTheme.current
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
            scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = if (theme.focusBorderWidth > 0.dp) {
                    Border(border = BorderStroke(theme.focusBorderWidth, theme.focusBorderColor), shape = shape)
                } else {
                    Border.None
                },
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = if (theme.glow) Glow(elevationColor = theme.glowColor, elevation = theme.glowSize) else Glow.None,
            ),
            interactionSource = interactionSource,
            content = content,
        )
    }

    @Composable
    override fun PageTab(
        onClick: () -> Unit,
        selected: Boolean,
        modifier: Modifier,
        content: @Composable (contentColor: Color) -> Unit,
    ) {
        val theme = LocalOwnTheme.current
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (selected) theme.surface else Color.Transparent,
                contentColor = if (selected) theme.text else theme.textMuted,
                focusedContainerColor = theme.text,
                focusedContentColor = theme.background,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            content(LocalContentColor.current)
        }
    }
}
