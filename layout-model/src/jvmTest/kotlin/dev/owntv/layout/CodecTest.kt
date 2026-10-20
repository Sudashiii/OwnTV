package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.Padding
import dev.owntv.layout.model.AppLists
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.ListSource
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.Size
import dev.owntv.layout.model.UnknownNode
import dev.owntv.layout.validation.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

    @Test
    fun `round trips every example without changing meaning`() {
        examplesDir.listFiles { f -> f.extension == "json" }!!.forEach { file ->
            val first = LayoutCodec.decode(file.readText()).requireDocument()
            val second = LayoutCodec.decode(LayoutCodec.encode(first)).requireDocument()
            assertEquals(first, second, "Round trip changed ${file.name}")
        }
    }

    @Test
    fun `decodes sizes padding and actions`() {
        val doc = LayoutCodec.decode(
            docWithRoot(
                """
                { "type": "column", "id": "root", "width": "fill", "height": 300, "padding": [48, 32],
                  "children": [
                    { "type": "shortcut", "id": "s", "label": "YT", "action": { "type": "launch_app", "package": "com.google.android.youtube.tv" } }
                  ] }
                """,
            ),
        ).requireDocument()
        val root = assertIs<ColumnNode>(doc.pages[0].root)
        assertEquals(Size.Fill, root.common.width)
        assertEquals(Size.Fixed(300), root.common.height)
        assertEquals(Padding(48, 32, 48, 32), root.common.padding)
        val shortcut = assertIs<ShortcutNode>(root.children[0])
        assertEquals(LaunchAppAction("com.google.android.youtube.tv"), shortcut.action)
    }

    @Test
    fun `encoding writes type first, keeps schemaVersion and drops defaults`() {
        val doc = LayoutCodec.decode(docWithRoot(TILE)).requireDocument()
        val text = LayoutCodec.encode(doc)
        assertTrue(text.contains("\"schemaVersion\": 3"))
        assertTrue(Regex("\\{\\s*\"type\": \"app_tile\",\\s*\"id\": \"tile\"").containsMatchIn(text), text)
        assertFalse(text.contains("\"visible\""), "Defaults should not be written")
    }

    @Test
    fun `unknown widget type becomes a placeholder and survives a round trip`() {
        val result = LayoutCodec.decode(
            docWithRoot("""{ "type": "column", "id": "root", "children": [ $TILE, { "type": "weather", "id": "w", "city": "Vienna" } ] }"""),
        )
        val doc = result.requireDocument()
        val unknown = assertIs<UnknownNode>((doc.pages[0].root as ColumnNode).children[1])
        assertEquals("weather", unknown.type)
        assertEquals("w", unknown.id)
        assertEquals(listOf("$.pages[0].root.children[1].type"), result.paths(Severity.Warning))
        assertTrue(LayoutCodec.encode(doc).contains("\"city\": \"Vienna\""))
    }

    @Test
    fun `unknown property is a warning with its path`() {
        val result = LayoutCodec.decode(docWithRoot("""{ "type": "app_tile", "id": "tile", "package": "a.b", "colour": "#fff" }"""))
        assertTrue(result.ok)
        assertEquals(listOf("$.pages[0].root.colour"), result.paths(Severity.Warning))
    }

    @Test
    fun `reports every structural error at once with paths`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """
                { "type": "column", "id": "root", "spacing": "big", "children": [
                  { "type": "app_row", "id": "r", "tile": { "shape": "huge" } },
                  { "type": "text", "id": "t", "text": "Hi", "color": "red" },
                  { "type": "app_grid", "id": "g", "columns": 40, "source": { "type": "favourites" } }
                ] }
                """,
            ),
        )
        assertNull(result.document)
        val errors = result.paths(Severity.Error).toSet()
        assertEquals(
            setOf(
                "$.pages[0].root.spacing",
                "$.pages[0].root.children[0].tile.shape",
                "$.pages[0].root.children[0]", // missing source
                "$.pages[0].root.children[1].color",
                "$.pages[0].root.children[2].columns",
                "$.pages[0].root.children[2].source.type",
            ),
            errors,
        )
    }

    @Test
    fun `missing pages is an error`() {
        val result = LayoutCodec.decode("""{ "schemaVersion": 3, "name": "x" }""")
        assertNull(result.document)
        assertTrue(result.issues.any { it.severity == Severity.Error && it.message.contains("\"pages\"") })
    }

    @Test
    fun `accepts code fences, comments and trailing commas from AI answers`() {
        val text = """
            Here is your layout:

            ```json
            {
              // a comment
              "schemaVersion": 3,
              "pages": [ { "id": "home", "root": $TILE, }, ],
            }
            ```
            Enjoy!
        """.trimIndent()
        val result = LayoutCodec.decode(text)
        assertTrue(result.ok, result.issues.joinToString())
        assertTrue(result.issues.any { it.severity == Severity.Info })
    }

    @Test
    fun `invalid json is reported, not thrown`() {
        val result = LayoutCodec.decode("{ \"schemaVersion\": 1, \"pages\": [ ")
        assertNull(result.document)
        assertEquals(Severity.Error, result.issues.single().severity)
    }

    @Test
    fun `newer schema versions are rejected and missing version is assumed`() {
        val newer = LayoutCodec.decode("""{ "schemaVersion": 99, "pages": [] }""")
        assertNull(newer.document)
        assertEquals(listOf("$.schemaVersion"), newer.paths(Severity.Error))

        val missing = LayoutCodec.decode("""{ "pages": [ { "id": "home", "root": $TILE } ] }""")
        assertTrue(missing.ok)
        assertEquals(listOf("$.schemaVersion"), missing.paths(Severity.Warning))
    }

    @Test
    fun `app row source decodes from a list`() {
        val result = LayoutCodec.decode(docWithRoot("""{ "type": "app_row", "id": "r", "source": { "type": "list", "list": "favorites" } }"""))
        val row = assertIs<AppRowNode>(result.requireDocument().pages[0].root)
        assertEquals(ListSource("favorites"), row.source)
        assertNull(result.extractedLists)
    }

    @Test
    fun `version 2 package lists and hidden apps move out of the layout`() {
        val result = LayoutCodec.decode(
            """
            { "schemaVersion": 2,
              "apps": { "a.b": { "label": "AB", "hidden": true }, "c.d": { "hidden": false } },
              "pages": [ { "id": "home", "root": { "type": "column", "id": "root", "children": [
                { "type": "app_row", "id": "favorites", "source": { "type": "packages", "packages": ["c.d", "a.b", "c.d"] } },
                { "type": "folder", "id": "my.folder", "label": "F", "source": { "type": "packages", "packages": ["e.f"] } },
                { "type": "app_grid", "id": "all", "source": { "type": "all" } }
              ] } } ] }
            """,
        )
        val doc = result.requireDocument()
        assertEquals(3, doc.schemaVersion)
        assertEquals(listOf(Severity.Info), result.issues.map { it.severity })
        val children = (doc.pages[0].root as ColumnNode).children
        assertEquals(ListSource("favorites"), assertIs<AppRowNode>(children[0]).source)
        assertEquals(ListSource("my_folder"), assertIs<FolderNode>(children[1]).source)
        assertEquals("AB", doc.apps.getValue("a.b").label)
        assertEquals(
            AppLists(lists = mapOf("favorites" to listOf("c.d", "a.b"), "my_folder" to listOf("e.f")), hidden = setOf("a.b")),
            result.extractedLists,
        )
    }
}
