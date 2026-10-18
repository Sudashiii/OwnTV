package dev.owntv.layout.validation

import dev.owntv.layout.model.Action
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.CategorySource
import dev.owntv.layout.model.ClockNode
import dev.owntv.layout.model.ColorBackground
import dev.owntv.layout.model.Colors
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.GridNode
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.ImageNode
import dev.owntv.layout.model.IntentAction
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.OpenUrlAction
import dev.owntv.layout.model.PackagesSource
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.StackNode
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.ThemeColors
import dev.owntv.layout.model.UnknownNode
import dev.owntv.layout.model.containsFocusable
import dev.owntv.layout.model.findNode
import dev.owntv.layout.model.walkNodes
import java.time.format.DateTimeFormatter

/** Checks the meaning of a decoded layout: references, ids, colors, and things that make a TV unusable. */
object LayoutValidator {
    const val MAX_DEPTH = 24
    private val idPattern = Regex("^[A-Za-z0-9_.-]+$")

    /** @param installedPackages when given, packages that are not installed produce warnings. */
    fun validate(doc: LayoutDocument, installedPackages: Set<String>? = null): List<ValidationIssue> {
        val out = IssueCollector()
        Checker(doc, installedPackages, out).run()
        return out.issues
    }

    private class Checker(
        val doc: LayoutDocument,
        val installed: Set<String>?,
        val out: IssueCollector,
    ) {
        val colors: ThemeColors = doc.theme.colors
        val pageIds = doc.pages.map { it.id }.toSet()

        fun run() {
            checkTheme()
            doc.background?.let { checkBackground(it, "$.background") }
            if (doc.pages.isEmpty()) out.error("$.pages", "Add at least one page.")

            val seenPages = mutableSetOf<String>()
            doc.pages.forEachIndexed { i, page ->
                val path = "$.pages[$i]"
                checkId(page.id, "$path.id")
                if (!seenPages.add(page.id)) out.error("$path.id", "Duplicate page id \"${page.id}\".")
                page.background?.let { checkBackground(it, "$path.background") }
                page.initialFocus?.let { id ->
                    val target = page.findNode(id)
                    when {
                        target == null -> out.error("$path.initialFocus", "No node with id \"$id\" on page \"${page.id}\".")
                        !target.containsFocusable() -> out.warning("$path.initialFocus", "Node \"$id\" has nothing focusable.")
                    }
                }
                if (!page.root.containsFocusable()) {
                    out.warning(
                        "$path.root",
                        "Page \"${page.id}\" has no focusable widget (app_tile, app_row, app_grid or shortcut), so the remote cannot do anything on it.",
                    )
                }
            }

            val seenIds = mutableMapOf<String, String>()
            doc.walkNodes { _, node, path, depth ->
                if (depth == MAX_DEPTH) out.error(path, "Nesting is deeper than $MAX_DEPTH levels.")
                if (node !is UnknownNode) {
                    checkId(node.id, "$path.id")
                    seenIds.put(node.id, path)?.let { first ->
                        out.error("$path.id", "Duplicate id \"${node.id}\" (first used at $first).")
                    }
                }
                node.background?.let { checkColorRef(it, "$path.background") }
                checkNode(node, path)
            }
        }

        fun checkTheme() {
            val base = "$.theme.colors"
            listOf(
                "background" to colors.background, "surface" to colors.surface, "primary" to colors.primary,
                "text" to colors.text, "textMuted" to colors.textMuted, "focus" to colors.focus,
            ).forEach { (name, value) ->
                if (!Colors.isHex(value)) out.error("$base.$name", "Theme colors must be hex values like #1A2B3C, not \"$value\".")
            }
            colors.custom.forEach { (name, value) ->
                if (!Regex("^[A-Za-z][A-Za-z0-9_]*$").matches(name)) out.error("$base.custom.$name", "Token names use letters, digits and _.")
                if (!Colors.isHex(value)) out.error("$base.custom.$name", "Custom colors must be hex values like #1A2B3C, not \"$value\".")
            }
            checkColorRef(doc.theme.focus.borderColor, "$.theme.focus.borderColor")
        }

        fun checkNode(node: Node, path: String) {
            when (node) {
                is ColumnNode, is RowNode, is StackNode -> Unit
                is GridNode -> node.children.forEachIndexed { i, child ->
                    val span = child.span ?: 1
                    if (span > node.columns) {
                        out.error("$path.children[$i].span", "span $span is larger than the grid's ${node.columns} columns.")
                    }
                }
                is AppTileNode -> checkPackage(node.packageName, "$path.package")
                is AppRowNode -> checkSource(node.source, "$path.source")
                is AppGridNode -> checkSource(node.source, "$path.source")
                is ShortcutNode -> {
                    checkAction(node.action, "$path.action")
                    node.color?.let { checkColorRef(it, "$path.color") }
                    node.image?.let { checkImageSrc(it, "$path.image") }
                    if (node.icon != null && node.image != null) out.info(path, "Both icon and image are set; the image is used.")
                }
                is TextNode -> node.color?.let { checkColorRef(it, "$path.color") }
                is ClockNode -> {
                    node.color?.let { checkColorRef(it, "$path.color") }
                    runCatching { DateTimeFormatter.ofPattern(node.format) }.onFailure {
                        out.error("$path.format", "Invalid time pattern \"${node.format}\": ${it.message}")
                    }
                }
                is ImageNode -> checkImageSrc(node.src, "$path.src")
                else -> Unit
            }
        }

        fun checkSource(source: AppSource, path: String) {
            when (source) {
                is PackagesSource -> {
                    if (source.packages.isEmpty()) out.warning("$path.packages", "The package list is empty.")
                    source.packages.forEachIndexed { i, pkg -> checkPackage(pkg, "$path.packages[$i]") }
                }
                is AllAppsSource, is CategorySource -> Unit
            }
        }

        fun checkAction(action: Action, path: String) {
            when (action) {
                is LaunchAppAction -> checkPackage(action.packageName, "$path.package")
                is GoToPageAction -> if (action.page !in pageIds) {
                    out.error("$path.page", "No page with id \"${action.page}\". Pages: ${pageIds.joinToString(", ")}.")
                }
                is OpenUrlAction -> if (!action.url.contains(":")) out.error("$path.url", "\"${action.url}\" is not a URL.")
                is IntentAction -> if (action.action == null && action.packageName == null && action.component == null) {
                    out.error(path, "An intent needs at least one of action, package or component.")
                }
                else -> Unit
            }
        }

        fun checkBackground(background: Background, path: String) {
            when (background) {
                is ColorBackground -> checkColorRef(background.color, "$path.color")
                is GradientBackground -> {
                    if (background.colors.size < 2) out.error("$path.colors", "A gradient needs at least 2 colors.")
                    background.colors.forEachIndexed { i, c -> checkColorRef(c, "$path.colors[$i]") }
                }
                is ImageBackground -> checkImageSrc(background.src, "$path.src")
            }
        }

        fun checkColorRef(value: String, path: String) {
            if (value.startsWith("@") && colors.token(value.substring(1)) == null) {
                out.error(path, "Unknown color token \"$value\". Built-in tokens: @background, @surface, @primary, @text, @textMuted, @focus; add more in theme.colors.custom.")
            }
        }

        fun checkImageSrc(src: String, path: String) {
            val ok = src.startsWith("https://") || src.startsWith("http://") || src.startsWith("asset:")
            if (!ok) out.error(path, "Image sources must start with https://, http:// or asset:.")
        }

        fun checkId(id: String, path: String) {
            if (!idPattern.matches(id)) out.error(path, "Ids may only use letters, digits, _ . and - (got \"$id\").")
        }

        fun checkPackage(pkg: String, path: String) {
            if (installed != null && pkg !in installed) out.warning(path, "App \"$pkg\" is not installed on this TV.")
        }
    }
}
