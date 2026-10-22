package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.edit.LayoutOps
import dev.owntv.layout.model.BuiltInLayouts
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.StandbyAction
import dev.owntv.layout.model.screensaverPage
import dev.owntv.layout.model.tabPages
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
              { "type": "shortcut", "id": "s", "label": "Go", "action": { "type": "go_to_page", "page": "missing" } } }, { "id": "settings_page", "root": $SETTINGS_LINK } ] }
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

    private fun singlePage(root: String) =
        """{ "schemaVersion": 3, "pages": [ { "id": "home", "root": $root } ] }"""

    private val settingsShortcut = """{ "type": "shortcut", "id": "s", "label": "Settings", "action": { "type": "open_launcher_settings" } }"""

    @Test
    fun `a layout without a link to launcher settings is rejected`() {
        val result = LayoutCodec.decode(singlePage("""{ "type": "column", "id": "root", "children": [ $TILE ] }"""))
        assertNull(result.document)
        assertEquals(listOf("$.pages"), result.paths(Severity.Error))
        assertTrue(result.issues.toAiFeedback().contains("open_launcher_settings"))

        // Other settings and system settings links do not count.
        val other = LayoutCodec.decode(
            singlePage("""{ "type": "column", "id": "root", "children": [
                { "type": "shortcut", "id": "a", "label": "A", "action": { "type": "open_system_settings" } },
                { "type": "shortcut", "id": "b", "label": "B", "action": { "type": "open_app_drawer" } } ] }"""),
        )
        assertEquals(listOf("$.pages"), other.paths(Severity.Error))
    }

    @Test
    fun `a visible link to launcher settings anywhere in the layout is enough`() {
        assertTrue(LayoutCodec.decode(singlePage(settingsShortcut)).ok)
        assertTrue(LayoutCodec.decode(singlePage("""{ "type": "column", "id": "root", "children": [ { "type": "row", "id": "r", "children": [ $settingsShortcut ] } ] }""")).ok)

        val onSecondPage = LayoutCodec.decode(docWithRoot(TILE))
        assertTrue(onSecondPage.ok, onSecondPage.issues.joinToString())

        val slide = LayoutCodec.decode(
            singlePage("""{ "type": "hero", "id": "h", "follow": "slides", "slides": [ { "title": "Hi", "action": { "type": "open_launcher_settings" } } ] }"""),
        )
        assertTrue(slide.ok, slide.issues.joinToString())
    }

    @Test
    fun `a hidden link to launcher settings does not count`() {
        val hidden = LayoutCodec.decode(
            singlePage("""{ "type": "column", "id": "root", "children": [ $TILE,
                { "type": "shortcut", "id": "s", "label": "Settings", "visible": false, "action": { "type": "open_launcher_settings" } } ] }"""),
        )
        assertEquals(listOf("$.pages"), hidden.paths(Severity.Error))

        val hiddenParent = LayoutCodec.decode(
            singlePage("""{ "type": "column", "id": "root", "children": [ $TILE,
                { "type": "row", "id": "r", "visible": false, "children": [ $settingsShortcut ] } ] }"""),
        )
        assertEquals(listOf("$.pages"), hiddenParent.paths(Severity.Error))

        // A hero that follows a target is not focusable, so its slides cannot be clicked.
        val unfocusableHero = LayoutCodec.decode(
            singlePage("""{ "type": "column", "id": "root", "children": [ $TILE,
                { "type": "hero", "id": "h", "target": "tile", "slides": [ { "title": "Hi", "action": { "type": "open_launcher_settings" } } ] } ] }"""),
        )
        assertEquals(listOf("$.pages"), unfocusableHero.paths(Severity.Error))
    }

    @Test
    fun `a screensaver page needs nothing focusable and is left out of page tabs`() {
        val saverPage = """{ "id": "saver", "root": { "type": "clock", "id": "saver_clock" } }"""
        val doc = """
            { "schemaVersion": 3, "pageTabs": true, "screensaver": { "page": "saver", "after": 60 },
              "pages": [ { "id": "home", "root": $SETTINGS_LINK }, { "id": "more", "root": $TILE }, $saverPage ] }
        """
        val result = LayoutCodec.decode(doc)
        assertTrue(result.issues.isEmpty(), result.issues.joinToString())
        val decoded = result.requireDocument()
        assertEquals("saver", decoded.screensaverPage?.id)
        assertEquals(listOf("home", "more"), decoded.tabPages.map { it.id })

        val withTile = LayoutCodec.decode(doc.replace(saverPage, """{ "id": "saver", "root": $TILE }""").replace("""{ "id": "more", "root": $TILE }, """, ""))
        assertEquals(listOf("$.pages[1].root"), withTile.paths(Severity.Warning))
    }

    @Test
    fun `standby after the screensaver and the standby action decode`() {
        val result = LayoutCodec.decode(
            """
            { "schemaVersion": 3, "screensaver": { "page": "saver", "standby": { "after": 1800 } },
              "pages": [ { "id": "home", "root": { "type": "row", "id": "root", "children": [ $SETTINGS_LINK,
                { "type": "shortcut", "id": "off", "label": "Off", "icon": "power", "action": { "type": "standby" } } ] } },
                { "id": "saver", "root": { "type": "clock", "id": "saver_clock" } } ] }
            """,
        )
        assertTrue(result.issues.isEmpty(), result.issues.joinToString())
        val doc = result.requireDocument()
        assertEquals(1800, doc.screensaver?.standby?.after)
        val off = LayoutOps.node(doc, "off") as ShortcutNode
        assertEquals(StandbyAction, off.action)

        val tooSoon = LayoutCodec.decode(docWithRoot(TILE, extra = """"screensaver": { "page": "settings_page", "standby": { "after": 10 } },"""))
        assertTrue("$.screensaver.standby.after" in tooSoon.issues.map { it.path }, tooSoon.issues.joinToString())
    }

    @Test
    fun `the screensaver page must exist and must not be the home page`() {
        val missing = LayoutCodec.decode(docWithRoot(TILE, extra = """"screensaver": { "page": "nope" },"""))
        assertEquals(listOf("$.screensaver.page"), missing.paths(Severity.Error))
        assertNull(missing.document?.screensaverPage)

        val home = LayoutCodec.decode(docWithRoot(TILE, extra = """"screensaver": { "page": "home" },"""))
        assertEquals(listOf("$.screensaver.page"), home.paths(Severity.Error))

        val tooSoon = LayoutCodec.decode(docWithRoot(TILE, extra = """"screensaver": { "page": "settings_page", "after": 5 },"""))
        assertTrue("$.screensaver.after" in tooSoon.issues.map { it.path }, tooSoon.issues.joinToString())
    }

    @Test
    fun `built-in fallback layout is valid`() {
        assertEquals(emptyList(), LayoutValidator.validate(BuiltInLayouts.fallback()))
        assertTrue(LayoutCodec.decode(LayoutCodec.encode(BuiltInLayouts.fallback())).ok)
    }
}
