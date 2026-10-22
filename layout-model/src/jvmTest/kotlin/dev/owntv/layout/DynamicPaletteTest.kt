package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.color.DynamicPalette
import dev.owntv.layout.color.Lab
import dev.owntv.layout.model.Colors
import dev.owntv.layout.model.DynamicColorSource
import dev.owntv.layout.model.DynamicColorStyle
import dev.owntv.layout.model.DynamicColors
import dev.owntv.layout.model.ThemeColors
import dev.owntv.layout.validation.Severity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicPaletteTest {

    private fun image(vararg parts: Pair<Int, Int>): IntArray =
        parts.flatMap { (argb, count) -> List(count) { argb } }.toIntArray()

    private fun lab(hex: String) = Lab.fromArgb(Colors.parseHex(hex)!!)

    /** Distance between two hues in degrees. */
    private fun hueDistance(a: Lab, b: Lab): Double {
        val d = abs(a.hue - b.hue) * 180 / PI
        return minOf(d, 360 - d)
    }

    @Test
    fun `seed is the hue that covers the image`() {
        val seed = assertNotNull(DynamicPalette.seed(image(0xFF1E3A8A.toInt() to 700, 0xFF202020.toInt() to 300)))
        assertTrue(hueDistance(Lab.fromArgb(seed), lab("#1E3A8A")) < 10)
    }

    @Test
    fun `a large calm area beats a tiny saturated speck`() {
        val seed = assertNotNull(DynamicPalette.seed(image(0xFF3B6E4F.toInt() to 900, 0xFFFF0000.toInt() to 10, 0xFF111111.toInt() to 90)))
        assertTrue(hueDistance(Lab.fromArgb(seed), lab("#3B6E4F")) < 10)
    }

    @Test
    fun `images without a clear color have no seed`() {
        assertNull(DynamicPalette.seed(image(0xFF000000.toInt() to 100, 0xFF808080.toInt() to 100, 0xFFFFFFFF.toInt() to 100)))
        assertNull(DynamicPalette.seed(image(0x000000FF to 100)))
        assertNull(DynamicPalette.seed(IntArray(0)))
    }

    @Test
    fun `colors take the seed hue and keep their lightness and opacity`() {
        val colors = ThemeColors(surface = "#331F2229", custom = mapOf("tile" to "#F7A3C0"))
        val seed = Colors.parseHex("#2E7D32")!!
        val out = DynamicPalette.apply(colors, seed, DynamicColors())

        assertTrue(out.surface.startsWith("#33"), out.surface)
        listOf(colors.background to out.background, colors.surface to out.surface, colors.textMuted to out.textMuted).forEach { (before, after) ->
            assertTrue(abs(lab(before).l - lab(after).l) < 0.01, "$before -> $after keeps its lightness")
            assertTrue(hueDistance(lab(after), Lab.fromArgb(seed)) < 5, "$before -> $after takes the seed hue")
        }
        assertTrue(hueDistance(lab(out.primary), Lab.fromArgb(seed)) < 3, "primary takes the seed hue: ${out.primary}")
        assertTrue(abs(lab("#F7A3C0").l - lab(out.custom.getValue("tile")).l) < 0.01)
    }

    @Test
    fun `a white focus border becomes a light accent`() {
        val out = DynamicPalette.apply(ThemeColors(focus = "#FFFFFF"), Colors.parseHex("#1565C0")!!, DynamicColors())
        val focus = lab(out.focus)
        assertTrue(focus.chroma > 0.05, "colorful: ${out.focus}")
        assertTrue(focus.l > 0.8, "light: ${out.focus}")
    }

    @Test
    fun `colorful tokens keep their hue distance to primary`() {
        val colors = ThemeColors(primary = "#7AA2F7", custom = mapOf("warm" to "#FF8800"))
        val seed = Colors.parseHex("#C62828")!!
        val out = DynamicPalette.apply(colors, seed, DynamicColors())
        val before = hueDistance(lab(colors.primary), lab("#FF8800"))
        val after = hueDistance(lab(out.primary), lab(out.custom.getValue("warm")))
        assertTrue(abs(before - after) < 6, "$before vs $after")
    }

    @Test
    fun `excluded tokens and stronger styles`() {
        val colors = ThemeColors()
        val seed = Colors.parseHex("#6A1B9A")!!
        val excluded = DynamicPalette.apply(colors, seed, DynamicColors(exclude = listOf("text", "background")))
        assertEquals(colors.text, excluded.text)
        assertEquals(colors.background, excluded.background)

        val neutral = DynamicPalette.apply(colors, seed, DynamicColors(style = DynamicColorStyle.Neutral))
        val vibrant = DynamicPalette.apply(colors, seed, DynamicColors(style = DynamicColorStyle.Vibrant))
        assertTrue(lab(vibrant.surface).chroma > lab(neutral.surface).chroma)
        assertTrue(lab(vibrant.primary).chroma > lab(neutral.primary).chroma)
    }

    @Test
    fun `dynamicColors decodes and checks its token names`() {
        val ok = LayoutCodec.decode(
            docWithRoot(TILE, extra = """"theme": { "dynamicColors": { "source": "focused", "style": "vibrant", "exclude": ["text"] } }, "background": "#102030","""),
        )
        assertTrue(ok.ok && ok.issues.isEmpty(), ok.issues.joinToString("\n"))
        val dynamic = assertNotNull(ok.requireDocument().theme.dynamicColors)
        assertEquals(DynamicColorSource.Focused, dynamic.source)
        assertEquals(DynamicColorStyle.Vibrant, dynamic.style)

        val bad = LayoutCodec.decode(docWithRoot(TILE, extra = """"theme": { "dynamicColors": { "exclude": ["nope"] } },"""))
        assertEquals(listOf("$.theme.dynamicColors.exclude[0]"), bad.paths(Severity.Error))
        assertEquals(listOf("$.theme.dynamicColors"), bad.paths(Severity.Warning))
    }
}
