package dev.owntv.layout.model

/** Children of a container, or an empty list for widgets. */
val Node.children: List<Node>
    get() = (this as? ContainerNode)?.children.orEmpty()

/** True for widgets the user can focus and click with the remote. */
val Node.isFocusableWidget: Boolean
    get() = this is AppTileNode || this is AppRowNode || this is AppGridNode || this is ShortcutNode

/** True when this node or one of its visible descendants can take focus. */
fun Node.containsFocusable(): Boolean =
    visible && (isFocusableWidget || children.any { it.containsFocusable() })

/** Visits this node and all descendants depth-first, with their JSON path. */
fun Node.walk(path: String, depth: Int = 0, visit: (node: Node, path: String, depth: Int) -> Unit) {
    visit(this, path, depth)
    children.forEachIndexed { index, child -> child.walk("$path.children[$index]", depth + 1, visit) }
}

/** Visits every node of every page with its JSON path. */
fun LayoutDocument.walkNodes(visit: (page: Page, node: Node, path: String, depth: Int) -> Unit) {
    pages.forEachIndexed { index, page ->
        page.root.walk("$.pages[$index].root") { node, path, depth -> visit(page, node, path, depth) }
    }
}

fun Page.findNode(id: String): Node? {
    var found: Node? = null
    root.walk("") { node, _, _ -> if (found == null && node.id == id) found = node }
    return found
}
