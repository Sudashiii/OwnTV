package dev.owntv.layout.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CURRENT_SCHEMA_VERSION = 1

@Serializable
@Doc("An OwnTV launcher layout. The screen is 960 x 540 dp; all sizes are in dp.")
data class LayoutDocument(
    @EncodeDefault
    @Doc("Format version. Use 1.")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @Doc("Name shown in launcher settings and layout history.")
    val name: String = "Untitled layout",
    @Doc("Colors, corner radius and focus style used by all widgets.")
    val theme: Theme = Theme(),
    @Doc("Background behind every page. Default: the theme background color.")
    val background: Background? = null,
    @Doc("Show a row of page tabs at the top when there is more than one page. Default false.")
    val pageTabs: Boolean = false,
    @Doc("Pages of the launcher. The first page is the home page.")
    val pages: List<Page>,
)

@Serializable
@Doc("One full screen of the launcher.")
data class Page(
    @Doc("Unique page id, used by go_to_page actions.")
    val id: String,
    @Doc("Title shown in page tabs.")
    val title: String? = null,
    @Doc("Id of the node that gets focus when the page opens. Default: the first focusable widget.")
    val initialFocus: String? = null,
    @Doc("Background for this page only. Overrides the layout background.")
    val background: Background? = null,
    @Doc("Root node. It fills the whole 960 x 540 dp screen.")
    val root: Node,
)

@Serializable
@Doc("Visual theme shared by all widgets.")
data class Theme(
    @Doc("Named colors. Reference them anywhere a color is expected as \"@name\", e.g. \"@primary\".")
    val colors: ThemeColors = ThemeColors(),
    @Range(0.0, 64.0)
    @Doc("Default corner radius of tiles in dp. Default 12.")
    val cornerRadius: Int = 12,
    @Doc("How a focused tile looks.")
    val focus: FocusStyle = FocusStyle(),
    @Range(0.5, 2.0)
    @Doc("Multiplier for all text sizes. Default 1.")
    val fontScale: Float = 1f,
)

@Serializable
@Doc("Theme color tokens. Values must be hex colors (#RRGGBB or #AARRGGBB).")
data class ThemeColors(
    @ColorValue @Doc("Token @background: screen background. Default #0E0F13.")
    val background: String = "#0E0F13",
    @ColorValue @Doc("Token @surface: tile and card background. Default #1F2229.")
    val surface: String = "#1F2229",
    @ColorValue @Doc("Token @primary: accent color. Default #7AA2F7.")
    val primary: String = "#7AA2F7",
    @ColorValue @Doc("Token @text: main text color. Default #ECEDEF.")
    val text: String = "#ECEDEF",
    @ColorValue @Doc("Token @textMuted: secondary text color. Default #9AA0AA.")
    val textMuted: String = "#9AA0AA",
    @ColorValue @Doc("Token @focus: focus border and glow color. Default #FFFFFF.")
    val focus: String = "#FFFFFF",
    @ColorValue @Doc("Extra named colors, e.g. {\"accent2\": \"#FF8800\"}, usable as \"@accent2\".")
    val custom: Map<String, String> = emptyMap(),
) {
    /** Hex value for a token name (without the `@`), or null when the token does not exist. */
    fun token(name: String): String? = when (name) {
        "background" -> background
        "surface" -> surface
        "primary" -> primary
        "text" -> text
        "textMuted" -> textMuted
        "focus" -> focus
        else -> custom[name]
    }

    /** Resolves `#hex` or `@token` to an ARGB int. */
    fun resolve(color: String): Int? =
        if (color.startsWith("@")) token(color.substring(1))?.let(Colors::parseHex) else Colors.parseHex(color)
}

@Serializable
@Doc("Look of the focused tile. D-pad focus must always be clearly visible on a TV.")
data class FocusStyle(
    @Range(1.0, 1.5) @Doc("Scale of the focused tile. Default 1.08.")
    val scale: Float = 1.08f,
    @Range(0.0, 12.0) @Doc("Border width in dp around the focused tile. Default 3.")
    val borderWidth: Int = 3,
    @ColorValue @Doc("Border color of the focused tile. Default @focus.")
    val borderColor: String = "@focus",
    @Doc("Soft glow around the focused tile. Default true.")
    val glow: Boolean = true,
)

@Serializable
@Doc("A full-screen background.")
sealed interface Background

@Serializable
@SerialName("color")
@Doc("A solid color background.")
data class ColorBackground(
    @ColorValue @Doc("Background color.")
    val color: String,
) : Background

@Serializable
@SerialName("gradient")
@Doc("A linear gradient background.")
data class GradientBackground(
    @ColorValue @Doc("Two or more colors, from start to end.")
    val colors: List<String>,
    @Range(0.0, 360.0)
    @Doc("Direction in degrees, CSS style: 0 = bottom to top, 90 = left to right, 180 = top to bottom. Default 180.")
    val angle: Int = 180,
) : Background

@Serializable
@SerialName("image")
@Doc("An image background.")
data class ImageBackground(
    @Doc("Image location: an https:// URL or \"asset:<path>\" for an image bundled with the app.")
    val src: String,
    @Range(0.0, 1.0) @Doc("Darkens the image so text stays readable: 0 = none, 1 = black. Default 0.3.")
    val dim: Float = 0.3f,
    @Doc("How the image fills the screen. Default cover.")
    val fit: ImageFit = ImageFit.Cover,
) : Background

@Serializable
enum class ImageFit {
    @SerialName("cover") Cover,
    @SerialName("contain") Contain,
    @SerialName("fill") Fill,
}
