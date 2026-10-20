package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.edit.FieldKind
import dev.owntv.layout.edit.FieldSpec
import dev.owntv.layout.edit.Fields
import dev.owntv.layout.edit.LayoutOps
import dev.owntv.layout.edit.NodeTemplates
import dev.owntv.layout.edit.PathStep
import dev.owntv.layout.edit.at
import dev.owntv.layout.edit.with
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Page
import dev.owntv.layout.model.NodeRegistry
import dev.owntv.layout.model.RowNode
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.children
import dev.owntv.layout.validation.LayoutValidator
import dev.owntv.layout.validation.Severity
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditTest {

    /** root column: [a (row: [a1, a2]), b2 (shortcut), c (column: [c_name, shown with focus in a]), d (hero, target a)] */
    private fun sample(): LayoutDocument = LayoutCodec.decode(
        """
        { "schemaVersion": 3, "pages": [ { "id": "home", "initialFocus": "a1", "root":
          { "type": "column", "id": "root", "children": [
            { "type": "row", "id": "a", "children": [
              { "type": "app_tile", "id": "a1", "package": "x.y", "focus": { "down": "b2" } },
              { "type": "app_tile", "id": "a2", "package": "x.z" } ] },
            { "type": "shortcut", "id": "b2", "label": "B", "action": { "type": "open_app_drawer" } },
            { "type": "column", "id": "c", "children": [ { "type": "text", "id": "c_name", "text": "{focused}", "showWhenFocused": "a" } ] },
            { "type": "hero", "id": "d", "target": "a" }
          ] } }, { "id": "settings_page", "root": $SETTINGS_LINK } ] }
        """,
    ).requireDocument()

    private fun LayoutDocument.childIds(id: String) = LayoutOps.node(this, id)!!.children.map { it.id }

    private fun LayoutDocument.assertValid() {
        val errors = LayoutValidator.validate(this).filter { it.severity == Severity.Error }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `locate finds nodes and parents`() {
        val doc = sample()
        assertEquals(listOf(0, 1), LayoutOps.locate(doc, "a2")!!.path)
        assertTrue(LayoutOps.locate(doc, "root")!!.isRoot)
        assertEquals("a", LayoutOps.parent(doc, "a2")!!.id)
        assertNull(LayoutOps.parent(doc, "root"))
        assertNull(LayoutOps.locate(doc, "nope"))
    }

    @Test
    fun `insert places nodes and refuses non-containers and taken ids`() {
        val doc = sample()
        val text = TextNode("t", "Hi")
        assertEquals(listOf("t", "a1", "a2"), LayoutOps.insert(doc, "a", 0, text)!!.childIds("a"))
        assertEquals(listOf("a1", "a2", "t"), LayoutOps.insert(doc, "a", -1, text)!!.childIds("a"))
        assertEquals(listOf("a1", "a2", "t"), LayoutOps.insert(doc, "a", 99, text)!!.childIds("a"))
        assertNull(LayoutOps.insert(doc, "a1", 0, text))
        assertNull(LayoutOps.insert(doc, "a", 0, TextNode("b2", "dup")))
        assertEquals(listOf("a", "b2", "t", "c", "d"), LayoutOps.insertAfter(doc, "b2", text)!!.childIds("root"))
        assertNull(LayoutOps.insertAfter(doc, "root", text))
    }

    @Test
    fun `remove deletes nodes but never the page root`() {
        val doc = sample()
        assertEquals(listOf("a", "c", "d"), LayoutOps.remove(doc, "b2")!!.childIds("root"))
        assertNull(LayoutOps.remove(doc, "root"))
        assertNull(LayoutOps.remove(doc, "nope"))
    }

    @Test
    fun `move shifts within the parent and stops at the ends`() {
        val doc = sample()
        assertEquals(listOf("b2", "a", "c", "d"), LayoutOps.move(doc, "b2", -1)!!.childIds("root"))
        assertEquals(listOf("a2", "a1"), LayoutOps.move(doc, "a1", 1)!!.childIds("a"))
        assertNull(LayoutOps.move(doc, "a", -1))
        assertNull(LayoutOps.move(doc, "d", 1))
        assertNull(LayoutOps.move(doc, "root", 1))
    }

    @Test
    fun `move into the previous container and back out`() {
        val doc = sample()
        val into = LayoutOps.moveIntoPrevious(doc, "b2")!!
        assertEquals(listOf("a1", "a2", "b2"), into.childIds("a"))
        assertEquals(listOf("a", "c", "d"), into.childIds("root"))
        assertNull(LayoutOps.moveIntoPrevious(doc, "c")) // previous sibling b2 is not a container
        assertNull(LayoutOps.moveIntoPrevious(doc, "a")) // first child

        val out = LayoutOps.moveOut(into, "b2")!!
        assertEquals(listOf("a", "b2", "c", "d"), out.childIds("root"))
        assertNull(LayoutOps.moveOut(doc, "a")) // already directly in the root
    }

    @Test
    fun `duplicate copies a subtree with fresh ids`() {
        val doc = sample()
        val (copy, newId) = LayoutOps.duplicate(doc, "a")!!
        assertEquals("a_2", newId)
        assertEquals(listOf("a", "a_2", "b2", "c", "d"), copy.childIds("root"))
        assertEquals(listOf("a1_2", "a2_2"), copy.childIds("a_2"))
        copy.assertValid()
        assertNull(LayoutOps.duplicate(doc, "root"))
    }

    @Test
    fun `rename updates every reference`() {
        val doc = sample()
        val renamed = LayoutOps.rename(doc, "a", "dock")!!
        assertEquals("dock", (LayoutOps.node(renamed, "d") as HeroNode).target)
        assertEquals("dock", LayoutOps.node(renamed, "c_name")!!.common.showWhenFocused)
        val renamedTile = LayoutOps.rename(doc, "b2", "button")!!
        assertEquals("button", LayoutOps.node(renamedTile, "a1")!!.common.focus!!.down)
        assertEquals("first", LayoutOps.rename(doc, "a1", "first")!!.pages[0].initialFocus)
        renamed.assertValid()
        assertNull(LayoutOps.rename(doc, "a", "b2")) // taken
        assertNull(LayoutOps.rename(doc, "a", "has space"))
    }

    @Test
    fun `duplicate retargets references within its subtree and retains external links`() {
        var doc = sample()
        val tile = LayoutOps.node(doc, "a1")!!
        doc = LayoutOps.replace(doc, "a1", tile.withCommon(tile.common.copy(focus = tile.common.focus!!.copy(right = "a2"))))!!
        doc = LayoutOps.insert(doc, "a", -1, HeroNode("inside_hero", target = "a"))!!
        val (copy, _) = LayoutOps.duplicate(doc, "a")!!
        assertEquals("a2_2", LayoutOps.node(copy, "a1_2")!!.common.focus!!.right)
        assertEquals("b2", LayoutOps.node(copy, "a1_2")!!.common.focus!!.down)
        assertEquals("a_2", (LayoutOps.node(copy, "inside_hero_2") as HeroNode).target)
        assertEquals("a", (LayoutOps.node(copy, "inside_hero") as HeroNode).target)
        copy.assertValid()
        val (section, _) = LayoutOps.duplicate(doc, "c")!!
        assertEquals("a", LayoutOps.node(section, "c_name_2")!!.common.showWhenFocused)
    }

    @Test
    fun `replace swaps a node in place`() {
        val doc = sample()
        val replaced = LayoutOps.replace(doc, "c", RowNode("c", spacing = 4))!!
        assertTrue(LayoutOps.node(replaced, "c") is RowNode)
        assertNull(LayoutOps.replace(doc, "c", RowNode("a")))
    }

    @Test
    fun `unique ids count up`() {
        val doc = sample()
        assertEquals("text", LayoutOps.uniqueId(doc, "text"))
        assertEquals("a_2", LayoutOps.uniqueId(doc, "a"))
        assertEquals("my_id", LayoutOps.uniqueId(doc, "my id"))
    }

    @Test
    fun `every palette template is valid inside a layout`() {
        var doc = sample()
        NodeRegistry.types.forEach { type ->
            val node = assertNotNull(NodeTemplates.create(type, LayoutOps.uniqueId(doc, type), samplePackage = "x.y"), type)
            doc = LayoutOps.insert(doc, "c", -1, node)!!
        }
        doc.assertValid()
        assertEquals(NodeRegistry.types.size + 1, (LayoutOps.node(doc, "c") as ColumnNode).children.size) // + c_name
        assertTrue(LayoutCodec.decode(LayoutCodec.encode(doc)).ok)
    }

    @Test
    fun `field specs describe node properties`() {
        val row = Fields.forNode("app_row").associateBy { it.name }
        assertEquals(FieldKind.Text, row.getValue("id").kind)
        assertEquals(FieldKind.Sealed, row.getValue("source").kind)
        assertEquals(FieldKind.Object, row.getValue("tile").kind)
        assertEquals(FieldKind.Int, row.getValue("spacing").kind)
        assertEquals(0.0..200.0, row.getValue("spacing").range)
        assertEquals(FieldKind.Size, row.getValue("width").kind)
        assertEquals(FieldKind.Background, row.getValue("background").kind)
        assertEquals(FieldKind.StyleRef, row.getValue("style").kind)
        assertEquals(FieldKind.Enum, row.getValue("scrollAlign").kind)
        assertTrue(row.getValue("width").nullable)

        assertTrue("children" !in Fields.forNode("column").map { it.name })
        val hero = Fields.forNode("hero").associateBy { it.name }
        assertEquals(FieldKind.NodeRef, hero.getValue("target").kind)
        assertEquals(FieldKind.ObjectList, hero.getValue("slides").kind)

        val source = Fields.forNode("app_row").first { it.name == "source" }
        val list = Fields.forDescriptor(Fields.subtypes(source.descriptor).getValue("list")).single()
        assertEquals(FieldKind.Text, list.kind)
        val exclude = Fields.forDescriptor(Fields.subtypes(source.descriptor).getValue("all")).first { it.name == "exclude" }
        assertEquals(FieldKind.Package, exclude.itemKind)
    }

    @Test
    fun `new sealed values decode`() {
        val sourceDescriptor = AppSource.serializer().descriptor
        Fields.subtypes(sourceDescriptor).keys.forEach { type ->
            LayoutCodec.json.decodeFromJsonElement(AppSource.serializer(), Fields.newSealed(sourceDescriptor, type))
        }
        val background = Background.serializer().descriptor
        assertEquals(setOf("color", "gradient", "image", "glass"), Fields.subtypes(background).keys)
        LayoutCodec.json.decodeFromJsonElement(Background.serializer(), Fields.newSealed(background, "gradient"))
    }

    @Test
    fun `json paths read and write nested values`() {
        val obj = JsonObject(mapOf("tile" to JsonObject(mapOf("size" to JsonPrimitive("small"))), "list" to JsonArray(listOf(JsonPrimitive("a")))))
        assertEquals(JsonPrimitive("small"), obj.at(listOf(PathStep.Key("tile"), PathStep.Key("size"))))
        val changed = obj.with(listOf(PathStep.Key("tile"), PathStep.Key("shape")), JsonPrimitive("icon"))
        assertEquals(JsonPrimitive("icon"), changed.at(listOf(PathStep.Key("tile"), PathStep.Key("shape"))))
        assertEquals(JsonPrimitive("small"), changed.at(listOf(PathStep.Key("tile"), PathStep.Key("size"))))
        val appended = obj.with(listOf(PathStep.Key("list"), PathStep.Index(5)), JsonPrimitive("b"))
        assertEquals(2, (appended.at(listOf(PathStep.Key("list"))) as JsonArray).size)
        val removed = obj.with(listOf(PathStep.Key("tile")), null)
        assertNull(removed.at(listOf(PathStep.Key("tile"))))
        val created = obj.with(listOf(PathStep.Key("border"), PathStep.Key("width")), JsonPrimitive(3))
        assertEquals(JsonPrimitive(3), created.at(listOf(PathStep.Key("border"), PathStep.Key("width"))))
    }

    /**
     * Every property reachable from the editor's forms must have an editor control. If this fails after
     * adding a property, give it a [FieldKind] (see CLAUDE.md, "Editor rules") instead of extending the allow list.
     */
    @Test
    fun `every editor field has a control`() {
        val unsupported = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        fun visit(owner: String, fields: List<FieldSpec>) {
            for (field in fields) {
                val where = "$owner.${field.name}"
                val nested = when (field.kind) {
                    FieldKind.Unsupported -> emptyMap<String, SerialDescriptor>().also { unsupported += where }
                    FieldKind.Object -> mapOf(where to field.descriptor)
                    FieldKind.ObjectList -> mapOf(where to field.descriptor.getElementDescriptor(0))
                    FieldKind.Sealed -> Fields.subtypes(field.descriptor).mapKeys { (type, _) -> "$where<$type>" }
                    else -> emptyMap()
                }
                nested.forEach { (path, descriptor) ->
                    if (seen.add(descriptor.serialName.removeSuffix("?"))) visit(path, Fields.forDescriptor(descriptor))
                }
            }
        }
        NodeRegistry.types.forEach { visit(it, Fields.forNode(it)) }
        visit("page", Fields.forDescriptors(listOf(Page.serializer().descriptor), skip = setOf("root", "id")))
        visit("layout", Fields.forDescriptors(listOf(LayoutDocument.serializer().descriptor), skip = setOf("pages", "styles", "defaults", "apps", "schemaVersion")))
        // Maps are edited in the JSON for now (docs/HANDOFF-phase2.md, "Deliberate boundaries").
        val allowed = setOf("shortcut.action<intent>.extras", "layout.theme.colors.custom")
        assertEquals(allowed, unsupported, "Fields without an editor control (update the list when one gains a control)")
    }
}
