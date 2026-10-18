package dev.owntv.layout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A node in the layout tree. Every node has the common properties below; their docs live in
 * [CommonNodeFields] so they are not repeated on every class. JSON objects carry a `"type"`
 * discriminator that [NodeSerializer] maps through [NodeRegistry].
 */
@Serializable(with = NodeSerializer::class)
sealed interface Node {
    val id: String
    val width: Size?
    val height: Size?
    val weight: Float?
    val padding: Padding?
    val background: String?
    val cornerRadius: Int?
    val align: Alignment?
    val span: Int?
    val visible: Boolean
}

sealed interface ContainerNode : Node {
    val children: List<Node>
}

/** Docs and constraints for the properties every node shares. */
object CommonNodeFields {
    val docs: Map<String, String> = linkedMapOf(
        "id" to "Unique id (letters, digits, _ . -). Used by initialFocus, the editor and AI edits.",
        "width" to "\"fill\" (all available width), \"wrap\" (fit content) or a number in dp. Default depends on the widget.",
        "height" to "\"fill\" (all available height), \"wrap\" (fit content) or a number in dp. Default depends on the widget.",
        "weight" to "Share of the remaining space inside a row or column, like CSS flex-grow.",
        "padding" to "Inner padding in dp: a number, [horizontal, vertical] or [start, top, end, bottom].",
        "background" to "Background color behind this node.",
        "cornerRadius" to "Corner radius in dp of this node's background.",
        "align" to "Position inside the parent. In a stack all 9 values apply; in a column only the horizontal part is used; in a row only the vertical part.",
        "span" to "Number of grid columns this node covers. Only used inside a grid. Default 1.",
        "visible" to "Set to false to hide the node without deleting it. Default true.",
    )
    val colorFields = setOf("background")
    val ranges: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "weight" to 0.0..100.0,
        "cornerRadius" to 0.0..200.0,
        "span" to 1.0..12.0,
    )
}

// ---------------------------------------------------------------- containers

@Serializable
@SerialName("column")
@Doc("Stacks children vertically, top to bottom.")
data class ColumnNode(
    override val id: String,
    @Doc("Child nodes, top to bottom.")
    override val children: List<Node> = emptyList(),
    @Range(0.0, 400.0) @Doc("Gap between children in dp. Default 0.")
    val spacing: Int = 0,
    @Doc("Horizontal alignment of children. Default start.")
    val contentAlign: HAlign = HAlign.Start,
    @Doc("Vertical distribution of children. Default start.")
    val arrange: Arrange = Arrange.Start,
    @Doc("Scroll vertically when the content is taller than the column. The focused widget scrolls into view. Default false.")
    val scroll: Boolean = false,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : ContainerNode

@Serializable
@SerialName("row")
@Doc("Places children side by side, left to right.")
data class RowNode(
    override val id: String,
    @Doc("Child nodes, left to right.")
    override val children: List<Node> = emptyList(),
    @Range(0.0, 400.0) @Doc("Gap between children in dp. Default 0.")
    val spacing: Int = 0,
    @Doc("Vertical alignment of children. Default center.")
    val contentAlign: VAlign = VAlign.Center,
    @Doc("Horizontal distribution of children. Default start.")
    val arrange: Arrange = Arrange.Start,
    @Doc("Scroll horizontally when the content is wider than the row. Default false.")
    val scroll: Boolean = false,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : ContainerNode

@Serializable
@SerialName("grid")
@Doc("Arranges children in a fixed number of equal-width columns. Children can cover several columns with span.")
data class GridNode(
    override val id: String,
    @Range(1.0, 12.0) @Doc("Number of columns.")
    val columns: Int,
    @Doc("Child nodes, filled row by row.")
    override val children: List<Node> = emptyList(),
    @Range(0.0, 400.0) @Doc("Gap between cells in dp, both directions. Default 16.")
    val spacing: Int = 16,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : ContainerNode

@Serializable
@SerialName("stack")
@Doc("Layers children on top of each other; later children are drawn on top. Position children with align.")
data class StackNode(
    override val id: String,
    @Doc("Child nodes, bottom layer first.")
    override val children: List<Node> = emptyList(),
    @Doc("Default position of children that do not set align. Default top_start.")
    val contentAlign: Alignment = Alignment.TopStart,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : ContainerNode

// ---------------------------------------------------------------- app widgets

@Serializable
@SerialName("app_tile")
@Doc("One focusable tile for one app. Opens the app on click.")
data class AppTileNode(
    override val id: String,
    @SerialName("package") @Doc("Package name of the app, e.g. com.netflix.ninja.")
    val packageName: String,
    @Doc("Label to show instead of the app name.")
    val label: String? = null,
    @Doc("Tile look. Default banner, medium, label on focus.")
    val tile: TileStyle = TileStyle(),
    @Doc("What to show when the app is not installed. Default placeholder.")
    val missing: Missing = Missing.Placeholder,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

@Serializable
@SerialName("app_row")
@Doc("A horizontally scrolling row of app tiles with an optional title. Default width fill.")
data class AppRowNode(
    override val id: String,
    @Doc("Which apps to show.")
    val source: AppSource,
    @Doc("Title above the row.")
    val title: String? = null,
    @Doc("Tile look. Default banner, medium, label on focus.")
    val tile: TileStyle = TileStyle(),
    @Range(0.0, 200.0) @Doc("Gap between tiles in dp. Default 16.")
    val spacing: Int = 16,
    @Range(1.0, 500.0) @Doc("Maximum number of tiles.")
    val limit: Int? = null,
    @Doc("For a packages source: what to show for apps that are not installed. Default hide.")
    val missing: Missing = Missing.Hide,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

@Serializable
@SerialName("app_grid")
@Doc("A grid of app tiles with an optional title. Default width fill. Put it in a scrolling column when it can be taller than the screen.")
data class AppGridNode(
    override val id: String,
    @Doc("Which apps to show.")
    val source: AppSource,
    @Doc("Title above the grid.")
    val title: String? = null,
    @Range(1.0, 12.0) @Doc("Number of columns. Default 5.")
    val columns: Int = 5,
    @Doc("Tile look. Default banner, medium, label on focus. Banner and card tiles stretch to the column width.")
    val tile: TileStyle = TileStyle(),
    @Range(0.0, 200.0) @Doc("Gap between tiles in dp. Default 16.")
    val spacing: Int = 16,
    @Range(1.0, 500.0) @Doc("Maximum number of tiles.")
    val limit: Int? = null,
    @Doc("For a packages source: what to show for apps that are not installed. Default hide.")
    val missing: Missing = Missing.Hide,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

@Serializable
@SerialName("shortcut")
@Doc("A focusable tile with a custom label, icon or image that runs an action.")
data class ShortcutNode(
    override val id: String,
    @Doc("Text on the tile.")
    val label: String,
    @Doc("What happens on click.")
    val action: Action,
    @Doc("Icon: one of settings, home, search, star, info, play, favorite, apps, add, refresh, notifications, person, lock, menu, or any emoji such as \"🎬\".")
    val icon: String? = null,
    @Doc("Image instead of an icon: an https:// URL or \"asset:<path>\".")
    val image: String? = null,
    @ColorValue @Doc("Tile color. Default @surface.")
    val color: String? = null,
    @Doc("Tile look. Default card, medium.")
    val tile: TileStyle = TileStyle(style = TileShape.Card),
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

// ---------------------------------------------------------------- content widgets

@Serializable
@SerialName("text")
@Doc("Static text. Not focusable.")
data class TextNode(
    override val id: String,
    @Doc("The text.")
    val text: String,
    @Doc("Text size preset. Default body.")
    val style: TextStyleName = TextStyleName.Body,
    @ColorValue @Doc("Text color. Default @text.")
    val color: String? = null,
    @Doc("Bold text. Default false.")
    val bold: Boolean = false,
    @Doc("Alignment of the text inside its box. Default start.")
    val textAlign: TextAlignment = TextAlignment.Start,
    @Range(1.0, 20.0) @Doc("Maximum number of lines; longer text ends with an ellipsis.")
    val maxLines: Int? = null,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

@Serializable
@SerialName("clock")
@Doc("Current time and/or date. Not focusable.")
data class ClockNode(
    override val id: String,
    @Doc("java.time pattern, e.g. \"HH:mm\" (24h), \"h:mm a\" (12h) or \"EEEE, d MMMM\" (date). Default HH:mm.")
    val format: String = "HH:mm",
    @Doc("Text size preset. Default display.")
    val style: TextStyleName = TextStyleName.Display,
    @ColorValue @Doc("Text color. Default @text.")
    val color: String? = null,
    @Doc("Bold text. Default false.")
    val bold: Boolean = false,
    @Doc("Alignment of the text inside its box. Default start.")
    val textAlign: TextAlignment = TextAlignment.Start,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

@Serializable
@SerialName("image")
@Doc("A picture. Not focusable. Give it a width and height.")
data class ImageNode(
    override val id: String,
    @Doc("Image location: an https:// URL or \"asset:<path>\" for an image bundled with the app.")
    val src: String,
    @Doc("How the image fills its box. Default contain.")
    val fit: ImageFit = ImageFit.Contain,
    @Range(0.0, 1.0) @Doc("Opacity, 0 to 1. Default 1.")
    val alpha: Float = 1f,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

@Serializable
@SerialName("spacer")
@Doc("Empty space. Use width/height for a fixed gap or weight to push siblings apart.")
data class SpacerNode(
    override val id: String,
    override val width: Size? = null,
    override val height: Size? = null,
    override val weight: Float? = null,
    override val padding: Padding? = null,
    override val background: String? = null,
    override val cornerRadius: Int? = null,
    override val align: Alignment? = null,
    override val span: Int? = null,
    override val visible: Boolean = true,
) : Node

/** A node whose `type` this version does not know. It is kept as-is and rendered as a placeholder. */
data class UnknownNode(val type: String, val raw: JsonObject) : Node {
    override val id: String = (raw["id"] as? JsonPrimitive)?.contentOrNull ?: ""
    override val width: Size? get() = null
    override val height: Size? get() = null
    override val weight: Float? get() = null
    override val padding: Padding? get() = null
    override val background: String? get() = null
    override val cornerRadius: Int? get() = null
    override val align: Alignment? get() = null
    override val span: Int? get() = null
    override val visible: Boolean get() = true
}

// ---------------------------------------------------------------- shared value types

@Serializable
@Doc("Look of app and shortcut tiles.")
data class TileStyle(
    @Doc("banner = 16:9 app banner; icon = square app icon with the label below; card = banner with the label always below. Default banner.")
    val style: TileShape = TileShape.Banner,
    @Doc("small, medium or large. Banner widths: 128, 160, 208 dp. Icon sizes: 56, 72, 96 dp. Default medium.")
    val size: TileSize = TileSize.Medium,
    @Doc("When to show the app name: always, focused or never. Default focused for banners, always for icon and card.")
    val showLabel: LabelMode? = null,
)

@Serializable
enum class TileShape {
    @SerialName("banner") Banner,
    @SerialName("icon") Icon,
    @SerialName("card") Card,
}

@Serializable
enum class TileSize {
    @SerialName("small") Small,
    @SerialName("medium") Medium,
    @SerialName("large") Large,
}

@Serializable
enum class LabelMode {
    @SerialName("always") Always,
    @SerialName("focused") Focused,
    @SerialName("never") Never,
}

@Serializable
enum class Missing {
    @SerialName("hide") Hide,
    @SerialName("placeholder") Placeholder,
}

@Serializable
enum class TextStyleName {
    @SerialName("display") Display,
    @SerialName("headline") Headline,
    @SerialName("title") Title,
    @SerialName("body") Body,
    @SerialName("caption") Caption,
}

@Serializable
enum class TextAlignment {
    @SerialName("start") Start,
    @SerialName("center") Center,
    @SerialName("end") End,
}

@Serializable
enum class HAlign {
    @SerialName("start") Start,
    @SerialName("center") Center,
    @SerialName("end") End,
}

@Serializable
enum class VAlign {
    @SerialName("top") Top,
    @SerialName("center") Center,
    @SerialName("bottom") Bottom,
}

@Serializable
enum class Arrange {
    @SerialName("start") Start,
    @SerialName("center") Center,
    @SerialName("end") End,
    @SerialName("space_between") SpaceBetween,
    @SerialName("space_around") SpaceAround,
    @SerialName("space_evenly") SpaceEvenly,
}

@Serializable
enum class Alignment {
    @SerialName("top_start") TopStart,
    @SerialName("top") Top,
    @SerialName("top_end") TopEnd,
    @SerialName("start") Start,
    @SerialName("center") Center,
    @SerialName("end") End,
    @SerialName("bottom_start") BottomStart,
    @SerialName("bottom") Bottom,
    @SerialName("bottom_end") BottomEnd,
}
