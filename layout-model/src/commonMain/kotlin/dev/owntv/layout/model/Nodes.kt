package dev.owntv.layout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A node in the layout tree. In JSON every node is one flat object: `"type"`, `"id"`, the node's own
 * properties and the [NodeCommon] properties. [NodeSerializer] splits and joins them.
 */
@Serializable(with = NodeSerializer::class)
sealed interface Node {
    val id: String
    val common: NodeCommon

    /** Returns this node with [common] replaced. */
    fun withCommon(common: NodeCommon): Node
}

sealed interface ContainerNode : Node {
    val children: List<Node>
}

val Node.visible: Boolean get() = common.visible

/** Properties every node accepts, written flat next to the node's own properties. */
@Serializable
@Doc("Properties every node accepts.")
data class NodeCommon(
    @Doc("\"fill\" (all available width), \"wrap\" (fit content) or a number in dp. Default depends on the widget.")
    val width: Size? = null,
    @Doc("\"fill\" (all available height), \"wrap\" (fit content) or a number in dp. Default depends on the widget.")
    val height: Size? = null,
    @Range(0.0, 100.0) @Doc("Share of the remaining space inside a row or column, like CSS flex-grow.")
    val weight: Float? = null,
    @Doc("Inner padding in dp: a number, [horizontal, vertical] or [start, top, end, bottom].")
    val padding: Padding? = null,
    @Doc("Background behind this node: a color like \"#202020\" or \"@surface\", or a gradient or image object.")
    val background: Background? = null,
    @Doc("Outline around this node.")
    val border: BorderStyle? = null,
    @Range(0.0, 200.0) @Doc("Corner radius in dp of the background, border and shadow.")
    val cornerRadius: Int? = null,
    @Range(0.0, 1.0) @Doc("Opacity of the whole node, 0 to 1. Default 1.")
    val opacity: Float? = null,
    @Range(0.0, 1.0) @Doc("Opacity of the whole node while focus is not inside it, e.g. 0.5 dims every section except the focused one (Projectivy style). Default: no dimming.")
    val unfocusedOpacity: Float? = null,
    @Range(0.0, 48.0) @Doc("Drop shadow size in dp. Default none.")
    val shadow: Int? = null,
    @Doc("Position inside the parent. In a stack all 9 values apply; in a column only the horizontal part is used; in a row only the vertical part.")
    val align: Alignment? = null,
    @Range(1.0, 12.0) @Doc("Number of grid columns this node covers. Only used inside a grid. Default 1.")
    val span: Int? = null,
    @Doc("Name of an entry in the document's styles. Its properties apply where this node does not set its own.")
    val style: String? = null,
    @Doc("Where the D-pad goes from this node: ids of other nodes on the same page. On a container it applies when focus leaves the container in that direction.")
    val focus: FocusLinks? = null,
    @Doc("When something inside this node gets focus, scroll so the whole node is visible (like the Apple TV top shelf). Default false.")
    val revealOnFocus: Boolean = false,
    @Doc("Id of a node on the same page: this node is only shown (and takes space) while focus is inside that node, e.g. the name and description of the focused tile under its section. Default: always shown.")
    val showWhenFocused: String? = null,
    @Doc("With showWhenFocused: keep the node's space while it is hidden and only fade it in and out, so nothing around it moves when focus changes. While the watched node has nothing to focus (an empty row) it takes no space. Default false (the node collapses).")
    val keepSpace: Boolean = false,
    @Doc("Set to false to hide the node without deleting it. Default true.")
    val visible: Boolean = true,
)

@Serializable
@Doc("Outline around a node.")
data class BorderStyle(
    @Range(0.0, 24.0) @Doc("Width in dp. Default 2.")
    val width: Int = 2,
    @ColorValue @Doc("Color. Default @textMuted.")
    val color: String = "@textMuted",
)

@Serializable
@Doc("Explicit D-pad targets. Each value is the id of a node on the same page.")
data class FocusLinks(
    @Doc("Node that gets focus when Up is pressed.")
    val up: String? = null,
    @Doc("Node that gets focus when Down is pressed.")
    val down: String? = null,
    @Doc("Node that gets focus when Left is pressed.")
    val left: String? = null,
    @Doc("Node that gets focus when Right is pressed.")
    val right: String? = null,
) {
    val ids: List<String> get() = listOfNotNull(up, down, left, right)
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
    @Doc("Where a focused child is scrolled to: nearest (just into view), start (to the top, minus scrollMargin) or center. Default nearest.")
    val scrollAlign: ScrollAlign = ScrollAlign.Nearest,
    @Range(0.0, 400.0) @Doc("Distance in dp from the top kept when scrollAlign is start. Default 32.")
    val scrollMargin: Int = 32,
    @Doc("How the column scrolls to the focused child. Default: the theme's scrollMotion.")
    val scrollMotion: ScrollMotion? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : ContainerNode {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

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
    @Doc("Where a focused child is scrolled to: nearest, start (to the left edge, minus scrollMargin) or center. Default nearest.")
    val scrollAlign: ScrollAlign = ScrollAlign.Nearest,
    @Range(0.0, 400.0) @Doc("Distance in dp from the left kept when scrollAlign is start. Default 32.")
    val scrollMargin: Int = 32,
    @Doc("How the row scrolls to the focused child. Default: the theme's scrollMotion.")
    val scrollMotion: ScrollMotion? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : ContainerNode {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

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
    @Transient override val common: NodeCommon = NodeCommon(),
) : ContainerNode {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("stack")
@Doc("Layers children on top of each other; later children are drawn on top. Position children with align.")
data class StackNode(
    override val id: String,
    @Doc("Child nodes, bottom layer first.")
    override val children: List<Node> = emptyList(),
    @Doc("Default position of children that do not set align. Default top_start.")
    val contentAlign: Alignment = Alignment.TopStart,
    @Transient override val common: NodeCommon = NodeCommon(),
) : ContainerNode {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

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
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("app_row")
@Doc("A horizontally scrolling row of app tiles with an optional title. Default width fill.")
data class AppRowNode(
    override val id: String,
    @Doc("Which apps to show.")
    val source: AppSource,
    @Doc("Title above the row.")
    val title: String? = null,
    @Doc("Icon before the title: a shortcut icon name such as tv or smartphone, or an emoji.")
    val titleIcon: String? = null,
    @Doc("Tile look. Default banner, medium, label on focus.")
    val tile: TileStyle = TileStyle(),
    @Range(0.0, 200.0) @Doc("Gap between tiles in dp. Default 16.")
    val spacing: Int = 16,
    @Range(0.0, 500.0) @Doc("Leave out the first N apps of the source, e.g. the ones a dock row above already shows. Default 0.")
    val skip: Int = 0,
    @Range(1.0, 500.0) @Doc("Maximum number of tiles.")
    val limit: Int? = null,
    @Doc("For a list source: what to show for apps in the list that are not installed. Default hide.")
    val missing: Missing = Missing.Hide,
    @Doc("Where the focused tile is scrolled to: nearest, start (Google TV style, focused tile stays at the left) or center. Default nearest.")
    val scrollAlign: ScrollAlign = ScrollAlign.Nearest,
    @Range(0.0, 400.0) @Doc("Distance in dp of the focused tile from the row's left edge when scrollAlign is start. Default 0 (in line with the title).")
    val scrollMargin: Int = 0,
    @Doc("How the row scrolls to the focused tile. Default: the theme's scrollMotion.")
    val scrollMotion: ScrollMotion? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("app_grid")
@Doc("A grid of app tiles with an optional title. Default width fill. Put it in a scrolling column when it can be taller than the screen.")
data class AppGridNode(
    override val id: String,
    @Doc("Which apps to show.")
    val source: AppSource,
    @Doc("Title above the grid.")
    val title: String? = null,
    @Doc("Icon before the title: a shortcut icon name such as tv or smartphone, or an emoji.")
    val titleIcon: String? = null,
    @Range(1.0, 12.0) @Doc("Number of columns. Default 5.")
    val columns: Int = 5,
    @Doc("Tile look. Default banner, medium, label on focus. Banner and card tiles stretch to the column width.")
    val tile: TileStyle = TileStyle(),
    @Range(0.0, 200.0) @Doc("Gap between tiles in dp. Default 16.")
    val spacing: Int = 16,
    @Range(0.0, 500.0) @Doc("Leave out the first N apps of the source, e.g. the ones a dock row above already shows. Default 0.")
    val skip: Int = 0,
    @Range(1.0, 500.0) @Doc("Maximum number of tiles.")
    val limit: Int? = null,
    @Doc("For a list source: what to show for apps in the list that are not installed. Default hide.")
    val missing: Missing = Missing.Hide,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("watch_next_row")
@Doc("A horizontally scrolling row of what the TV's apps offer to continue watching (Android TV's Watch Next), like the \"Continue watching\" row of Google TV: poster, title and a progress bar. OK resumes the entry in its app. The TV asks once for permission to read the entries; a row with nothing to show is hidden. Default width fill.")
data class WatchNextRowNode(
    override val id: String,
    @Doc("Title above the row.")
    val title: String? = null,
    @Doc("Icon before the title: a shortcut icon name such as tv or play, or an emoji.")
    val titleIcon: String? = null,
    @Doc("Tile look. Default banner, medium, label on focus. The entry's title is the label; {focused} and {description} (app and time left) name the focused entry.")
    val tile: TileStyle = TileStyle(),
    @Range(0.0, 200.0) @Doc("Gap between tiles in dp. Default 16.")
    val spacing: Int = 16,
    @Range(0.0, 500.0) @Doc("Leave out the first N entries. Default 0.")
    val skip: Int = 0,
    @Range(1.0, 500.0) @Doc("Maximum number of tiles.")
    val limit: Int? = null,
    @Doc("Package names of apps whose entries are left out.")
    val exclude: List<String> = emptyList(),
    @Doc("Draw a progress bar along the bottom of tiles that report how far they are watched. Default true.")
    val showProgress: Boolean = true,
    @Doc("Where the focused tile is scrolled to: nearest, start (focused tile stays at the left) or center. Default nearest.")
    val scrollAlign: ScrollAlign = ScrollAlign.Nearest,
    @Range(0.0, 400.0) @Doc("Distance in dp of the focused tile from the row's left edge when scrollAlign is start. Default 0 (in line with the title).")
    val scrollMargin: Int = 0,
    @Doc("How the row scrolls to the focused tile. Default: the theme's scrollMotion.")
    val scrollMotion: ScrollMotion? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("folder")
@Doc("A tile showing a few app icons that opens a panel with a group of apps, like Apple TV folders.")
data class FolderNode(
    override val id: String,
    @Doc("Folder name, shown on and under the tile and as the panel title.")
    val label: String,
    @Doc("Which apps are in the folder.")
    val source: AppSource,
    @Doc("Look of the folder tile. Default banner, medium.")
    val tile: TileStyle = TileStyle(),
    @Doc("Look of the app tiles in the open panel. Default banner, medium.")
    val itemTile: TileStyle = TileStyle(),
    @Range(1.0, 12.0) @Doc("Columns in the open panel. Default 4.")
    val columns: Int = 4,
    @ColorValue @Doc("Folder tile color; a translucent color gives a glass look. Default @surface.")
    val color: String? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("shortcut")
@Doc("A focusable tile with a custom label, icon or image that runs an action.")
data class ShortcutNode(
    override val id: String,
    @Doc("Text on the tile.")
    val label: String,
    @Doc("What happens on click.")
    val action: Action,
    @Doc("Icon: one of settings, home, search, star, info, info_outline, play, favorite, apps, add, add_circle, refresh, notifications, person, lock, menu, tv, live_tv, input, hdmi, smartphone, display, system, accessibility, android, wallpaper, wifi, wifi_off, ethernet, or any emoji such as \"🎬\".")
    val icon: String? = null,
    @Doc("Image instead of an icon: an https:// URL or \"asset:<path>\".")
    val image: String? = null,
    @ColorValue @Doc("Tile color. Default @surface.")
    val color: String? = null,
    @ColorValue @Doc("Color of the icon and the label on the tile. Default @text.")
    val iconColor: String? = null,
    @ColorValue @Doc("Tile color while focused, e.g. a dark tile that is light when idle. Default: color.")
    val focusColor: String? = null,
    @ColorValue @Doc("Icon and label color while focused. Default: iconColor.")
    val focusIconColor: String? = null,
    @Doc("Tile look. Default card, medium.")
    val tile: TileStyle = TileStyle(shape = TileShape.Card),
    @Doc("Text a hero shows while this shortcut is focused.")
    val description: String? = null,
    @Doc("Image a hero shows while this shortcut is focused: an https:// URL or \"asset:<path>\".")
    val backdrop: String? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("hero")
@Doc("A large banner area. With follow \"focus\" it shows the focused app (Apple TV top shelf); with \"slides\" it rotates through slides (Google TV / Fire TV carousel). Default width fill, height 280.")
data class HeroNode(
    override val id: String,
    @Doc("focus = show the focused app or shortcut (backdrop and description come from the apps map or the shortcut); slides = rotate through slides. Default focus.")
    val follow: HeroFollow = HeroFollow.Focus,
    @Doc("With follow focus: only react to focus inside the node with this id (e.g. the dock row). Default: the whole page.")
    val target: String? = null,
    @Doc("Slides. With follow focus they are shown until something is focused.")
    val slides: List<HeroSlide> = emptyList(),
    @Range(2.0, 120.0) @Doc("Seconds per slide. Default 8.")
    val interval: Int = 8,
    @Doc("Show title and description text. Default true.")
    val showText: Boolean = true,
    @Range(0.0, 1.0) @Doc("Darkness of the gradient behind the text, 0 to 1. Default 0.6.")
    val scrim: Float = 0.6f,
    @Doc("Where the text block sits. Default bottom_start.")
    val contentAlign: Alignment = Alignment.BottomStart,
    @Range(0.0, 1.0) @Doc("Share of the height at the bottom that fades out into what is behind the hero, 0 to 1. Default 0.")
    val fade: Float = 0f,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@Doc("One slide of a hero.")
data class HeroSlide(
    @Doc("Large text.")
    val title: String? = null,
    @Doc("Smaller text under the title.")
    val description: String? = null,
    @Doc("Slide background: a color, gradient or image.")
    val background: Background? = null,
    @Doc("Makes the hero clickable while this slide shows.")
    val action: Action? = null,
)

@Serializable
enum class HeroFollow {
    @SerialName("focus") Focus,
    @SerialName("slides") Slides,
}

// ---------------------------------------------------------------- content widgets

@Serializable
@SerialName("text")
@Doc("Text, not focusable. May contain placeholders: {greeting}, {time}, {date}, {date:EEEE d MMMM}, {focused}, {description}, {page}. Write {{ for a literal {.")
data class TextNode(
    override val id: String,
    @Doc("The text, optionally with placeholders.")
    val text: String,
    @Doc("Size preset. Default body.")
    val textStyle: TextStyleName = TextStyleName.Body,
    @Range(6.0, 200.0) @Doc("Font size in sp; overrides the preset size.")
    val fontSize: Int? = null,
    @Doc("Font family. Default: the theme's font.")
    val font: FontName? = null,
    @ColorValue @Doc("Text color. Default @text.")
    val color: String? = null,
    @Doc("Bold text. Default false.")
    val bold: Boolean = false,
    @Doc("Show the text in capital letters. Default false.")
    val uppercase: Boolean = false,
    @Range(-2.0, 20.0) @Doc("Extra space between letters in sp.")
    val letterSpacing: Float? = null,
    @Doc("Alignment of the text inside its box. Default start.")
    val textAlign: TextAlignment = TextAlignment.Start,
    @Range(1.0, 20.0) @Doc("Maximum number of lines; longer text ends with an ellipsis.")
    val maxLines: Int? = null,
    @Doc("Icon before the text, in the text's size and color: a shortcut icon name such as tv or input, or an emoji.")
    val icon: String? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("clock")
@Doc("Current time and/or date. Not focusable.")
data class ClockNode(
    override val id: String,
    @Doc("java.time pattern, e.g. \"HH:mm\" (24h), \"h:mm a\" (12h) or \"EEEE, d MMMM\" (date). Default HH:mm.")
    val format: String = "HH:mm",
    @Doc("Size preset. Default display.")
    val textStyle: TextStyleName = TextStyleName.Display,
    @Range(6.0, 200.0) @Doc("Font size in sp; overrides the preset size.")
    val fontSize: Int? = null,
    @Doc("Font family. Default: the theme's font.")
    val font: FontName? = null,
    @ColorValue @Doc("Text color. Default @text.")
    val color: String? = null,
    @Doc("Bold text. Default false.")
    val bold: Boolean = false,
    @Range(-2.0, 20.0) @Doc("Extra space between letters in sp.")
    val letterSpacing: Float? = null,
    @Doc("Alignment of the text inside its box. Default start.")
    val textAlign: TextAlignment = TextAlignment.Start,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("network")
@Doc("Connection indicator: a Wi-Fi, Ethernet or offline icon that follows the TV's network. Not focusable.")
data class NetworkNode(
    override val id: String,
    @Range(12.0, 96.0) @Doc("Icon size in dp. Default 24.")
    val size: Int = 24,
    @ColorValue @Doc("Icon color. Default @text.")
    val color: String? = null,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

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
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

@Serializable
@SerialName("spacer")
@Doc("Empty space. Use width/height for a fixed gap or weight to push siblings apart.")
data class SpacerNode(
    override val id: String,
    @Transient override val common: NodeCommon = NodeCommon(),
) : Node {
    override fun withCommon(common: NodeCommon) = copy(common = common)
}

/** A node whose `type` this version does not know. It is kept as-is and rendered as a placeholder. */
data class UnknownNode(val type: String, val raw: JsonObject) : Node {
    override val id: String = (raw["id"] as? JsonPrimitive)?.contentOrNull ?: ""
    override val common: NodeCommon get() = NodeCommon()
    override fun withCommon(common: NodeCommon) = this
}

// ---------------------------------------------------------------- shared value types

@Serializable
@Doc("Look of app, folder and shortcut tiles.")
data class TileStyle(
    @Doc("banner = 16:9 app banner; icon = square app icon with the label below; card = banner with the label always below. Default banner.")
    val shape: TileShape = TileShape.Banner,
    @Doc("small, medium or large. Banner widths: 128, 160, 208 dp. Icon sizes: 56, 72, 96 dp. Default medium.")
    val size: TileSize = TileSize.Medium,
    @Doc("When to show the app name: always, focused or never. Default focused for banners, always for icon and card.")
    val showLabel: LabelMode? = null,
    @Range(32.0, 600.0) @Doc("Exact tile width in dp; overrides size.")
    val width: Int? = null,
    @Range(0.3, 4.0) @Doc("Width divided by height, e.g. 1.78 (16:9), 1.67 (Apple TV 5:3), 1 (square), 0.67 (poster). Default 16:9, or 1 for icons.")
    val aspectRatio: Float? = null,
    @Range(0.0, 200.0) @Doc("Corner radius in dp; overrides the theme's cornerRadius.")
    val cornerRadius: Int? = null,
    @Doc("Thin light edge around the tile, like tvOS Liquid Glass icons. Default false.")
    val rim: Boolean = false,
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
enum class FontName {
    @SerialName("sans") Sans,
    @SerialName("serif") Serif,
    @SerialName("mono") Mono,
    @SerialName("cursive") Cursive,
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
enum class ScrollAlign {
    @SerialName("nearest") Nearest,
    @SerialName("start") Start,
    @SerialName("center") Center,
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
