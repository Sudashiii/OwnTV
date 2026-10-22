package dev.owntv.launcher.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.layout.edit.LayoutOps
import dev.owntv.layout.edit.NodeTemplates
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.CategorySource
import dev.owntv.layout.model.ClockNode
import dev.owntv.layout.model.ContainerNode
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.ImageNode
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.NetworkNode
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.NodeCategory
import dev.owntv.layout.model.NodeRegistry
import dev.owntv.layout.model.RecentSource
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.WatchNextRowNode
import dev.owntv.layout.model.screensaverPage
import dev.owntv.layout.model.walk

@Composable
internal fun PanelHeader(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        subtitle ?: "Back returns to the previous panel.",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        maxLines = 2,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** One row of a panel. Rows are plain list items so the remote moves through them predictably. */
@Composable
internal fun PanelRow(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    )
}

/** Every node of the current page as an indented list. Moving through it selects nodes in the preview. */
@Composable
internal fun OutlinePanel(state: EditorState) {
    val doc = state.doc
    val page = state.page
    val rows = remember(page) {
        buildList { page.root.walk("") { node, _, depth -> add(node to depth) } }
    }
    val headerCount = if (doc.pages.size > 1) 3 else 2
    val selectedIndex = rows.indexOfFirst { it.first.id == state.selectedId }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val requester = remember { FocusRequester() }

    PanelHeader("Outline", "Select a part of the layout and press OK.")
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        item {
            PanelRow("Layout settings", "Name, theme, background, page tabs") {
                state.push(Panel.Form(FormTarget.DocumentTarget, emptyList(), "Layout settings", listOf(dev.owntv.layout.model.LayoutDocument.serializer().descriptor), skip = setOf("pages", "styles", "defaults", "apps", "schemaVersion")))
            }
        }
        item {
            PanelRow("Page settings", "Title, initial focus, background") {
                state.push(Panel.Form(FormTarget.PageTarget, emptyList(), "Page \"${page.title ?: page.id}\"", listOf(dev.owntv.layout.model.Page.serializer().descriptor), skip = setOf("root", "id")))
            }
        }
        if (doc.pages.size > 1) {
            item {
                val title = page.title ?: page.id
                val label = if (page === doc.screensaverPage && !title.contains("screensaver", ignoreCase = true)) "$title (screensaver)" else title
                PanelRow("Page ${state.pageIndex + 1} of ${doc.pages.size}: $label", "Press OK to edit the next page") {
                    state.showPage((state.pageIndex + 1) % doc.pages.size)
                }
            }
        }
        itemsIndexed(rows, key = { _, row -> row.first.id }) { index, (node, depth) ->
            PanelRow(
                title = "${NodeRegistry.forNode(node)?.type ?: "unknown"} · ${node.id}",
                detail = node.summary(),
                selected = node.id == state.selectedId,
                modifier = Modifier
                    .padding(start = (depth * 12).dp)
                    .then(if (index == selectedIndex) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged { if (it.isFocused) state.selectedId = node.id },
            ) { state.push(Panel.NodeMenu(node.id)) }
        }
    }
    LaunchedEffect(page.id) {
        listState.scrollToItem((selectedIndex + headerCount - 2).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** What can be done with one node. */
@Composable
internal fun NodeMenuPanel(state: EditorState, panel: Panel.NodeMenu) {
    val node = LayoutOps.node(state.doc, panel.id) ?: return
    val isRoot = LayoutOps.locate(state.doc, panel.id)?.isRoot == true
    val doc = state.doc
    val id = panel.id

    PanelHeader("${NodeRegistry.forNode(node)?.type ?: "node"} · $id", node.summary())
    val actions = buildList<Pair<String, () -> Unit>> {
        add("Edit properties" to { state.nodeForm(id)?.let(state::push) })
        if (node is ContainerNode) add("Add inside…" to { state.push(Panel.Palette(id, inside = true)) })
        if (!isRoot) {
            add("Add after…" to { state.push(Panel.Palette(id, inside = false)) })
            add("Move earlier" to { state.edit(LayoutOps.move(doc, id, -1), "It is already first.") })
            add("Move later" to { state.edit(LayoutOps.move(doc, id, 1), "It is already last.") })
            add("Move into the container before it" to {
                state.edit(LayoutOps.moveIntoPrevious(doc, id), "The node before it is not a column, row, grid or stack.")
            })
            add("Move out of its container" to { state.edit(LayoutOps.moveOut(doc, id), "It is already at the top level of the page.") })
            add("Duplicate" to {
                val copy = LayoutOps.duplicate(doc, id)
                if (state.edit(copy?.first)) {
                    state.selectedId = copy!!.second
                    state.pop()
                }
            })
            add("Delete" to {
                val parent = LayoutOps.parent(doc, id)
                if (state.edit(LayoutOps.remove(doc, id))) {
                    state.selectedId = parent?.id ?: state.page.root.id
                    state.pop()
                }
            })
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(actions) { index, (label, action) ->
            PanelRow(label, modifier = if (index == 0) dev.owntv.launcher.ui.rememberInitialFocus() else Modifier, onClick = action)
        }
    }
}

/** Node types to add, grouped like the registry. */
@Composable
internal fun PalettePanel(state: EditorState, panel: Panel.Palette, catalog: AppCatalog) {
    PanelHeader(if (panel.inside) "Add inside ${panel.anchorId}" else "Add after ${panel.anchorId}", "Pick what to add.")
    val groups = NodeCategory.entries.map { category -> category to NodeRegistry.all.filter { it.category == category } }
    Column {
        LazyColumn(Modifier.fillMaxWidth()) {
            var first = true
            groups.forEach { (category, types) ->
                item(key = "header_$category") {
                    Text(category.label(), style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(top = 8.dp, start = 8.dp))
                }
                types.forEach { type ->
                    val focusFirst = first
                    first = false
                    item(key = type.type) {
                        PanelRow(
                            type.type,
                            type.description,
                            modifier = if (focusFirst) dev.owntv.launcher.ui.rememberInitialFocus() else Modifier,
                        ) {
                            val doc = state.doc
                            val newId = LayoutOps.uniqueId(doc, type.type)
                            val samplePackage = catalog.apps.firstOrNull { it.isTvApp }?.packageName
                            val node = NodeTemplates.create(type.type, newId, samplePackage) ?: return@PanelRow
                            val next = if (panel.inside) LayoutOps.insert(doc, panel.anchorId, -1, node) else LayoutOps.insertAfter(doc, panel.anchorId, node)
                            if (state.edit(next)) {
                                state.selectedId = newId
                                state.panels.removeRange(1, state.panels.size)
                                state.push(Panel.NodeMenu(newId))
                                state.nodeForm(newId)?.let(state::push)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun NodeCategory.label() = when (this) {
    NodeCategory.Container -> "Containers"
    NodeCategory.Apps -> "Apps and shortcuts"
    NodeCategory.Content -> "Content"
}

/** A short description of a node for the outline. */
internal fun Node.summary(): String? = when (this) {
    is AppRowNode -> title ?: source.describe()
    is AppGridNode -> title ?: source.describe()
    is WatchNextRowNode -> title ?: "continue watching"
    is AppTileNode -> label ?: packageName
    is FolderNode -> label
    is ShortcutNode -> label
    is HeroNode -> if (slides.isNotEmpty()) "${slides.size} slide(s), follows ${follow.name.lowercase()}" else "follows ${follow.name.lowercase()}"
    is TextNode -> text
    is ClockNode -> format
    is NetworkNode -> "connection icon"
    is ImageNode -> src
    is ContainerNode -> "${children.size} item(s)"
    else -> null
}

private fun AppSource.describe(): String = when (this) {
    is ListSource -> "list $list"
    is AllAppsSource -> "all apps"
    is CategorySource -> "${category.name.lowercase()} only"
    is RecentSource -> "recently used"
}
