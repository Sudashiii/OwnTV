package dev.owntv.launcher.ui.editor

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.edit.Fields
import dev.owntv.layout.edit.LayoutOps
import dev.owntv.layout.edit.PathStep
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.HeroSlide
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.validation.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorStateTest {
    private fun sample() = LayoutCodec.decode(
        """
        {"schemaVersion":2,"name":"Editor test","styles":{"row":{"spacing":28}},"pages":[
          {"id":"home","initialFocus":"apps","root":{"type":"column","id":"root","children":[
            {"type":"app_row","id":"apps","style":"row","source":{"type":"all"},"focus":{"down":"apps"}},
            {"type":"hero","id":"hero","target":"root"}
          ]}},
          {"id":"other","root":{"type":"shortcut","id":"other_root","label":"Apps","action":{"type":"open_launcher_settings"}}}
        ]}
        """,
    ).document!!

    @Test
    fun `property edits retain styles and survive save decoding`() {
        val state = EditorState(sample(), "apps")
        assertTrue(state.setValue(FormTarget.NodeTarget("apps"), listOf(PathStep.Key("title")), JsonPrimitive("Favorites")))
        assertEquals("row", LayoutOps.node(state.doc, "apps")!!.common.style)
        assertEquals(state.original.styles, state.doc.styles)
        val saved = LayoutCodec.decode(LayoutCodec.encode(state.doc))
        assertTrue(saved.ok, saved.issues.toString())
        assertEquals("Favorites", (LayoutOps.node(saved.document!!, "apps") as AppRowNode).title)
        assertTrue(state.dirty)
        state.undo()
        assertFalse(state.dirty)
        assertTrue(state.canRedo)
        state.redo()
        assertTrue(state.dirty)
        assertFalse(state.canRedo)
    }

    @Test
    fun `renaming updates references inside the edited node and its descendants`() {
        val state = EditorState(sample(), "apps")
        state.nodeForm("apps")!!.let(state::push)
        assertTrue(state.setValue(FormTarget.NodeTarget("apps"), listOf(PathStep.Key("id")), JsonPrimitive("favorites")))
        assertEquals("favorites", state.selectedId)
        assertEquals("favorites", state.page.initialFocus)
        assertEquals("favorites", LayoutOps.node(state.doc, "favorites")!!.common.focus!!.down)
        assertEquals(FormTarget.NodeTarget("favorites"), (state.panels.last() as Panel.Form).target)
        assertTrue(state.setValue(FormTarget.NodeTarget("root"), listOf(PathStep.Key("id")), JsonPrimitive("home_root")))
        assertEquals("home_root", (LayoutOps.node(state.doc, "hero") as HeroNode).target)
        assertTrue(state.issues.none { it.severity == Severity.Error }, state.issues.toString())
    }

    @Test
    fun `invalid rename leaves the document and history intact`() {
        val state = EditorState(sample(), "apps")
        assertFalse(state.setValue(FormTarget.NodeTarget("apps"), listOf(PathStep.Key("id")), JsonPrimitive("hero")))
        assertFalse(state.dirty)
        assertFalse(state.canUndo)
        assertNotNull(state.message)
    }

    @Test
    fun `undo closes forms for nodes that no longer exist`() {
        val state = EditorState(sample(), null)
        state.edit(LayoutOps.insert(state.doc, "root", -1, TextNode("added", "Hello")))
        state.selectedId = "added"
        state.push(Panel.NodeMenu("added"))
        state.push(state.nodeForm("added")!!)
        state.undo()
        assertEquals(listOf(Panel.Outline), state.panels.toList())
        assertEquals("root", state.selectedId)
        state.redo()
        assertNotNull(LayoutOps.node(state.doc, "added"))
    }

    @Test
    fun `undo closes a stale list item form while retaining its parent form`() {
        val state = EditorState(sample(), "hero")
        val target = FormTarget.NodeTarget("hero")
        val path = listOf(PathStep.Key("slides"))
        val spec = Fields.forNode("hero").first { it.name == "slides" }
        val list = Panel.ListForm(target, path, "Slides", spec)
        state.push(list)
        state.setValue(target, path + PathStep.Index(0), JsonObject(mapOf("title" to JsonPrimitive("Slide"))))
        state.push(Panel.Form(target, path + PathStep.Index(0), "Slide", listOf(HeroSlide.serializer().descriptor)))
        state.undo()
        assertEquals(list, state.panels.last())
        assertEquals(JsonArray(emptyList()), state.rootJson(target)!!["slides"])
    }

    @Test
    fun `resetting an optional nested object restores its default`() {
        val state = EditorState(sample(), "apps")
        val target = FormTarget.NodeTarget("apps")
        assertTrue(state.setValue(target, listOf(PathStep.Key("tile"), PathStep.Key("shape")), JsonPrimitive("icon")))
        assertTrue(state.setValue(target, listOf(PathStep.Key("tile")), null))
        assertEquals("banner", (state.rootJson(target)!!["tile"] as JsonObject)["shape"]?.let { (it as JsonPrimitive).content })
        assertNull(state.message)
    }

    @Test
    fun `selecting a node on another page opens that page`() {
        val state = EditorState(sample(), "other_root")
        assertEquals(1, state.pageIndex)
        assertEquals("other_root", state.selectedId)
        state.push(Panel.Form(FormTarget.DocumentTarget, emptyList(), "Layout", listOf(LayoutDocument.serializer().descriptor)))
        state.showPage(0)
        assertEquals("root", state.selectedId)
        assertEquals(listOf(Panel.Outline), state.panels.toList())
    }
}
