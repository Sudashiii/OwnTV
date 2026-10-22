package dev.owntv.layout.color

import dev.owntv.layout.model.Colors
import dev.owntv.layout.model.DynamicColorStyle
import dev.owntv.layout.model.DynamicColors
import dev.owntv.layout.model.ThemeColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Material You-style theme colors from an image (`theme.dynamicColors`). Pure, so the TV and the web
 * preview pick the same colors. Works in OKLCH, where lightness and hue stay put while chroma changes.
 */
object DynamicPalette {
    /**
     * The image's most characteristic color as ARGB, or null when it has no clear color (gray, black,
     * white or transparent). Like Material's scoring: hues that cover much of the image win, and
     * colorful ones get a bonus. [pixels] are ARGB; large images are sampled.
     */
    fun seed(pixels: IntArray): Int? {
        val step = max(1, pixels.size / MAX_SAMPLES)
        val hueCount = IntArray(HUE_BINS)
        val sumL = DoubleArray(HUE_BINS)
        val sumA = DoubleArray(HUE_BINS)
        val sumB = DoubleArray(HUE_BINS)
        var total = 0
        var i = 0
        while (i < pixels.size) {
            val argb = pixels[i]
            i += step
            if ((argb ushr 24) < 128) continue
            total++
            val lab = Lab.fromArgb(argb)
            if (lab.chroma < MIN_SEED_CHROMA) continue
            val bin = (lab.hue / (2 * PI) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            hueCount[bin]++
            sumL[bin] += lab.l
            sumA[bin] += lab.a
            sumB[bin] += lab.b
        }
        if (total == 0) return null
        var best: Lab? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (bin in 0 until HUE_BINS) {
            val n = hueCount[bin]
            if (n == 0) continue
            // Neighboring bins count too, so a hue split over a bin edge is not undervalued.
            val nearby = hueCount[(bin + HUE_BINS - 1) % HUE_BINS] + n + hueCount[(bin + 1) % HUE_BINS]
            val proportion = nearby.toDouble() / total
            if (proportion < MIN_PROPORTION) continue
            val color = Lab(sumL[bin] / n, sumA[bin] / n, sumB[bin] / n)
            val chroma = color.chroma * CHROMA_SCALE
            val score = proportion * 100 * 0.7 + (chroma - TARGET_CHROMA) * (if (chroma < TARGET_CHROMA) 0.1 else 0.3)
            if (score > bestScore) {
                bestScore = score
                best = color
            }
        }
        return best?.toArgb()
    }

    /**
     * [colors] turned to the hue of [seed]. Every color keeps its lightness and opacity. Colorful ones
     * keep their distance to primary's hue, so a palette of several hues stays one; near-grays take the
     * seed's hue. Each gets at least the style's colorfulness, and primary and focus more of it (and a
     * lightness of at most [MAX_ACCENT_L], so a white focus border becomes a light accent).
     */
    fun apply(colors: ThemeColors, seed: Int, settings: DynamicColors): ThemeColors {
        val seedHue = Lab.fromArgb(seed).hue
        val primary = Colors.parseHex(colors.primary)?.let(Lab::fromArgb)
        val turn = if (primary != null && primary.chroma >= HUE_CHROMA) seedHue - primary.hue else null
        val (minChroma, minAccentChroma) = when (settings.style) {
            DynamicColorStyle.Neutral -> 0.012 to 0.05
            DynamicColorStyle.TonalSpot -> 0.025 to 0.11
            DynamicColorStyle.Vibrant -> 0.05 to 0.17
        }

        fun tint(name: String, hex: String, accent: Boolean = false): String {
            if (name in settings.exclude) return hex
            val argb = Colors.parseHex(hex) ?: return hex
            val lab = Lab.fromArgb(argb)
            val hue = if (turn != null && lab.chroma >= HUE_CHROMA) lab.hue + turn else seedHue
            val l = if (accent) min(lab.l, MAX_ACCENT_L) else lab.l
            val chroma = max(lab.chroma, if (accent) minAccentChroma else minChroma)
            return toHex(Lab.inGamut(l, chroma, hue).toArgb(), alpha = argb ushr 24)
        }

        return colors.copy(
            background = tint("background", colors.background),
            surface = tint("surface", colors.surface),
            primary = tint("primary", colors.primary, accent = true),
            text = tint("text", colors.text),
            textMuted = tint("textMuted", colors.textMuted),
            focus = tint("focus", colors.focus, accent = true),
            custom = colors.custom.mapValues { (name, hex) -> if (Colors.isHex(hex)) tint(name, hex) else hex },
        )
    }

    private fun toHex(argb: Int, alpha: Int): String {
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        return if (alpha == 0xFF) "#$rgb" else "#" + alpha.toString(16).padStart(2, '0').uppercase() + rgb
    }

    private const val MAX_SAMPLES = 4096
    private const val HUE_BINS = 36
    private const val MIN_SEED_CHROMA = 0.03
    private const val MIN_PROPORTION = 0.015
    /** Roughly OKLCH chroma to Material's HCT chroma, for the scoring constants below. */
    private const val CHROMA_SCALE = 300.0
    private const val TARGET_CHROMA = 48.0
    /** Below this chroma a hue is noise: such colors are treated as gray. */
    private const val HUE_CHROMA = 0.04
    private const val MAX_ACCENT_L = 0.86
}

/** An OKLab color. */
internal data class Lab(val l: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)

    /** Hue in radians, 0 until 2 pi. */
    val hue: Double get() = atan2(b, a).let { if (it < 0) it + 2 * PI else it }

    fun toArgb(): Int {
        val (r, g, b) = linearRgb()
        return (0xFF shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(b)
    }

    private fun linearRgb(): Triple<Double, Double, Double> {
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return Triple(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
        )
    }

    private fun inGamut(): Boolean {
        val (r, g, b) = linearRgb()
        return r in -GAMUT_EPSILON..1 + GAMUT_EPSILON && g in -GAMUT_EPSILON..1 + GAMUT_EPSILON && b in -GAMUT_EPSILON..1 + GAMUT_EPSILON
    }

    companion object {
        private const val GAMUT_EPSILON = 1e-4

        fun fromArgb(argb: Int): Lab {
            val r = decode((argb shr 16) and 0xFF)
            val g = decode((argb shr 8) and 0xFF)
            val b = decode(argb and 0xFF)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return Lab(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        /** The color with lightness [l] and [hue], with as much of [chroma] as sRGB can show. */
        fun inGamut(l: Double, chroma: Double, hue: Double): Lab {
            fun at(c: Double) = Lab(l, c * cos(hue), c * sin(hue))
            if (at(chroma).inGamut()) return at(chroma)
            var low = 0.0
            var high = chroma
            repeat(20) {
                val mid = (low + high) / 2
                if (at(mid).inGamut()) low = mid else high = mid
            }
            return at(low)
        }

        private fun decode(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        private fun encode(linear: Double): Int {
            val c = linear.coerceIn(0.0, 1.0)
            val v = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
            return (v * 255).roundToInt().coerceIn(0, 255)
        }
    }
}
