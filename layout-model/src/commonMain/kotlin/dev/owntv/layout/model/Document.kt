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

const val CURRENT_SCHEMA_VERSION = 3

@Serializable
@Doc("An OwnTV launcher layout. The screen is 960 x 540 dp; all sizes are in dp.")
data class LayoutDocument(
    @EncodeDefault
    @Doc("Format version. Use 3.")
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
    @Doc("Per-app settings keyed by package name: label, artwork, hero backdrop and description.")
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
    @Doc("How scrolling rows and columns move to the focused item. Containers can set their own. Default: a quick spring that keeps up with a held remote.")
    val scrollMotion: ScrollMotion? = null,
    @Doc("Take the theme colors' hue from the wallpaper, like Material You and Projectivy. Every theme color keeps its lightness and opacity; primary and focus also become clearly colorful. Images without a clear color keep the layout's own colors. Default: off.")
    val dynamicColors: DynamicColors? = null,
)

@Serializable
@Doc("Theme colors taken from an image.")
data class DynamicColors(
    @Doc("wallpaper (the page background) or focused (the focused tile's backdrop or app banner, else the wallpaper). Default wallpaper.")
    val source: DynamicColorSource = DynamicColorSource.Wallpaper,
    @Doc("How colorful the theme becomes: neutral (a light tint), tonal_spot (calm, the Material You default) or vibrant. Colors that are already more colorful keep it. Default tonal_spot.")
    val style: DynamicColorStyle = DynamicColorStyle.TonalSpot,
    @Doc("Theme color tokens that keep their own color, e.g. [\"text\", \"tile\"]. Default: none, every token follows the image.")
    val exclude: List<String> = emptyList(),
)

@Serializable
enum class DynamicColorSource {
    @SerialName("wallpaper") Wallpaper,
    @SerialName("focused") Focused,
}

@Serializable
enum class DynamicColorStyle {
    @SerialName("neutral") Neutral,
    @SerialName("tonal_spot") TonalSpot,
    @SerialName("vibrant") Vibrant,
}

@Serializable
@Doc("How a scroll container moves to the focused item.")
data class ScrollMotion(
    @Doc("spring (quick, keeps up with a held remote), ease_out (starts fast, glides to a stop), ease_in_out (gentle start and stop) or linear. Default spring.")
    val curve: ScrollCurve = ScrollCurve.Spring,
    @Range(50.0, 1500.0)
    @Doc("About how long one scroll takes, in milliseconds. Longer is slower and softer. Default 120.")
    val duration: Int = 120,
)

@Serializable
enum class ScrollCurve {
    @SerialName("spring") Spring,
    @SerialName("ease_out") EaseOut,
    @SerialName("ease_in_out") EaseInOut,
    @SerialName("linear") Linear,
}

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
    @Doc("Draw a moving light sheen across the focused tile, like the tvOS focus effect. Default false.")
    val highlight: Boolean = false,
    @Doc("Movement of the focused tile: none, or sway (a gentle 3D tilt, like tvOS parallax). Default none.")
    val motion: FocusMotion = FocusMotion.None,
)

@Serializable
enum class FocusMotion {
    @SerialName("none") None,
    @SerialName("sway") Sway,
}

/**
 * A background: a solid color, a gradient, an image, a video, a slideshow or glass. In JSON a plain color string such as
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
    @Doc("Brightness, contrast, saturation and hue of the image. Default: unchanged.")
    val adjust: ImageAdjust? = null,
) : Background

@Serializable
@SerialName("video")
@Doc("A video that plays silently in a loop, e.g. an aquarium or a fireplace. Shown on the TV; the web preview shows a placeholder.")
data class VideoBackground(
    @Doc("Video location: an https:// URL (MP4, WebM, MKV or an HLS .m3u8 stream) or \"asset:<path>\".")
    val src: String,
    @Range(0.0, 1.0) @Doc("Darkens the video so text stays readable: 0 = none, 1 = black. Default 0.3.")
    val dim: Float = 0.3f,
    @Range(0.0, 60.0) @Doc("Blur radius in dp (Android 12 and newer). Default 0.")
    val blur: Int = 0,
    @Doc("How the video fills the area. Default cover.")
    val fit: ImageFit = ImageFit.Cover,
    @Doc("Brightness, contrast, saturation and hue of the video. Default: unchanged.")
    val adjust: ImageAdjust? = null,
) : Background

@Serializable
@SerialName("slideshow")
@Doc("Wallpapers that take turns: pictures (animated GIFs too) and videos from a list, from a folder on the TV and from a web address that returns a new random picture each time. They change on a timer, at random or in order, with a crossfade; a shortcut with the next_wallpaper action changes them at once.")
data class SlideshowBackground(
    @Doc("Pictures and videos to show, each with its own look. Sources ending in .mp4, .webm, .mkv, .mov or .m3u8 play as videos.")
    val images: List<SlideshowImage> = emptyList(),
    @Doc("A folder on the TV whose pictures and videos join the slideshow, e.g. \"Pictures/OwnTV\" (its subfolders and the same folder on USB drives count too). OwnTV asks once for access to photos and videos.")
    val folder: String? = null,
    @Doc("A web address that returns a different picture on every request, e.g. \"https://picsum.photos/1920/1080\". It takes one turn in the slideshow and brings a new picture every time.")
    val randomUrl: String? = null,
    @Range(0.0, 86400.0)
    @Doc("Seconds between changes; 0 changes only when the launcher starts and with next_wallpaper. Default 300 (5 minutes).")
    val interval: Int = 300,
    @Doc("shuffle (a random wallpaper each time, never the same one twice in a row) or sequential (in the listed order, then the folder). Default shuffle.")
    val order: SlideshowOrder = SlideshowOrder.Shuffle,
    @Range(0.0, 5000.0)
    @Doc("Length of the crossfade between wallpapers in milliseconds; 0 switches at once. Default 1000.")
    val transition: Int = 1000,
    @Range(0.0, 1.0) @Doc("Darkens every wallpaper that does not set its own dim: 0 = none, 1 = black. Default 0.3.")
    val dim: Float = 0.3f,
    @Range(0.0, 60.0) @Doc("Blur radius in dp for wallpapers that do not set their own (Android 12 and newer). Default 0.")
    val blur: Int = 0,
    @Doc("How wallpapers that do not set their own fit fill the area. Default cover.")
    val fit: ImageFit = ImageFit.Cover,
    @Doc("Brightness, contrast, saturation and hue for wallpapers that do not set their own. Default: unchanged.")
    val adjust: ImageAdjust? = null,
) : Background

@Serializable
@Doc("One picture or video of a slideshow. Unset properties come from the slideshow.")
data class SlideshowImage(
    @Doc("Location: an https:// URL or \"asset:<path>\". Animated GIFs and WebPs play; .mp4, .webm, .mkv, .mov and .m3u8 play as videos.")
    val src: String,
    @Range(0.0, 1.0) @Doc("Darkens this wallpaper: 0 = none, 1 = black. Default: the slideshow's dim.")
    val dim: Float? = null,
    @Range(0.0, 60.0) @Doc("Blur radius in dp. Default: the slideshow's blur.")
    val blur: Int? = null,
    @Doc("How this wallpaper fills the area. Default: the slideshow's fit.")
    val fit: ImageFit? = null,
    @Doc("Brightness, contrast, saturation and hue of this wallpaper. Default: the slideshow's adjust.")
    val adjust: ImageAdjust? = null,
)

@Serializable
enum class SlideshowOrder {
    @SerialName("shuffle") Shuffle,
    @SerialName("sequential") Sequential,
}

@Serializable
@Doc("Color adjustments for a picture or video, like a photo editor's.")
data class ImageAdjust(
    @Range(-1.0, 1.0) @Doc("Brighter (up to 1) or darker (down to -1). Default 0.")
    val brightness: Float = 0f,
    @Range(0.0, 2.0) @Doc("1 keeps the contrast, 0 is flat gray, 2 doubles it. Default 1.")
    val contrast: Float = 1f,
    @Range(0.0, 2.0) @Doc("1 keeps the colors, 0 is black and white, 2 is twice as colorful. Default 1.")
    val saturation: Float = 1f,
    @Range(-180.0, 180.0) @Doc("Turns every color around the color wheel by this many degrees. Default 0.")
    val hue: Int = 0,
)

@Serializable
@SerialName("glass")
@Doc("Frosted Liquid Glass: blurs and refracts the hero, images and page background behind the node, with a light rim. Android 12 and newer; older devices show the tint only.")
data class GlassBackground(
    @ColorValue @Doc("Tint over the glass; use a translucent color. Default #1FFFFFFF.")
    val tint: String = "#1FFFFFFF",
    @Doc("regular = frosted, clear = mostly see-through with stronger refraction. Default regular.")
    val variant: GlassVariant = GlassVariant.Regular,
) : Background

@Serializable
enum class GlassVariant {
    @SerialName("regular") Regular,
    @SerialName("clear") Clear,
}

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
        "video" to VideoBackground.serializer(),
        "slideshow" to SlideshowBackground.serializer(),
        "glass" to GlassBackground.serializer(),
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
            is VideoBackground -> "video" to output.json.encodeToJsonElement(VideoBackground.serializer(), value)
            is SlideshowBackground -> "slideshow" to output.json.encodeToJsonElement(SlideshowBackground.serializer(), value)
            is GlassBackground -> "glass" to output.json.encodeToJsonElement(GlassBackground.serializer(), value)
            is ColorBackground -> error("handled above")
        }
        output.encodeJsonElement(JsonObject(linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type)) + body.jsonObject))
    }
}
