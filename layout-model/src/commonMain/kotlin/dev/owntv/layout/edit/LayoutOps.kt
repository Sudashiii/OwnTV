package dev.owntv.layout.edit

import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.ContainerNode
import dev.owntv.layout.model.GridNode
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.StackNode
import dev.owntv.layout.model.children
import dev.owntv.layout.model.walkNodes

/** Where a node sits: its page and the child indices from that page's root (empty for the root). */
data class NodeLocation(val pageIndex: Int, val path: List<Int>) {
    val isRoot: Boolean get() = path.isEmpty()
    val parentPath: List<Int> get() = path.dropLast(1)
    val index: Int get() = path.last()
}

/**
 * Pure tree edits on a layout, addressed by node id. Each returns a new document, or null when the
 * edit is not possible (unknown id, page root, no container to move into, ...).
 */
object LayoutOps {
    private val idPattern = Regex("^[A-Za-z0-9_.-]+$")

    fun locate(doc: LayoutDocument, id: String): NodeLocation? {
        doc.pages.forEachIndexed { pageIndex, page ->
            find(page.root, id, emptyList())?.let { return NodeLocation(pageIndex, it) }
        }
        return null
    }

    fun node(doc: LayoutDocument, id: String): Node? = locate(doc, id)?.let { nodeAt(doc, it.pageIndex, it.path) }

    fun parent(doc: LayoutDocument, id: String): ContainerNode? {
        val location = locate(doc, id) ?: return null
        if (location.isRoot) return null
        return nodeAt(doc, location.pageIndex, location.parentPath) as? ContainerNode
    }

    fun allIds(doc: LayoutDocument): Set<String> {
        val ids = mutableSetOf<String>()
        doc.walkNodes { _, node, _, _ -> ids += node.id }
        return ids
    }

    /** `base`, or `base_2`, `base_3`, … whichever is free. */
    fun uniqueId(doc: LayoutDocument, base: String, taken: Set<String> = allIds(doc)): String {
        val clean = base.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "node" }
        if (clean !in taken) return clean
        val stem = clean.replace(Regex("_\\d+$"), "")
        var n = 2
        while ("${stem}_$n" in taken) n++
        return "${stem}_$n"
    }

    fun replace(doc: LayoutDocument, id: String, node: Node): LayoutDocument? {
        val location = locate(doc, id) ?: return null
        if (node.id != id && node.id in allIds(doc)) return null
        return update(doc, location) { node }
    }

    fun remove(doc: LayoutDocument, id: String): LayoutDocument? {
        val location = locate(doc, id) ?: return null
        if (location.isRoot) return null
        return updateChildren(doc, location.pageIndex, location.parentPath) { it.filterIndexed { i, _ -> i != location.index } }
    }

    /** Inserts [node] into the container [parentId] at [index] (clamped; -1 appends). */
    fun insert(doc: LayoutDocument, parentId: String, index: Int, node: Node): LayoutDocument? {
        val location = locate(doc, parentId) ?: return null
        if (nodeAt(doc, location.pageIndex, location.path) !is ContainerNode) return null
        if (allIds(doc).intersect(idsOf(node)).isNotEmpty()) return null
        return updateChildren(doc, location.pageIndex, location.path) { children ->
            val at = if (index < 0) children.size else index.coerceIn(0, children.size)
            children.toMutableList().apply { add(at, node) }
        }
    }

    /** Inserts [node] right after [siblingId] in the same container. */
    fun insertAfter(doc: LayoutDocument, siblingId: String, node: Node): LayoutDocument? {
        val location = locate(doc, siblingId) ?: return null
        if (location.isRoot) return null
        val parent = nodeAt(doc, location.pageIndex, location.parentPath)
        return insert(doc, parent.id, location.index + 1, node)
    }

    /** Moves a node within its container by [delta] places (-1 = up/left, +1 = down/right). */
    fun move(doc: LayoutDocument, id: String, delta: Int): LayoutDocument? {
        val location = locate(doc, id) ?: return null
        if (location.isRoot) return null
        val siblings = (nodeAt(doc, location.pageIndex, location.parentPath) as ContainerNode).children
        val target = location.index + delta
        if (target !in siblings.indices || delta == 0) return null
        return updateChildren(doc, location.pageIndex, location.parentPath) { children ->
            children.toMutableList().apply { add(target, removeAt(location.index)) }
        }
    }

    /** Moves a node into the container just before it, as that container's last child. */
    fun moveIntoPrevious(doc: LayoutDocument, id: String): LayoutDocument? {
        val location = locate(doc, id) ?: return null
        if (location.isRoot || location.index == 0) return null
        val siblings = (nodeAt(doc, location.pageIndex, location.parentPath) as ContainerNode).children
        val target = siblings[location.index - 1] as? ContainerNode ?: return null
        val node = siblings[location.index]
        val removed = remove(doc, id) ?: return null
        return insert(removed, target.id, -1, node)
    }

    /** Moves a node out of its container, right after that container. */
    fun moveOut(doc: LayoutDocument, id: String): LayoutDocument? {
        val location = locate(doc, id) ?: return null
        if (location.path.size < 2) return null
        val node = nodeAt(doc, location.pageIndex, location.path)
        val parent = nodeAt(doc, location.pageIndex, location.parentPath)
        val removed = remove(doc, id) ?: return null
        return insertAfter(removed, parent.id, node)
    }

    /** Copies a node (and everything inside it) with fresh ids, right after the original. Returns the new id. */
    fun duplicate(doc: LayoutDocument, id: String): Pair<LayoutDocument, String>? {
        val location = locate(doc, id) ?: return null
        if (location.isRoot) return null
        val taken = allIds(doc).toMutableSet()
        val renamedIds = mutableMapOf<String, String>()
        val copy = renameAll(nodeAt(doc, location.pageIndex, location.path)) { old ->
            uniqueId(doc, old, taken).also { taken += it; renamedIds[old] = it }
        }
        val retargeted = mapTree(copy) { node ->
            renamedIds.entries.fold(node) { result, (old, new) -> retarget(result, old, new) }
        }
        return insertAfter(doc, id, retargeted)?.let { it to retargeted.id }
    }

    /** Changes a node's id and every reference to it (initial focus, focus links, hero targets). */
    fun rename(doc: LayoutDocument, oldId: String, newId: String): LayoutDocument? {
        if (oldId == newId) return doc
        if (!idPattern.matches(newId) || newId in allIds(doc)) return null
        val location = locate(doc, oldId) ?: return null
        val renamed = update(doc, location) { renameAll(it) { id -> if (id == oldId) newId else id } }
        return renamed.copy(
            pages = renamed.pages.map { page ->
                page.copy(
                    initialFocus = page.initialFocus?.let { if (it == oldId) newId else it },
                    root = mapTree(page.root) { node -> retarget(node, oldId, newId) },
                )
            },
        )
    }

    // ---------------------------------------------------------------- tree plumbing

    private fun find(node: Node, id: String, path: List<Int>): List<Int>? {
        if (node.id == id) return path
        node.children.forEachIndexed { i, child -> find(child, id, path + i)?.let { return it } }
        return null
    }

    fun nodeAt(doc: LayoutDocument, pageIndex: Int, path: List<Int>): Node =
        path.fold(doc.pages[pageIndex].root) { node, i -> node.children[i] }

    private fun update(doc: LayoutDocument, location: NodeLocation, change: (Node) -> Node): LayoutDocument {
        val page = doc.pages[location.pageIndex]
        val root = updateAt(page.root, location.path, change)
        return doc.copy(pages = doc.pages.toMutableList().apply { set(location.pageIndex, page.copy(root = root)) })
    }

    private fun updateAt(node: Node, path: List<Int>, change: (Node) -> Node): Node {
        if (path.isEmpty()) return change(node)
        val children = node.children.toMutableList()
        children[path.first()] = updateAt(children[path.first()], path.drop(1), change)
        return withChildren(node, children)
    }

    private fun updateChildren(doc: LayoutDocument, pageIndex: Int, containerPath: List<Int>, change: (List<Node>) -> List<Node>) =
        update(doc, NodeLocation(pageIndex, containerPath)) { withChildren(it, change(it.children)) }

    private fun withChildren(node: Node, children: List<Node>): Node = when (node) {
        is ColumnNode -> node.copy(children = children)
        is RowNode -> node.copy(children = children)
        is GridNode -> node.copy(children = children)
        is StackNode -> node.copy(children = children)
        else -> error("${node.id} has no children")
    }

    private fun mapTree(node: Node, change: (Node) -> Node): Node {
        val changed = change(node)
        if (changed !is ContainerNode) return changed
        return withChildren(changed, changed.children.map { mapTree(it, change) })
    }

    private fun renameAll(node: Node, newId: (String) -> String): Node = mapTree(node) { n -> withId(n, newId(n.id)) }

    private fun idsOf(node: Node): Set<String> {
        val ids = mutableSetOf<String>()
        mapTree(node) { ids += it.id; it }
        return ids
    }

    private fun retarget(node: Node, oldId: String, newId: String): Node {
        fun swap(id: String?) = if (id == oldId) newId else id
        var result = node
        node.common.focus?.let { links ->
            result = result.withCommon(
                result.common.copy(focus = links.copy(up = swap(links.up), down = swap(links.down), left = swap(links.left), right = swap(links.right))),
            )
        }
        if (result.common.showWhenFocused == oldId) result = result.withCommon(result.common.copy(showWhenFocused = newId))
        if (result is HeroNode && result.target == oldId) result = result.copy(target = newId)
        return result
    }

    private fun withId(node: Node, id: String): Node = NodeTemplates.withId(node, id)
}
