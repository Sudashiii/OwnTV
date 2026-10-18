package dev.owntv.launcher.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import dev.owntv.launcher.data.apps.AppImageLoader
import dev.owntv.launcher.data.apps.AppInfo
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.ContainerNode
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppCategory
import dev.owntv.layout.model.AppSort
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.CategorySource
import dev.owntv.layout.model.Missing
import dev.owntv.layout.model.PackagesSource

/** Installed apps as the renderer sees them. */
@Stable
class AppCatalog(val apps: List<AppInfo>, val loaded: Boolean) {
    private val byPackage = apps.associateBy { it.packageName }

    operator fun get(packageName: String): AppInfo? = byPackage[packageName]

    /** Resolves a source to tiles. Missing apps become placeholders only when [missing] asks for it. */
    fun resolve(source: AppSource, missing: Missing, limit: Int?): List<AppEntry> {
        val entries: List<AppEntry> = when (source) {
            is PackagesSource -> source.packages.distinct().mapNotNull { pkg ->
                this[pkg]?.let(AppEntry::Installed)
                    ?: if (missing == Missing.Placeholder && loaded) AppEntry.NotInstalled(pkg) else null
            }
            is AllAppsSource -> apps
                .filter { (it.isTvApp || source.includeNonTvApps) && it.packageName !in source.exclude }
                .sortedWith(source.sort.comparator())
                .map(AppEntry::Installed)
            is CategorySource -> apps
                .filter { it.isTvApp && it.isGame == (source.category == AppCategory.Games) && it.packageName !in source.exclude }
                .sortedWith(source.sort.comparator())
                .map(AppEntry::Installed)
        }
        return if (limit != null) entries.take(limit) else entries
    }

    private fun AppSort.comparator(): Comparator<AppInfo> = when (this) {
        AppSort.Alphabetical -> compareBy { it.label.lowercase() }
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
    is AppRowNode -> catalog.resolve(source, missing, limit).isNotEmpty()
    is AppGridNode -> catalog.resolve(source, missing, limit).isNotEmpty()
    is ContainerNode -> children.any { it.rendersFocusable(catalog) }
    else -> false
}

/** What a long press on a tile refers to. */
data class ContextTarget(val title: String, val app: AppInfo?, val packageName: String?)

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

/** The node that should receive focus when a page opens. */
class FocusTargets(val targetId: String?) {
    val requester = FocusRequester()

    fun tryFocus(): Boolean = targetId != null && runCatching { requester.requestFocus() }.isSuccess
}

val LocalApps = staticCompositionLocalOf { AppCatalog(emptyList(), loaded = false) }
val LocalAppImages = staticCompositionLocalOf<AppImageLoader?> { null }
val LocalActionHandler = staticCompositionLocalOf<ActionHandler> { ActionHandler.None }
val LocalFocusTargets = staticCompositionLocalOf<FocusTargets?> { null }

@Composable
fun rememberAppImage(app: AppInfo, kind: AppImageLoader.Kind): ImageBitmap? {
    val loader = LocalAppImages.current ?: return null
    val image by produceState(loader.cached(app, kind), app, kind) {
        if (value == null) value = loader.load(app, kind)
    }
    return image
}
