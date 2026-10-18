package dev.owntv.launcher.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import dev.owntv.launcher.data.apps.AppImageLoader
import dev.owntv.launcher.data.apps.AppInfo
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppCategory
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppOverride
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppSort
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.CategorySource
import dev.owntv.layout.model.ContainerNode
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.Missing
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.PackagesSource
import dev.owntv.layout.model.RecentSource
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.isFocusableWidget
import dev.owntv.layout.model.visible

/**
 * Installed apps as the renderer sees them: with the layout's per-app [overrides] and the
 * [recent] launch order (most recent first).
 */
@Stable
class AppCatalog(
    val apps: List<AppInfo>,
    val loaded: Boolean,
    val recent: List<String> = emptyList(),
    val overrides: Map<String, AppOverride> = emptyMap(),
) {
    private val byPackage = apps.associateBy { it.packageName }

    operator fun get(packageName: String): AppInfo? = byPackage[packageName]

    fun override(packageName: String): AppOverride? = overrides[packageName]

    fun labelOf(app: AppInfo): String = overrides[app.packageName]?.label ?: app.label

    fun withOverrides(overrides: Map<String, AppOverride>) =
        if (overrides == this.overrides) this else AppCatalog(apps, loaded, recent, overrides)

    private fun isHidden(app: AppInfo) = overrides[app.packageName]?.hidden == true

    /** Resolves a source to tiles. Missing apps become placeholders only when [missing] asks for it. */
    fun resolve(source: AppSource, missing: Missing, limit: Int?, skip: Int = 0): List<AppEntry> {
        val entries: List<AppEntry> = when (source) {
            is PackagesSource -> source.packages.distinct().mapNotNull { pkg ->
                this[pkg]?.let(AppEntry::Installed)
                    ?: if (missing == Missing.Placeholder && loaded) AppEntry.NotInstalled(pkg) else null
            }
            is AllAppsSource -> apps
                .filter { (it.isTvApp || source.includeNonTvApps) && it.packageName !in source.exclude }
                .filter { source.includeHidden || !isHidden(it) }
                .sortedWith(source.sort.comparator())
                .map(AppEntry::Installed)
            is CategorySource -> apps
                .filter { it.isTvApp && it.isGame == (source.category == AppCategory.Games) && it.packageName !in source.exclude }
                .filterNot(::isHidden)
                .sortedWith(source.sort.comparator())
                .map(AppEntry::Installed)
            is RecentSource -> recent
                .filter { it !in source.exclude }
                .mapNotNull { this[it] }
                .filterNot(::isHidden)
                .map(AppEntry::Installed)
        }
        val remaining = entries.drop(skip)
        return if (limit != null) remaining.take(limit) else remaining
    }

    private fun AppSort.comparator(): Comparator<AppInfo> = when (this) {
        AppSort.Alphabetical -> compareBy { labelOf(it).lowercase() }
        AppSort.RecentlyInstalled -> compareByDescending { it.firstInstallTime }
    }
}

sealed interface AppEntry {
    val packageName: String

    data class Installed(val app: AppInfo) : AppEntry {
        override val packageName: String get() = app.packageName
    }

    data class NotInstalled(override val packageName: String) : AppEntry
}

/** True when this node will draw at least one focusable tile with the apps that are installed now. */
fun Node.rendersFocusable(catalog: AppCatalog): Boolean = visible && when (this) {
    is ShortcutNode -> true
    is AppTileNode -> catalog[packageName] != null || (missing == Missing.Placeholder && catalog.loaded)
    is AppRowNode -> catalog.resolve(source, missing, limit, skip).isNotEmpty()
    is AppGridNode -> catalog.resolve(source, missing, limit, skip).isNotEmpty()
    is FolderNode -> catalog.resolve(source, Missing.Hide, null).isNotEmpty()
    is HeroNode -> isFocusableWidget
    is ContainerNode -> children.any { it.rendersFocusable(catalog) }
    else -> false
}

/** What a long press on a tile refers to. */
data class ContextTarget(val title: String, val app: AppInfo?, val packageName: String?, val nodeId: String? = null)

/** Receives clicks from rendered widgets. */
interface ActionHandler {
    fun perform(action: Action)
    fun openApp(app: AppInfo)
    fun openMissing(packageName: String)
    fun longPress(target: ContextTarget)

    object None : ActionHandler {
        override fun perform(action: Action) = Unit
        override fun openApp(app: AppInfo) = Unit
        override fun openMissing(packageName: String) = Unit
        override fun longPress(target: ContextTarget) = Unit
    }
}

/** Focus requesters for the nodes other nodes point at (initial focus, focus links, hero targets). */
class FocusRegistry(val targetIds: Set<String>) {
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun isTarget(id: String) = id in targetIds

    fun requester(id: String): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    /** Requests focus on [id]; false when that node is not on screen. */
    fun focus(id: String?): Boolean = id != null && isTarget(id) && runCatching { requester(id).requestFocus() }.isSuccess
}

/** The tile that has focus right now, for heroes and the {focused} placeholder. */
data class FocusedTile(
    val label: String,
    val app: AppInfo?,
    val description: String?,
    val backdrop: String?,
    /** Ids of the nodes this tile sits in, innermost last. */
    val ancestors: List<String>,
)

@Stable
class FocusTracker {
    var current by mutableStateOf<FocusedTile?>(null)
}

val LocalApps = staticCompositionLocalOf { AppCatalog(emptyList(), loaded = false) }
val LocalAppImages = staticCompositionLocalOf<AppImageLoader?> { null }
val LocalActionHandler = staticCompositionLocalOf<ActionHandler> { ActionHandler.None }
val LocalFocusRegistry = staticCompositionLocalOf<FocusRegistry?> { null }
val LocalFocusTracker = staticCompositionLocalOf<FocusTracker?> { null }

/** Ids of the nodes enclosing the current one, outermost first. */
val LocalAncestors = staticCompositionLocalOf { emptyList<String>() }

/** The node outlined in the editor's preview. */
val LocalEditorSelection = staticCompositionLocalOf<String?> { null }

/** Title of the page being rendered, for the {page} placeholder. */
val LocalPageTitle = staticCompositionLocalOf<String?> { null }

@Composable
fun rememberAppImage(app: AppInfo, kind: AppImageLoader.Kind): ImageBitmap? {
    val loader = LocalAppImages.current ?: return null
    val image by produceState(loader.cached(app, kind), app, kind) {
        if (value == null) value = loader.load(app, kind)
    }
    return image
}
