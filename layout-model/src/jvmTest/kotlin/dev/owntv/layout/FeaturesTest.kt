package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.BorderStyle
import dev.owntv.layout.model.ColorBackground
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.FocusLinks
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.Placeholders
import dev.owntv.layout.model.RecentSource
import dev.owntv.layout.model.ScrollCurve
import dev.owntv.layout.model.ScrollMotion
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.Size
import dev.owntv.layout.model.StyleResolver
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.TileShape
import dev.owntv.layout.validation.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeaturesTest {

    // ------------------------------------------------------------ node styling

    @Test
    fun `background accepts a color string or an object and writes colors back as strings`() {
        val doc = LayoutCodec.decode(
            docWithRoot(
                """
                { "type": "column", "id": "root", "background": "#112233", "children": [
                  { "type": "app_tile", "id": "tile", "package": "a.b",
                    "background": { "type": "gradient", "colors": ["#000000", "@primary"], "angle": 90 } }
                ] }
                """,
            ),
        ).requireDocument()
        val root = assertIs<ColumnNode>(doc.pages[0].root)
        assertEquals(ColorBackground("#112233"), root.common.background)
        assertEquals(GradientBackground(listOf("#000000", "@primary"), 90), root.children[0].common.background)
        val text = LayoutCodec.encode(doc)
        assertTrue(text.contains("\"background\": \"#112233\""), text)
    }

    @Test
    fun `common properties sit flat in the node and survive a round trip`() {
        val json = docWithRoot(
            """
            { "type": "row", "id": "root", "width": "fill", "height": 120, "opacity": 0.5, "shadow": 8,
              "border": { "width": 3, "color": "@focus" }, "revealOnFocus": true,
              "focus": { "down": "tile" }, "children": [ $TILE ] }
            """,
        )
        val doc = LayoutCodec.decode(json).requireDocument()
        val common = doc.pages[0].root.common
        assertEquals(Size.Fill, common.width)
        assertEquals(Size.Fixed(120), common.height)
        assertEquals(0.5f, common.opacity)
        assertEquals(8, common.shadow)
        assertEquals(BorderStyle(3, "@focus"), common.border)
        assertEquals(FocusLinks(down = "tile"), common.focus)
        assertTrue(common.revealOnFocus)
        assertEquals(doc, LayoutCodec.decode(LayoutCodec.encode(doc)).requireDocument())
    }

    // ------------------------------------------------------------ styles and defaults

    private val styled = """
        {
          "schemaVersion": 3,
          "styles": {
            "base": { "spacing": 40, "tile": { "shape": "icon", "size": "large" } },
            "fancy": { "style": "base", "tile": { "size": "small" }, "textStyle": "headline" }
          },
          "defaults": { "app_row": { "title": "Default title", "spacing": 10 } },
          "pages": [ { "id": "home", "root": { "type": "column", "id": "root", "children": [
            { "type": "app_row", "id": "plain", "source": { "type": "all" } },
            { "type": "app_row", "id": "fancy_row", "style": "fancy", "source": { "type": "all" }, "tile": { "showLabel": "never" } },
            { "type": "text", "id": "t", "style": "fancy", "text": "Hi" }
          ] } }, { "id": "settings_page", "root": $SETTINGS_LINK } ]
        }
    """.trimIndent()

    @Test
    fun `defaults, style chains and node values combine in that order`() {
        val result = LayoutCodec.decode(styled)
        assertTrue(result.ok && result.issues.isEmpty(), result.issues.joinToString("\n"))
        val resolved = StyleResolver.resolve(result.document!!)
        val children = (resolved.pages[0].root as ColumnNode).children

        val plain = assertIs<AppRowNode>(children[0])
        assertEquals("Default title", plain.title)
        assertEquals(10, plain.spacing)

        val fancy = assertIs<AppRowNode>(children[1])
        assertEquals(40, fancy.spacing) // style beats type default
        assertEquals(TileShape.Icon, fancy.tile.shape) // from "base"
        assertEquals(dev.owntv.layout.model.TileSize.Small, fancy.tile.size) // "fancy" beats "base"
        assertEquals(dev.owntv.layout.model.LabelMode.Never, fancy.tile.showLabel) // node beats styles

        val text = assertIs<TextNode>(children[2])
        assertEquals(TextStyleName.Headline, text.textStyle) // keys the node type lacks are skipped
    }

    @Test
    fun `resolving the same document again reuses the result, an equal copy is resolved anew`() {
        val doc = LayoutCodec.decode(styled).requireDocument()
        val resolved = StyleResolver.resolve(doc)
        assertSame(resolved, StyleResolver.resolve(doc))
        val copy = doc.copy()
        val again = StyleResolver.resolve(copy)
        assertNotSame(resolved, again)
        assertEquals(resolved, again)
    }

    @Test
    fun `the stored document keeps style references`() {
        val doc = LayoutCodec.decode(styled).requireDocument()
        val encoded = LayoutCodec.encode(doc)
        assertTrue(encoded.contains("\"style\": \"fancy\""), encoded)
        assertTrue(!encoded.contains("Default title\",\n      \"id\""), encoded)
    }

    @Test
    fun `unknown styles, cycles and bad style keys are reported`() {
        val result = LayoutCodec.decode(
            """
            { "schemaVersion": 3,
              "styles": { "a": { "style": "b" }, "b": { "style": "a" }, "c": { "id": "x", "nonsense": 1 } },
              "defaults": { "not_a_type": { "spacing": 4 } },
              "pages": [ { "id": "home", "root": { "type": "app_tile", "id": "t", "package": "a.b", "style": "missing" } }, { "id": "settings_page", "root": $SETTINGS_LINK } ] }
            """,
        )
        assertEquals(setOf("$.styles.c.id"), result.paths(Severity.Error).toSet())
        assertEquals(setOf("$.styles.c.nonsense", "$.defaults.not_a_type"), result.paths(Severity.Warning).toSet())

        val semantic = LayoutCodec.decode(
            """
            { "schemaVersion": 3,
              "styles": { "a": { "style": "b" }, "b": { "style": "a" } },
              "pages": [ { "id": "home", "root": { "type": "app_tile", "id": "t", "package": "a.b", "style": "missing" } }, { "id": "settings_page", "root": $SETTINGS_LINK } ] }
            """,
        )
        assertNull(semantic.document)
        assertEquals(setOf("$.styles.a.style", "$.styles.b.style", "$.pages[0].root.style"), semantic.paths(Severity.Error).toSet())
    }

    // ------------------------------------------------------------ placeholders, focus links, hero

    @Test
    fun `placeholders parse with arguments and escapes`() {
        val parts = Placeholders.parse("{{literal}} {greeting}, it is {time:h:mm a}")
        assertEquals(
            listOf(
                Placeholders.Part.Literal("{literal} "),
                Placeholders.Part.Token("greeting", null),
                Placeholders.Part.Literal(", it is "),
                Placeholders.Part.Token("time", "h:mm a"),
            ),
            parts,
        )
        assertEquals("Good morning", Placeholders.greeting(8))
        assertEquals("Good night", Placeholders.greeting(2))
    }

    @Test
    fun `unknown placeholders warn and bad date patterns fail`() {
        val result = LayoutCodec.decode(
            docWithRoot("""{ "type": "column", "id": "root", "children": [ $TILE, { "type": "text", "id": "t", "text": "{weather} {date:bbb}" } ] }"""),
        )
        assertEquals(listOf("$.pages[0].root.children[1].text"), result.paths(Severity.Warning))
        assertEquals(listOf("$.pages[0].root.children[1].text"), result.paths(Severity.Error))
    }

    @Test
    fun `focus links and hero targets must exist on the page`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """
                { "type": "column", "id": "root", "children": [
                  { "type": "hero", "id": "h", "target": "nowhere" },
                  { "type": "app_tile", "id": "tile", "package": "a.b", "focus": { "up": "h", "down": "ghost" } }
                ] }
                """,
            ),
        )
        assertEquals(
            setOf("$.pages[0].root.children[0].target", "$.pages[0].root.children[1].focus.down"),
            result.paths(Severity.Error).toSet(),
        )
    }

    @Test
    fun `showWhenFocused must point at a node on the page`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """
                { "type": "column", "id": "root", "children": [ $TILE,
                  { "type": "text", "id": "name", "text": "{focused} {description}", "showWhenFocused": "root", "keepSpace": true },
                  { "type": "text", "id": "lost", "text": "x", "showWhenFocused": "ghost" },
                  { "type": "text", "id": "stray", "text": "x", "keepSpace": true }
                ] }
                """,
            ),
        )
        assertEquals(listOf("$.pages[0].root.children[2].showWhenFocused"), result.paths(Severity.Error))
        assertEquals(listOf("$.pages[0].root.children[3].keepSpace"), result.paths(Severity.Warning))
    }

    @Test
    fun `icons, inputs, mobile apps and the network widget decode`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """
                { "type": "column", "id": "root", "unfocusedOpacity": 0.5, "children": [
                  { "type": "text", "id": "title", "text": "Inputs", "icon": "input" },
                  { "type": "text", "id": "emoji", "text": "Movies", "icon": "🎬" },
                  { "type": "text", "id": "typo", "text": "Oops", "icon": "hdmii" },
                  { "type": "shortcut", "id": "hdmi", "label": "HDMI 1", "icon": "hdmi", "focusColor": "@nope", "action": { "type": "open_input", "input": "hdmi1" } },
                  { "type": "app_row", "id": "mobile", "titleIcon": "smartphone", "source": { "type": "category", "category": "mobile" } },
                  { "type": "network", "id": "net", "size": 20 }
                ] }
                """,
            ),
        )
        assertEquals(listOf("$.pages[0].root.children[2].icon"), result.paths(Severity.Warning))
        assertEquals(listOf("$.pages[0].root.children[3].focusColor"), result.paths(Severity.Error))
        val bad = LayoutCodec.decode(docWithRoot("""{ "type": "shortcut", "id": "s", "label": "x", "action": { "type": "open_input", "input": "hdmi9" } }"""))
        assertEquals(listOf("$.pages[0].root.action.input"), bad.paths(Severity.Error))
    }

    @Test
    fun `scroll motion decodes on the theme and on scroll containers`() {
        val result = LayoutCodec.decode(
            docWithRoot(
                """{ "type": "column", "id": "root", "scroll": true, "scrollMotion": { "curve": "ease_out", "duration": 350 }, "children": [ $TILE ] }""",
                extra = """ "theme": { "scrollMotion": { "duration": 90 } }, """,
            ),
        )
        assertTrue(result.ok, result.issues.joinToString("\n"))
        val doc = result.requireDocument()
        assertEquals(ScrollMotion(ScrollCurve.Spring, 90), doc.theme.scrollMotion)
        assertEquals(ScrollMotion(ScrollCurve.EaseOut, 350), (doc.pages[0].root as ColumnNode).scrollMotion)
        val bad = LayoutCodec.decode(docWithRoot("""{ "type": "row", "id": "root", "scrollMotion": { "curve": "bouncy", "duration": 5000 }, "children": [ $TILE ] }"""))
        assertEquals(setOf("$.pages[0].root.scrollMotion.curve", "$.pages[0].root.scrollMotion.duration"), bad.paths(Severity.Error).toSet())
    }

    @Test
    fun `hero slides need content and folders, recent sources and app overrides decode`() {
        val result = LayoutCodec.decode(
            """
            { "schemaVersion": 2,
              "apps": { "com.netflix.ninja": { "label": "Netflix", "backdrop": "https://example.com/n.jpg", "hidden": true },
                        "not a package": { "banner": "ftp://nope" } },
              "pages": [ { "id": "home", "root": { "type": "column", "id": "root", "children": [
                { "type": "hero", "id": "h", "follow": "slides" },
                { "type": "folder", "id": "f", "label": "Games", "source": { "type": "category", "category": "games" } },
                { "type": "app_row", "id": "r", "source": { "type": "recent" }, "skip": 1 }
              ] } }, { "id": "settings_page", "root": $SETTINGS_LINK } ] }
            """,
        )
        assertEquals(setOf("$.apps.not a package.banner"), result.paths(Severity.Error).toSet())
        assertEquals(setOf("$.apps.not a package", "$.pages[0].root.children[0].slides"), result.paths(Severity.Warning).toSet())

        val ok = LayoutCodec.decode(
            docWithRoot("""{ "type": "app_row", "id": "r", "source": { "type": "recent", "exclude": ["a.b"] }, "skip": 2 }"""),
        ).requireDocument()
        val row = assertIs<AppRowNode>(ok.pages[0].root)
        assertEquals(RecentSource(listOf("a.b")), row.source)
        assertEquals(2, row.skip)
    }

    // ------------------------------------------------------------ migration

    @Test
    fun `version 1 layouts are upgraded to the renamed properties`() {
        val result = LayoutCodec.decode(
            """
            { "schemaVersion": 1, "pages": [ { "id": "home", "root": { "type": "column", "id": "root", "children": [
              { "type": "text", "id": "t", "text": "Hi", "style": "headline" },
              { "type": "clock", "id": "c", "style": "title" },
              { "type": "app_row", "id": "r", "source": { "type": "all" }, "tile": { "style": "icon", "size": "small" } }
            ] } }, { "id": "settings_page", "root": $SETTINGS_LINK } ] }
            """,
        )
        val doc = result.requireDocument()
        assertEquals(listOf(Severity.Info), result.issues.map { it.severity })
        assertEquals(3, doc.schemaVersion)
        val children = (doc.pages[0].root as ColumnNode).children
        assertEquals(TextStyleName.Headline, assertIs<TextNode>(children[0]).textStyle)
        assertEquals(TextStyleName.Title, assertIs<dev.owntv.layout.model.ClockNode>(children[1]).textStyle)
        assertEquals(TileShape.Icon, assertIs<AppRowNode>(children[2]).tile.shape)
        assertNull(children[0].common.style)
    }

    @Test
    fun `shortcut keeps hero text`() {
        val doc = LayoutCodec.decode(
            docWithRoot("""{ "type": "shortcut", "id": "s", "label": "News", "description": "Daily news", "backdrop": "asset:news.jpg", "action": { "type": "open_app_drawer" } }"""),
        ).requireDocument()
        val shortcut = assertIs<ShortcutNode>(doc.pages[0].root)
        assertEquals("Daily news", shortcut.description)
        assertEquals("asset:news.jpg", shortcut.backdrop)
    }
}
