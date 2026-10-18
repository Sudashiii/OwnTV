package dev.owntv.launcher.ui.render

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.owntv.layout.model.FontName
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.Theme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The layout's [Theme] resolved to Compose values. */
@Immutable
class OwnTheme(val theme: Theme) {
    private val colors = theme.colors

    val background = resolveOr(colors.background, Color(0xFF0E0F13))
    val surface = resolveOr(colors.surface, Color(0xFF1F2229))
    val primary = resolveOr(colors.primary, Color(0xFF7AA2F7))
    val text = resolveOr(colors.text, Color(0xFFECEDEF))
    val textMuted = resolveOr(colors.textMuted, Color(0xFF9AA0AA))
    val focusBorderColor = resolveOr(theme.focus.borderColor, Color.White)
    val glowColor: Color = theme.focus.glowColor?.let(::color) ?: focusBorderColor.copy(alpha = 0.55f)
    val glowSize: Dp = theme.focus.glowSize.dp
    val cornerRadius: Dp = theme.cornerRadius.dp
    val focusScale: Float = theme.focus.scale
    val focusBorderWidth: Dp = theme.focus.borderWidth.dp
    val glow: Boolean = theme.focus.glow && theme.focus.glowSize > 0

    /** Resolves `#hex` or `@token`; null when the value is missing or invalid. */
    fun color(ref: String?): Color? = ref?.let(colors::resolve)?.let { Color(it) }

    fun textStyle(name: TextStyleName, bold: Boolean = false, font: FontName? = null): TextStyle {
        val size = when (name) {
            TextStyleName.Display -> 56
            TextStyleName.Headline -> 34
            TextStyleName.Title -> 22
            TextStyleName.Body -> 16
            TextStyleName.Caption -> 12
        } * theme.fontScale
        val weight = when {
            bold -> FontWeight.Bold
            name == TextStyleName.Display || name == TextStyleName.Headline -> FontWeight.Medium
            else -> FontWeight.Normal
        }
        return TextStyle(
            fontSize = size.sp,
            lineHeight = (size * 1.2f).sp,
            fontWeight = weight,
            fontFamily = (font ?: theme.fontFamily).toFontFamily(),
            color = text,
        )
    }

    /** A brush for a gradient, CSS-style: 0 deg = bottom to top, 90 deg = left to right. */
    fun gradientBrush(gradient: GradientBackground, size: Size): Brush {
        val colors = gradient.colors.mapNotNull(::color).ifEmpty { listOf(background, background) }
        val radians = Math.toRadians(gradient.angle.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        return Brush.linearGradient(colors, start = center - Offset(dx * half, dy * half), end = center + Offset(dx * half, dy * half))
    }

    private fun resolveOr(ref: String, fallback: Color) = color(ref) ?: fallback
}

fun FontName.toFontFamily(): FontFamily = when (this) {
    FontName.Sans -> FontFamily.SansSerif
    FontName.Serif -> FontFamily.Serif
    FontName.Mono -> FontFamily.Monospace
    FontName.Cursive -> FontFamily.Cursive
}

val LocalOwnTheme = staticCompositionLocalOf { OwnTheme(Theme()) }

/** True while rendering a scaled-down preview: nothing is focusable or clickable. */
val LocalPreviewMode = staticCompositionLocalOf { false }
