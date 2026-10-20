package dev.owntv.layout.edit

import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.ClockNode
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.GridNode
import dev.owntv.layout.model.HeroFollow
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.HeroSlide
import dev.owntv.layout.model.ImageNode
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.NodeCommon
import dev.owntv.layout.model.NetworkNode
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.Size
import dev.owntv.layout.model.SpacerNode
import dev.owntv.layout.model.StackNode
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.UnknownNode

/** Ready-to-use nodes for the editor's "Add" palette. Every template is valid as it is. */
object NodeTemplates {
    /**
     * A new node of [type] with id [id]. [samplePackage] is used where a node needs one app
     * (an installed package, so the new tile shows something real).
     */
    fun create(type: String, id: String, samplePackage: String? = null): Node? = when (type) {
        "column" -> ColumnNode(id, spacing = 16)
        "row" -> RowNode(id, spacing = 16)
        "grid" -> GridNode(id, columns = 3)
        "stack" -> StackNode(id)
        "app_tile" -> AppTileNode(id, packageName = samplePackage ?: "com.google.android.youtube.tv")
        "app_row" -> AppRowNode(id, source = ListSource(id.toListName()), title = "Apps")
        "app_grid" -> AppGridNode(id, source = AllAppsSource(), title = "All apps")
        "folder" -> FolderNode(id, label = "Folder", source = ListSource(id.toListName()))
        "shortcut" -> ShortcutNode(id, label = "Shortcut", icon = "star", action = OpenAppDrawerAction)
        "hero" -> HeroNode(
            id,
            follow = HeroFollow.Slides,
            slides = listOf(HeroSlide("Title", "Description", GradientBackground(listOf("@primary", "@background"), 120))),
            common = NodeCommon(height = Size.Fixed(220), cornerRadius = 16),
        )
        "text" -> TextNode(id, text = "Text")
        "clock" -> ClockNode(id)
        "network" -> NetworkNode(id)
        "image" -> ImageNode(id, src = "https://", common = NodeCommon(width = Size.Fixed(160), height = Size.Fixed(90)))
        "spacer" -> SpacerNode(id, common = NodeCommon(width = Size.Fixed(24), height = Size.Fixed(24)))
        else -> null
    }

    /** [node] with a different id; everything else unchanged. */
    fun withId(node: Node, id: String): Node = when (node) {
        is ColumnNode -> node.copy(id = id)
        is RowNode -> node.copy(id = id)
        is GridNode -> node.copy(id = id)
        is StackNode -> node.copy(id = id)
        is AppTileNode -> node.copy(id = id)
        is AppRowNode -> node.copy(id = id)
        is AppGridNode -> node.copy(id = id)
        is FolderNode -> node.copy(id = id)
        is ShortcutNode -> node.copy(id = id)
        is HeroNode -> node.copy(id = id)
        is TextNode -> node.copy(id = id)
        is ClockNode -> node.copy(id = id)
        is NetworkNode -> node.copy(id = id)
        is ImageNode -> node.copy(id = id)
        is SpacerNode -> node.copy(id = id)
        is UnknownNode -> node
    }
}

/** A list name made from a node id: letters, digits, _ and - only. */
private fun String.toListName(): String = map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
