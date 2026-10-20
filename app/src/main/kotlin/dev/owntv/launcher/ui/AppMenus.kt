package dev.owntv.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.data.apps.AppListsRepository
import dev.owntv.launcher.ui.render.AppArranger
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.ContextTarget
import dev.owntv.launcher.ui.render.MovingApp
import dev.owntv.launcher.ui.render.rememberResolved
import dev.owntv.layout.apps.AppListOps
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppLists
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.ListRef
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.walkNodes

/**
 * Move mode for the live launcher: changes show at once and are saved on [drop]; [cancel]
 * restores the lists from when the move started.
 */
@Stable
class LauncherArranger(private val repository: AppListsRepository) : AppArranger {
    override var moving by mutableStateOf<MovingApp?>(null)
        private set
    private var before: AppLists? = null

    fun start(ref: ListRef, packageName: String) {
        if (moving == null) before = repository.lists.value
        moving = MovingApp(ref, packageName)
    }

    override fun step(order: List<String>, delta: Int) {
        val app = moving ?: return
        repository.showUnsaved(AppListOps.move(repository.lists.value, app.ref, order, app.packageName, delta))
    }

    override fun drop() {
        if (moving == null) return
        moving = null
        before = null
        repository.saveCurrent()
    }

    override fun cancel() {
        before?.let(repository::showUnsaved)
        moving = null
        before = null
    }
}

/** A list the layout shows, with the name people see (its row title or folder label). */
data class LayoutList(val name: String, val title: String)

/** The lists [doc] shows, in page order, each once. */
fun layoutLists(doc: LayoutDocument): List<LayoutList> {
    val found = linkedMapOf<String, String>()
    doc.walkNodes { _, node, _, _ ->
        val (source, title) = when (node) {
            is AppRowNode -> node.source to node.title
            is AppGridNode -> node.source to node.title
            is FolderNode -> node.source to node.label
            else -> return@walkNodes
        }
        val name = (source as? ListSource)?.list ?: return@walkNodes
        if (name !in found || found[name] == name) found[name] = title?.takeIf { it.isNotBlank() } ?: name
    }
    return found.map { (name, title) -> LayoutList(name, title) }
}

/**
 * The long-press menu for tiles, with Move, Add to…, Remove and Hide for apps.
 * [onEditLayout] is null where the layout editor makes no sense (the app drawer).
 */
@Composable
fun TileMenu(
    target: ContextTarget,
    container: AppContainer,
    nav: Navigator,
    doc: LayoutDocument,
    arranger: LauncherArranger,
    onDismiss: () -> Unit,
    onEditLayout: (() -> Unit)?,
) {
    val lists by container.appLists.lists.collectAsStateWithLifecycle()
    val resolved = rememberResolved(doc)
    var addTo by remember(target) { mutableStateOf(false) }
    val pkg = target.packageName
    val listName = (target.list?.ref as? ListRef.Named)?.name
    val listTitle = target.list?.title?.takeIf { it.isNotBlank() } ?: listName

    if (addTo && pkg != null) {
        val choices = layoutLists(resolved)
        ContextMenu(
            title = "Add ${target.title} to",
            items = if (choices.isEmpty()) {
                listOf("This layout shows no app lists. Add a row in Edit layout." to {})
            } else {
                choices.map { list ->
                    val inList = pkg in lists.list(list.name)
                    (if (inList) "✓ ${list.title}" else list.title) to {
                        container.appLists.update { AppListOps.toggle(it, list.name, pkg) }
                        container.actions.toast(if (inList) "Removed from ${list.title}" else "Added to ${list.title}")
                    }
                }
            },
            onDismiss = onDismiss,
        )
        return
    }

    val items = buildList<Pair<String, () -> Unit>> {
        if (target.app != null) add("Open" to { container.actions.launchApp(target.app!!.packageName) })
        if (pkg != null) {
            target.list?.let { list -> add("Move" to { arranger.start(list.ref, pkg) }) }
            add("Add to…" to { addTo = true })
            if (listName != null) {
                add("Remove from $listTitle" to { container.appLists.update { AppListOps.remove(it, listName, pkg) } })
            }
            if (lists.isHidden(pkg)) {
                add("Unhide" to { container.appLists.update { AppListOps.unhide(it, pkg) } })
            } else {
                add("Hide" to {
                    container.appLists.update { AppListOps.hide(it, pkg) }
                    container.actions.toast("Hidden. Settings > Hidden apps brings it back.")
                })
            }
            add("App info" to { container.actions.openAppInfo(pkg) })
        }
        if (onEditLayout != null) add("Edit layout" to onEditLayout)
        add("Launcher settings" to { nav.push(Screen.Settings) })
        add("Cancel" to {})
    }
    // Add to… swaps this menu for the list picker instead of closing.
    ContextMenu(target.title, items, onDismiss = { if (!addTo) onDismiss() })
}

/** Picks the apps of one list: OK adds or removes an app. */
@Composable
fun ListEditorScreen(container: AppContainer, name: String, title: String?, catalog: AppCatalog) {
    val lists by container.appLists.lists.collectAsStateWithLifecycle()
    val members = lists.list(name)
    val apps = remember(catalog.apps) { catalog.apps.sortedWith(compareBy({ !it.isTvApp }, { catalog.labelOf(it).lowercase() })) }
    SettingsScaffold(title ?: name, "${members.size} app(s). Select apps to add or remove them. New apps go to the end; long-press a tile on the home screen to move it.") {
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                val inList = app.packageName in members
                ListItem(
                    selected = false,
                    onClick = { container.appLists.update { AppListOps.toggle(it, name, app.packageName) } },
                    modifier = if (index == 0) rememberInitialFocus() else Modifier,
                    headlineContent = { Text(catalog.labelOf(app)) },
                    supportingContent = listOfNotNull(
                        "Phone app".takeIf { !app.isTvApp },
                        "Hidden".takeIf { lists.isHidden(app.packageName) },
                    ).takeIf { it.isNotEmpty() }?.let { notes -> { Text(notes.joinToString(" · ")) } },
                    trailingContent = { Text(if (inList) "✓" else "") },
                )
            }
        }
    }
}

/** Every hidden app, with a way back. */
@Composable
fun HiddenAppsScreen(container: AppContainer, catalog: AppCatalog) {
    val lists by container.appLists.lists.collectAsStateWithLifecycle()
    val hidden = lists.hidden.sortedBy { (catalog[it]?.let(catalog::labelOf) ?: it).lowercase() }
    SettingsScaffold("Hidden apps", "Hidden apps stay out of every row, grid and folder. The app drawer still shows them.") {
        if (hidden.isEmpty()) {
            Text("No hidden apps. Long-press an app and choose Hide.", color = Color(0xFFA9AFBA))
            return@SettingsScaffold
        }
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(hidden, key = { _, pkg -> pkg }) { index, pkg ->
                    val app = catalog[pkg]
                    SettingsItem(
                        app?.let(catalog::labelOf) ?: pkg,
                        if (app == null) "$pkg · not installed · select to unhide" else "Select to unhide",
                        modifier = if (index == 0) rememberInitialFocus() else Modifier,
                    ) { container.appLists.update { AppListOps.unhide(it, pkg) } }
                }
            }
        }
    }
}
