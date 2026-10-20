package dev.owntv.layout.model

import kotlinx.serialization.Serializable

/**
 * The apps the TV keeps apart from the layout: what is in each named list, the custom order of
 * all apps and which apps are hidden. Layouts only name lists ([ListSource]), so switching layouts
 * keeps these.
 */
@Serializable
data class AppLists(
    /** List name to package names, in the order they are shown. */
    val lists: Map<String, List<String>> = emptyMap(),
    /** Order for sources sorted `custom`. Apps not in it come after, alphabetically. */
    val allAppsOrder: List<String> = emptyList(),
    /** Apps left out of every source; the app drawer still shows them. */
    val hidden: Set<String> = emptySet(),
) {
    fun list(name: String): List<String> = lists[name].orEmpty()

    fun isHidden(packageName: String) = packageName in hidden

    val isEmpty: Boolean get() = lists.isEmpty() && allAppsOrder.isEmpty() && hidden.isEmpty()
}

/** Which stored order a row, grid or folder shows: a named list, or the custom order of all apps. */
sealed interface ListRef {
    data class Named(val name: String) : ListRef
    data object AllApps : ListRef
}

/** The stored order behind [source], or null when its order is computed (alphabetical, recent, ...). */
fun AppSource.listRef(): ListRef? = when (this) {
    is ListSource -> ListRef.Named(list)
    is AllAppsSource -> if (sort == AppSort.Custom) ListRef.AllApps else null
    is CategorySource -> if (sort == AppSort.Custom) ListRef.AllApps else null
    is RecentSource -> null
}
