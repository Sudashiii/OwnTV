package dev.owntv.layout

import dev.owntv.layout.apps.AppListOps
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppLists
import dev.owntv.layout.model.AppSort
import dev.owntv.layout.model.ListRef
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.RecentSource
import dev.owntv.layout.model.listRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppListOpsTest {
    private val favorites = ListRef.Named("favorites")
    private val lists = AppLists(lists = mapOf("favorites" to listOf("a", "b", "c", "gone")))

    @Test
    fun `move shifts an app and keeps apps that are not shown at the end`() {
        val shown = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a", "gone"), AppListOps.move(lists, favorites, shown, "a", 2).list("favorites"))
        assertEquals(listOf("c", "a", "b", "gone"), AppListOps.move(lists, favorites, shown, "c", -5).list("favorites"))
        assertSame(lists, AppListOps.move(lists, favorites, shown, "a", -1))
        assertSame(lists, AppListOps.move(lists, favorites, shown, "x", 1))
    }

    @Test
    fun `moving in all apps stores the shown order`() {
        val moved = AppListOps.move(AppLists(), ListRef.AllApps, listOf("x", "y", "z"), "z", -1)
        assertEquals(listOf("x", "z", "y"), moved.allAppsOrder)
    }

    @Test
    fun `add, remove and toggle`() {
        assertSame(lists, AppListOps.add(lists, "favorites", "b"))
        assertEquals(listOf("d"), AppListOps.add(lists, "new", "d").list("new"))
        assertEquals(listOf("a", "c", "gone"), AppListOps.remove(lists, "favorites", "b").list("favorites"))
        assertSame(lists, AppListOps.remove(lists, "other", "b"))
        val toggled = AppListOps.toggle(lists, "favorites", "z")
        assertEquals(listOf("a", "b", "c", "gone", "z"), toggled.list("favorites"))
        assertEquals(lists.list("favorites"), AppListOps.toggle(toggled, "favorites", "z").list("favorites"))
    }

    @Test
    fun `hide and unhide`() {
        val hidden = AppListOps.hide(lists, "a")
        assertEquals(setOf("a"), hidden.hidden)
        assertSame(hidden, AppListOps.hide(hidden, "a"))
        assertEquals(emptySet(), AppListOps.unhide(hidden, "a").hidden)
    }

    @Test
    fun `seed fills only lists the TV does not have yet`() {
        val seeded = AppListOps.seed(
            lists,
            AppLists(lists = mapOf("favorites" to listOf("x"), "games" to listOf("g")), allAppsOrder = listOf("q"), hidden = setOf("h")),
        )
        assertEquals(lists.list("favorites"), seeded.list("favorites"))
        assertEquals(listOf("g"), seeded.list("games"))
        assertEquals(listOf("q"), seeded.allAppsOrder)
        assertEquals(setOf("h"), seeded.hidden)
        assertSame(seeded, AppListOps.seed(seeded, AppLists(lists = mapOf("games" to listOf("other")))))
    }

    @Test
    fun `sources know their stored order`() {
        assertEquals(favorites, ListSource("favorites").listRef())
        assertEquals(ListRef.AllApps, AllAppsSource().listRef())
        assertNull(AllAppsSource(sort = AppSort.Alphabetical).listRef())
        assertNull(RecentSource().listRef())
    }
}
