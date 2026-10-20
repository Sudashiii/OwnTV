package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.BuiltInLayouts
import dev.owntv.layout.validation.LayoutValidator
import dev.owntv.layout.validation.Severity
import dev.owntv.layout.validation.toAiFeedback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidatorTest {

    @Test
    fun `duplicate ids are errors pointing at the second use`() {
        val result = LayoutCodec.decode(
            docWithRoot("""{ "type": "row", "id": "root", "children": [ $TILE, { "type": "text", "id": "tile", "text": "x" } ] }"""),
        )
        assertNull(result.document)
        assertEquals(listOf("$.pages[0].root.children[1].id"), result.paths(Severity.Error))
    }

    @Test
    fun `initialFocus and go_to_page must point at existing targets`() {
        val result = LayoutCodec.decode(
            """
            { "schemaVersion": 3, "pages": [ { "id": "home", "initialFocus": "nope", "root":
              { "type": "shortcut", "id": "s", "label": "Go", "action": { "type": "go_to_page", "page": "missing" } } } ] }
            """,
        )
        assertEquals(setOf("$.pages[0].initialFocus", "$.pages[0].root.action.page"), result.paths(Severity.Error).toSet())
    }

    @Test
    fun `unknown color tokens and token theme colors are errors`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """{ "type": "column", "id": "root", "background": "@brand", "children": [ $TILE ] }""",
                extra = """"theme": { "colors": { "primary": "@text", "custom": { "ok": "#123456" } } },""",
            ),
        )
        assertEquals(setOf("$.theme.colors.primary", "$.pages[0].root.background"), result.paths(Severity.Error).toSet())
    }

    @Test
    fun `custom tokens resolve`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """{ "type": "column", "id": "root", "background": "@brand", "children": [ $TILE ] }""",
                extra = """"theme": { "colors": { "custom": { "brand": "#FF8800" } } },""",
            ),
        )
        assertTrue(result.ok, result.issues.joinToString())
        assertEquals(0xFFFF8800.toInt(), result.document!!.theme.colors.resolve("@brand"))
    }

    @Test
    fun `grid span larger than columns is an error`() {
        val result = LayoutCodec.decode(
            docWithRoot("""{ "type": "grid", "id": "root", "columns": 2, "children": [ { "type": "app_tile", "id": "t", "package": "a.b", "span": 3 } ] }"""),
        )
        assertEquals(listOf("$.pages[0].root.children[0].span"), result.paths(Severity.Error))
    }

    @Test
    fun `page without focusable widgets is a warning`() {
        val result = LayoutCodec.decode(docWithRoot("""{ "type": "text", "id": "t", "text": "Hello" }"""))
        assertTrue(result.ok)
        assertEquals(listOf("$.pages[0].root"), result.paths(Severity.Warning))
    }

    @Test
    fun `invalid clock pattern is an error`() {
        val result = LayoutCodec.decode(
            docWithRoot("""{ "type": "column", "id": "root", "children": [ $TILE, { "type": "clock", "id": "c", "format": "HH:mm {" } ] }"""),
        )
        assertEquals(listOf("$.pages[0].root.children[1].format"), result.paths(Severity.Error))
    }

    @Test
    fun `packages that are not installed produce warnings`() {
        val result = LayoutCodec.decode(
            docWithRoot("""{ "type": "row", "id": "r", "children": [
                { "type": "app_tile", "id": "a", "package": "a.b" }, { "type": "app_tile", "id": "c", "package": "c.d" } ] }"""),
            installedPackages = setOf("a.b"),
        )
        assertTrue(result.ok)
        assertEquals(listOf("$.pages[0].root.children[1].package"), result.paths(Severity.Warning))
    }

    @Test
    fun `list names are checked`() {
        val result = LayoutCodec.decode(docWithRoot("""{ "type": "app_row", "id": "r", "source": { "type": "list", "list": "my list" } }"""))
        assertEquals(listOf("$.pages[0].root.source.list"), result.paths(Severity.Error))
    }

    @Test
    fun `bad ids are errors`() {
        val result = LayoutCodec.decode(docWithRoot("""{ "type": "app_tile", "id": "my tile", "package": "a.b" }"""))
        assertEquals(listOf("$.pages[0].root.id"), result.paths(Severity.Error))
    }

    @Test
    fun `ai feedback lists errors and warnings but not info`() {
        val result = LayoutCodec.decode("```json\n" + docWithRoot("""{ "type": "text", "id": "t", "text": "x", "color": "red" }""") + "\n```")
        val feedback = result.issues.toAiFeedback()
        assertTrue(feedback.contains("ERROR at $.pages[0].root.color"), feedback)
        assertTrue(!feedback.contains("INFO"), feedback)
    }

    @Test
    fun `built-in fallback layout is valid`() {
        assertEquals(emptyList(), LayoutValidator.validate(BuiltInLayouts.fallback()))
        assertTrue(LayoutCodec.decode(LayoutCodec.encode(BuiltInLayouts.fallback())).ok)
    }
}
