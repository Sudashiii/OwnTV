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
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.GlassBackground
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.GridNode
import dev.owntv.layout.model.HeroFollow
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.ImageNode
import dev.owntv.layout.model.IntentAction
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.OpenUrlAction
import dev.owntv.layout.model.Page
import dev.owntv.layout.model.Placeholders
import dev.owntv.layout.model.RecentSource
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.StackNode
import dev.owntv.layout.model.StyleResolver
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.ThemeColors
import dev.owntv.layout.model.UnknownNode
import dev.owntv.layout.model.containsFocusable
import dev.owntv.layout.model.findNode
import dev.owntv.layout.model.walk
import dev.owntv.layout.model.walkNodes
import dev.owntv.layout.time.Clocks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Checks the meaning of a decoded layout: references, ids, colors, and things that make a TV unusable. */
object LayoutValidator {
    const val MAX_DEPTH = 24
    private val idPattern = Regex("^[A-Za-z0-9_.-]+$")
    private val listNamePattern = Regex("^[A-Za-z0-9_-]+$")
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** @param installedPackages when given, packages that are not installed produce warnings. */
    fun validate(doc: LayoutDocument, installedPackages: Set<String>? = null): List<ValidationIssue> {
        val out = IssueCollector()
        checkStyleReferences(doc, out)
        val resolved = runCatching { StyleResolver.resolve(doc) }.getOrElse {
            out.error("$.styles", "Applying styles failed: ${it.message}")
            doc
        }
        Checker(resolved, installedPackages, out).run()
        return out.issues
    }

    private fun checkStyleReferences(doc: LayoutDocument, out: IssueCollector) {
        doc.styles.forEach { (name, style) ->
            val parent = (style["style"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (parent !in doc.styles) out.error("$.styles.$name.style", "No style named \"$parent\".")
            if (hasCycle(name, doc.styles)) out.error("$.styles.$name.style", "Style \"$name\" builds on itself.")
        }
        doc.walkNodes { _, node, path, _ ->
            val style = node.common.style ?: return@walkNodes
            if (style !in doc.styles) {
                val known = doc.styles.keys.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none defined"
                out.error("$path.style", "No style named \"$style\" (styles: $known).")
            }
        }
    }

    private fun hasCycle(start: String, styles: Map<String, JsonObject>): Boolean {
        val seen = mutableSetOf<String>()
        var current: String? = start
        while (current != null) {
            if (!seen.add(current)) return true
            current = (styles[current]?.get("style") as? JsonPrimitive)?.contentOrNull
        }
        return false
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
            checkApps()
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
                        "Page \"${page.id}\" has no focusable widget (app_tile, app_row, app_grid, folder or shortcut), so the remote cannot do anything on it.",
                    )
                }
                page.root.walk("$path.root") { node, nodePath, _ -> checkReferences(page, node, nodePath) }
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
                node.common.background?.let { checkBackground(it, "$path.background") }
                node.common.border?.let { checkColorRef(it.color, "$path.border.color") }
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
            doc.theme.focus.glowColor?.let { checkColorRef(it, "$.theme.focus.glowColor") }
        }

        fun checkApps() {
            doc.apps.forEach { (pkg, app) ->
                val path = "$.apps.$pkg"
                if (!packagePattern.matches(pkg)) out.warning(path, "\"$pkg\" does not look like a package name such as com.netflix.ninja.")
                app.banner?.let { checkImageSrc(it, "$path.banner") }
                app.icon?.let { checkImageSrc(it, "$path.icon") }
                app.backdrop?.let { checkImageSrc(it, "$path.backdrop") }
                app.color?.let { checkColorRef(it, "$path.color") }
            }
        }

        /** Ids a node points at must exist on the same page. */
        fun checkReferences(page: Page, node: Node, path: String) {
            node.common.focus?.let { links ->
                listOf("up" to links.up, "down" to links.down, "left" to links.left, "right" to links.right).forEach { (dir, id) ->
                    if (id != null && page.findNode(id) == null) out.error("$path.focus.$dir", "No node with id \"$id\" on page \"${page.id}\".")
                }
            }
            if (node is HeroNode) {
                node.target?.let { id ->
                    if (page.findNode(id) == null) out.error("$path.target", "No node with id \"$id\" on page \"${page.id}\".")
                }
            }
        }

        fun checkNode(node: Node, path: String) {
            when (node) {
                is ColumnNode, is RowNode, is StackNode -> Unit
                is GridNode -> node.children.forEachIndexed { i, child ->
                    val span = child.common.span ?: 1
                    if (span > node.columns) {
                        out.error("$path.children[$i].span", "span $span is larger than the grid's ${node.columns} columns.")
                    }
                }
                is AppTileNode -> checkPackage(node.packageName, "$path.package")
                is AppRowNode -> checkSource(node.source, "$path.source")
                is AppGridNode -> checkSource(node.source, "$path.source")
                is FolderNode -> {
                    checkSource(node.source, "$path.source")
                    node.color?.let { checkColorRef(it, "$path.color") }
                }
                is ShortcutNode -> {
                    checkAction(node.action, "$path.action")
                    node.color?.let { checkColorRef(it, "$path.color") }
                    node.image?.let { checkImageSrc(it, "$path.image") }
                    node.backdrop?.let { checkImageSrc(it, "$path.backdrop") }
                    if (node.icon != null && node.image != null) out.info(path, "Both icon and image are set; the image is used.")
                }
                is HeroNode -> {
                    if (node.follow == HeroFollow.Slides && node.slides.isEmpty()) {
                        out.warning("$path.slides", "A hero with follow \"slides\" needs at least one slide.")
                    }
                    node.slides.forEachIndexed { i, slide ->
                        slide.background?.let { checkBackground(it, "$path.slides[$i].background") }
                        slide.action?.let { checkAction(it, "$path.slides[$i].action") }
                        slide.title?.let { checkPlaceholders(it, "$path.slides[$i].title") }
                        slide.description?.let { checkPlaceholders(it, "$path.slides[$i].description") }
                    }
                }
                is TextNode -> {
                    node.color?.let { checkColorRef(it, "$path.color") }
                    checkPlaceholders(node.text, "$path.text")
                }
                is ClockNode -> {
                    node.color?.let { checkColorRef(it, "$path.color") }
                    checkPattern(node.format, "$path.format")
                }
                is ImageNode -> checkImageSrc(node.src, "$path.src")
                else -> Unit
            }
        }

        fun checkPlaceholders(text: String, path: String) {
            Placeholders.tokens(text).forEach { token ->
                when {
                    token.name !in Placeholders.names ->
                        out.warning(path, "Unknown placeholder {${token.name}}; it is shown as written. Known: ${Placeholders.names.joinToString(", ") { "{$it}" }}. Write {{ for a literal {.")
                    token.name == "date" || token.name == "time" -> token.argument?.let { checkPattern(it, path) }
                }
            }
        }

        fun checkPattern(pattern: String, path: String) {
            Clocks.patternError(pattern)?.let { out.error(path, "Invalid time pattern \"$pattern\": $it") }
        }

        fun checkSource(source: AppSource, path: String) {
            when (source) {
                is ListSource -> if (!listNamePattern.matches(source.list)) {
                    out.error("$path.list", "\"${source.list}\" is not a list name. Use letters, digits, _ and -, e.g. \"favorites\".")
                }
                is AllAppsSource, is CategorySource, is RecentSource -> Unit
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
                is ColorBackground -> checkColorRef(background.color, path)
                is GradientBackground -> {
                    if (background.colors.size < 2) out.error("$path.colors", "A gradient needs at least 2 colors.")
                    background.colors.forEachIndexed { i, c -> checkColorRef(c, "$path.colors[$i]") }
                }
                is ImageBackground -> checkImageSrc(background.src, "$path.src")
                is GlassBackground -> checkColorRef(background.tint, "$path.tint")
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
