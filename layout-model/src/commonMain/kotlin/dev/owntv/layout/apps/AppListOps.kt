package dev.owntv.layout.apps

import dev.owntv.layout.model.AppLists
import dev.owntv.layout.model.ListRef

/** Pure edits of [AppLists]. Each returns a new value, or the same one when nothing changes. */
object AppListOps {
    /**
     * Moves [packageName] by [delta] places within [shown], the order the row or grid shows now
     * (all of its apps, before skip and limit). Stored apps that are not shown (not installed,
     * hidden) keep their place after the shown ones.
     */
    fun move(lists: AppLists, ref: ListRef, shown: List<String>, packageName: String, delta: Int): AppLists {
        val from = shown.indexOf(packageName)
        if (from < 0 || delta == 0) return lists
        val to = (from + delta).coerceIn(0, shown.lastIndex)
        if (to == from) return lists
        val reordered = shown.toMutableList().apply { add(to, removeAt(from)) }
        return withOrder(lists, ref, reordered)
    }

    /** Stores [shown] as the order of [ref], keeping stored apps that are not shown at the end. */
    fun withOrder(lists: AppLists, ref: ListRef, shown: List<String>): AppLists {
        val stored = stored(lists, ref)
        val order = shown.distinct() + stored.filter { it !in shown }
        return when (ref) {
            is ListRef.Named -> lists.copy(lists = lists.lists + (ref.name to order))
            ListRef.AllApps -> lists.copy(allAppsOrder = order)
        }
    }

    fun stored(lists: AppLists, ref: ListRef): List<String> = when (ref) {
        is ListRef.Named -> lists.list(ref.name)
        ListRef.AllApps -> lists.allAppsOrder
    }

    /** Adds [packageName] at the end of list [name]; no change when it is already in it. */
    fun add(lists: AppLists, name: String, packageName: String): AppLists {
        val current = lists.list(name)
        if (packageName in current) return lists
        return lists.copy(lists = lists.lists + (name to current + packageName))
    }

    fun remove(lists: AppLists, name: String, packageName: String): AppLists {
        val current = lists.lists[name] ?: return lists
        if (packageName !in current) return lists
        return lists.copy(lists = lists.lists + (name to current - packageName))
    }

    fun toggle(lists: AppLists, name: String, packageName: String): AppLists =
        if (packageName in lists.list(name)) remove(lists, name, packageName) else add(lists, name, packageName)

    fun hide(lists: AppLists, packageName: String): AppLists =
        if (packageName in lists.hidden) lists else lists.copy(hidden = lists.hidden + packageName)

    fun unhide(lists: AppLists, packageName: String): AppLists =
        if (packageName !in lists.hidden) lists else lists.copy(hidden = lists.hidden - packageName)

    /**
     * Brings in lists taken from an older layout or a default set: only lists the TV does not
     * have yet are filled, and hidden apps are added. Nothing the user arranged is overwritten.
     */
    fun seed(lists: AppLists, from: AppLists): AppLists {
        val newLists = from.lists.filterKeys { it !in lists.lists }
        val order = lists.allAppsOrder.ifEmpty { from.allAppsOrder }
        if (newLists.isEmpty() && from.hidden.all { it in lists.hidden } && order == lists.allAppsOrder) return lists
        return lists.copy(lists = lists.lists + newLists, allAppsOrder = order, hidden = lists.hidden + from.hidden)
    }
}
