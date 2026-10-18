package dev.owntv.layout.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val CURRENT_SCHEMA_VERSION = 2

@Serializable
@Doc("An OwnTV launcher layout. The screen is 960 x 540 dp; all sizes are in dp.")
data class LayoutDocument(
    @EncodeDefault
    @Doc("Format version. Use 2.")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @Doc("Name shown in launcher settings and layout history.")
    val name: String = "Untitled layout",
    @Doc("One sentence about this layout, shown in the template and theme lists.")
    val description: String? = null,
    @Doc("Colors, fonts, corner radius and focus style used by all widgets.")
    val theme: Theme = Theme(),
    @Doc("Background behind every page. Default: the theme background color.")
    val background: Background? = null,
    @Doc("Named sets of node properties. A node uses one with \"style\": \"name\"; its own properties win. A style may itself set \"style\" to build on another one.")
    val styles: Map<String, JsonObject> = emptyMap(),
    @Doc("Default properties per node type, e.g. {\"app_row\": {\"spacing\": 24}}. Applied before styles; the node's own properties win.")
    val defaults: Map<String, JsonObject> = emptyMap(),
    @Doc("Per-app settings keyed by package name: label, artwork, hero backdrop and description, hidden.")
    val apps: Map<String, AppOverride> = emptyMap(),
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
@Doc("Settings for one app, used everywhere the app appears.")
data class AppOverride(
    @Doc("Name to show instead of the app's own.")
    val label: String? = null,
    @Doc("Image replacing the app's banner on banner and card tiles: an https:// URL or \"asset:<path>\".")
    val banner: String? = null,
    @Doc("Image replacing the app's icon on icon tiles.")
    val icon: String? = null,
    @Doc("Large image a hero shows while this app is focused.")
    val backdrop: String? = null,
    @Doc("Text a hero shows while this app is focused.")
    val description: String? = null,
    @ColorValue @Doc("Tile background color behind the app's artwork.")
    val color: String? = null,
    @Doc("Leave the app out of all, category, recent and folder sources. Explicit package lists still show it. Default false.")
    val hidden: Boolean = false,
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
    @Doc("Font family for all text. Default sans.")
    val fontFamily: FontName = FontName.Sans,
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
    @Range(0.0, 12.0) @Doc("Border width in dp around the focused tile; 0 for none. Default 3.")
    val borderWidth: Int = 3,
    @ColorValue @Doc("Border color of the focused tile. Default @focus.")
    val borderColor: String = "@focus",
    @Doc("Soft glow or shadow around the focused tile. Default true.")
    val glow: Boolean = true,
    @ColorValue @Doc("Glow color; use a dark, semi-transparent color like #99000000 for a drop shadow. Default: the border color at 55% opacity.")
    val glowColor: String? = null,
    @Range(0.0, 48.0) @Doc("Glow size in dp. Default 10.")
    val glowSize: Int = 10,
)

/**
 * A background: a solid color, a gradient or an image. In JSON a plain color string such as
 * `"#202020"` or `"@surface"` is a shorthand for `{"type": "color", "color": …}`.
 */
@Serializable(with = BackgroundSerializer::class)
sealed interface Background

@Serializable
@SerialName("color")
@Doc("A solid color.")
data class ColorBackground(
    @ColorValue @Doc("Background color.")
    val color: String,
) : Background

@Serializable
@SerialName("gradient")
@Doc("A linear gradient.")
data class GradientBackground(
    @ColorValue @Doc("Two or more colors, from start to end.")
    val colors: List<String>,
    @Range(0.0, 360.0)
    @Doc("Direction in degrees, CSS style: 0 = bottom to top, 90 = left to right, 180 = top to bottom. Default 180.")
    val angle: Int = 180,
) : Background

@Serializable
@SerialName("image")
@Doc("An image.")
data class ImageBackground(
    @Doc("Image location: an https:// URL or \"asset:<path>\" for an image bundled with the app.")
    val src: String,
    @Range(0.0, 1.0) @Doc("Darkens the image so text stays readable: 0 = none, 1 = black. Default 0.3.")
    val dim: Float = 0.3f,
    @Range(0.0, 60.0) @Doc("Blur radius in dp (Android 12 and newer). Default 0.")
    val blur: Int = 0,
    @Doc("How the image fills the area. Default cover.")
    val fit: ImageFit = ImageFit.Cover,
) : Background

@Serializable
enum class ImageFit {
    @SerialName("cover") Cover,
    @SerialName("contain") Contain,
    @SerialName("fill") Fill,
}

/** Reads a background from a color string or a `{"type": …}` object; writes solid colors as strings. */
object BackgroundSerializer : KSerializer<Background> {
    const val SERIAL_NAME = "dev.owntv.layout.Background"

    val subtypes: Map<String, KSerializer<out Background>> = linkedMapOf(
        "color" to ColorBackground.serializer(),
        "gradient" to GradientBackground.serializer(),
        "image" to ImageBackground.serializer(),
    )

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor(SERIAL_NAME, PolymorphicKind.SEALED)

    override fun deserialize(decoder: Decoder): Background {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Backgrounds can only be read from JSON")
        val element = input.decodeJsonElement()
        if (element is JsonPrimitive && element.isString) return ColorBackground(element.content)
        val obj = element as? JsonObject ?: throw SerializationException("A background must be a color string or an object")
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val serializer = subtypes[type] ?: throw SerializationException("Unknown background type \"$type\"")
        return input.json.decodeFromJsonElement(serializer, JsonObject(obj - "type"))
    }

    override fun serialize(encoder: Encoder, value: Background) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Backgrounds can only be written as JSON")
        if (value is ColorBackground) {
            output.encodeJsonElement(JsonPrimitive(value.color))
            return
        }
        val (type, body) = when (value) {
            is GradientBackground -> "gradient" to output.json.encodeToJsonElement(GradientBackground.serializer(), value)
            is ImageBackground -> "image" to output.json.encodeToJsonElement(ImageBackground.serializer(), value)
            is ColorBackground -> error("handled above")
        }
        output.encodeJsonElement(JsonObject(linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type)) + body.jsonObject))
    }
}
