package dev.owntv.launcher.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.edit.FieldSpec
import dev.owntv.layout.edit.LayoutOps
import dev.owntv.layout.edit.NodeTemplates
import dev.owntv.layout.edit.PathStep
import dev.owntv.layout.edit.at
import dev.owntv.layout.edit.with
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Node
import dev.owntv.layout.model.NodeRegistry
import dev.owntv.layout.model.NodeSerializer
import dev.owntv.layout.model.Page
import dev.owntv.layout.validation.LayoutValidator
import dev.owntv.layout.validation.ValidationIssue
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/** What a property form edits. */
sealed interface FormTarget {
    data class NodeTarget(val id: String) : FormTarget
    data object PageTarget : FormTarget
    data object DocumentTarget : FormTarget
}

/** One screen of the editor's side panel. The panel works like a stack; Back pops it. */
sealed interface Panel {
    data object Outline : Panel
    data class NodeMenu(val id: String) : Panel
    data class Palette(val anchorId: String, val inside: Boolean) : Panel

    /** Fields of an object at [path]. With [sealedBase], the object is one of several types and a type row is shown. */
    data class Form(
        val target: FormTarget,
        val path: List<PathStep>,
        val title: String,
        val descriptors: List<SerialDescriptor> = emptyList(),
        val skip: Set<String> = emptySet(),
        val sealedBase: SerialDescriptor? = null,
    ) : Panel

    /** Items of a list at [path]: strings edited in a dialog, or objects edited in their own form. */
    data class ListForm(
        val target: FormTarget,
        val path: List<PathStep>,
        val title: String,
        val spec: FieldSpec,
    ) : Panel
}

/** A field being edited in a dialog. [path] points at the value inside the target's JSON. */
data class FieldEdit(
    val target: FormTarget,
    val path: List<PathStep>,
    val spec: FieldSpec,
    /** For list items: the kind of one item, and whether the item already exists. */
    val listItem: Boolean = false,
    val sealedBase: SerialDescriptor? = null,
)

/** All editor state: the document being edited, undo/redo, selection and the side panel stack. */
@Stable
class EditorState(val original: LayoutDocument, selectId: String?) {
    var doc by mutableStateOf(original)
        private set

    private val undoStack = mutableStateListOf<LayoutDocument>()
    private val redoStack = mutableStateListOf<LayoutDocument>()

    var pageIndex by mutableIntStateOf(selectId?.let { LayoutOps.locate(original, it)?.pageIndex } ?: 0)
        private set

    var selectedId by mutableStateOf(selectId?.takeIf { LayoutOps.locate(original, it) != null } ?: original.pages.first().root.id)

    val panels = mutableStateListOf<Panel>(Panel.Outline)
    var dialog by mutableStateOf<FieldEdit?>(null)
    var message by mutableStateOf<String?>(null)

    val page: Page get() = doc.pages[pageIndex.coerceIn(0, doc.pages.lastIndex)]
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val dirty: Boolean get() = doc != original
    val issues: List<ValidationIssue> by derivedStateOf { LayoutValidator.validate(doc) }

    /** Records [next] as a new version. Null means the edit was not possible; [failure] is shown instead. */
    fun edit(next: LayoutDocument?, failure: String = "That is not possible here."): Boolean {
        if (next == null) {
            message = failure
            return false
        }
        if (next != doc) {
            undoStack += doc
            if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
            redoStack.clear()
            doc = next
        }
        message = null
        return true
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack += doc
        doc = previous
        afterHistoryChange()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack += doc
        doc = next
        afterHistoryChange()
    }

    private fun afterHistoryChange() {
        message = null
        dialog = null
        if (LayoutOps.locate(doc, selectedId) == null) selectedId = page.root.id
        // Panels may point at nodes that no longer exist.
        while (panels.size > 1 && !panels.last().isValid()) panels.removeAt(panels.lastIndex)
    }

    private fun Panel.isValid(): Boolean = when (this) {
        Panel.Outline -> true
        is Panel.NodeMenu -> LayoutOps.locate(doc, id) != null
        is Panel.Palette -> LayoutOps.locate(doc, anchorId) != null
        is Panel.Form -> rootJson(target)?.at(path) is JsonObject
        is Panel.ListForm -> rootJson(target)?.at(path) is JsonArray
    }

    fun showPage(index: Int) {
        pageIndex = index.coerceIn(0, doc.pages.lastIndex)
        selectedId = page.root.id
        panels.removeRange(1, panels.size)
    }

    fun push(panel: Panel) {
        panels += panel
    }

    /** Pops the side panel; false when already at the outline. */
    fun pop(): Boolean {
        if (panels.size <= 1) return false
        panels.removeAt(panels.lastIndex)
        return true
    }

    fun popTo(panel: Panel) {
        while (panels.size > 1 && panels.last() != panel) panels.removeAt(panels.lastIndex)
    }

    // ---------------------------------------------------------------- JSON view of form targets

    fun rootJson(target: FormTarget): JsonObject? = when (target) {
        is FormTarget.NodeTarget -> LayoutOps.node(doc, target.id)?.let { editJson.encodeToJsonElement(NodeSerializer, it).jsonObject }
        FormTarget.PageTarget -> editJson.encodeToJsonElement(Page.serializer(), page).jsonObject
        FormTarget.DocumentTarget -> editJson.encodeToJsonElement(LayoutDocument.serializer(), doc).jsonObject
    }

    /** Sets one value inside a form target and applies it; null removes the value (back to its default). */
    fun setValue(target: FormTarget, path: List<PathStep>, value: JsonElement?): Boolean {
        val root = rootJson(target) ?: return false
        return applyRoot(target, root.with(path, value).jsonObject)
    }

    private fun applyRoot(target: FormTarget, json: JsonObject): Boolean = try {
        when (target) {
            is FormTarget.NodeTarget -> {
                val node: Node = editJson.decodeFromJsonElement(NodeSerializer, json)
                var next = LayoutOps.replace(doc, target.id, NodeTemplates.withId(node, target.id))
                if (node.id != target.id) next = next?.let { LayoutOps.rename(it, target.id, node.id) }
                edit(next, "Ids must be unique and use only letters, digits, _ . and -.").also { ok ->
                    if (ok && node.id != target.id) renameTarget(target.id, node.id)
                }
            }
            FormTarget.PageTarget -> {
                val page = editJson.decodeFromJsonElement(Page.serializer(), json)
                edit(doc.copy(pages = doc.pages.toMutableList().apply { set(pageIndex, page) }))
            }
            FormTarget.DocumentTarget -> edit(editJson.decodeFromJsonElement(LayoutDocument.serializer(), json))
        }
    } catch (e: Exception) {
        message = "That value does not fit here (${e.message?.lineSequence()?.firstOrNull()})."
        false
    }

    private fun renameTarget(old: String, new: String) {
        if (selectedId == old) selectedId = new
        for (i in panels.indices) {
            panels[i] = when (val p = panels[i]) {
                is Panel.NodeMenu -> if (p.id == old) p.copy(id = new) else p
                is Panel.Palette -> if (p.anchorId == old) p.copy(anchorId = new) else p
                is Panel.Form -> if (p.target == FormTarget.NodeTarget(old)) p.copy(target = FormTarget.NodeTarget(new)) else p
                is Panel.ListForm -> if (p.target == FormTarget.NodeTarget(old)) p.copy(target = FormTarget.NodeTarget(new)) else p
                Panel.Outline -> p
            }
        }
    }

    /** Fields form for a node: its own properties, with the common ones behind one more row. */
    fun nodeForm(id: String): Panel.Form? {
        val node = LayoutOps.node(doc, id) ?: return null
        val type = NodeRegistry.forNode(node) ?: return null
        return Panel.Form(
            FormTarget.NodeTarget(id),
            path = emptyList(),
            title = "${type.type} · $id",
            descriptors = listOf(type.serializer.descriptor),
            skip = setOf("children"),
        )
    }

    companion object {
        const val MAX_UNDO = 100

        /** Like the stored format, but with every default written out so the form can show it. */
        val editJson = Json(LayoutCodec.json) { encodeDefaults = true }
    }
}
