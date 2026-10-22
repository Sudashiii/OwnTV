package dev.owntv.launcher.data.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.LayoutDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltInTrackingTest {
    private val root = File(System.getProperty("owntv.rootDir") ?: "..")

    private fun doc(name: String, label: String = "Settings"): LayoutDocument = LayoutCodec.decode(
        """
        {"schemaVersion":3,"name":"$name","pages":[{"id":"home","root":{"type":"column","id":"root","children":[
          {"type":"shortcut","id":"settings_link","label":"$label","icon":"settings","action":{"type":"open_launcher_settings"}}
        ]}}]}
        """.trimIndent(),
    ).document!!

    private val ref = BuiltInRef(BuiltInKind.Look, "roku.json")

    /** The user has the old version of the look, stored as `applied`. */
    private fun origin(applied: LayoutDocument, dismissedHash: String? = null) =
        BuiltInOrigin(ref, BuiltInTracking.hash(LayoutCodec.encode(applied)), dismissedHash)

    @Test
    fun `offers the new version when the stored layout is untouched`() {
        val old = doc("Roku")
        val new = doc("Roku", label = "Launcher settings")
        val update = BuiltInTracking.update(origin(old), BuiltInTracking.hash(LayoutCodec.encode(old)), old, new)
        assertNotNull(update)
        assertEquals(ref, update.ref)
        assertEquals("Roku", update.name)
        assertEquals(BuiltInTracking.documentHash(new), update.hash)
        assertEquals(false, update.dismissed)
    }

    @Test
    fun `offers nothing when the built-in layout did not change`() {
        val old = doc("Roku")
        assertNull(BuiltInTracking.update(origin(old), BuiltInTracking.hash(LayoutCodec.encode(old)), old, doc("Roku")))
    }

    @Test
    fun `offers nothing once the user changed the layout`() {
        val old = doc("Roku")
        val edited = doc("Roku", label = "My settings")
        val editedFileHash = BuiltInTracking.hash(LayoutCodec.encode(edited))
        assertNull(BuiltInTracking.update(origin(old), editedFileHash, edited, doc("Roku", label = "Launcher settings")))
    }

    @Test
    fun `offers nothing without a link to a built-in layout or when the APK no longer ships it`() {
        val old = doc("Roku")
        val fileHash = BuiltInTracking.hash(LayoutCodec.encode(old))
        assertNull(BuiltInTracking.update(null, fileHash, old, doc("Roku", label = "Launcher settings")))
        assertNull(BuiltInTracking.update(origin(old), fileHash, old, null))
    }

    @Test
    fun `not now is remembered for that version only`() {
        val old = doc("Roku")
        val v2 = doc("Roku", label = "Two")
        val v3 = doc("Roku", label = "Three")
        val fileHash = BuiltInTracking.hash(LayoutCodec.encode(old))
        val dismissedV2 = origin(old, dismissedHash = BuiltInTracking.documentHash(v2))
        assertEquals(true, BuiltInTracking.update(dismissedV2, fileHash, old, v2)?.dismissed)
        assertEquals(false, BuiltInTracking.update(dismissedV2, fileHash, old, v3)?.dismissed)
    }

    @Test
    fun `adopts a stored layout that is identical to a built-in one, looks first`() {
        val look = doc("Same")
        val other = doc("Other", label = "Else")
        val candidates = listOf(
            BuiltInRef(BuiltInKind.Template, "same.json") to look,
            BuiltInRef(BuiltInKind.Look, "same.json") to look,
            BuiltInRef(BuiltInKind.Look, "other.json") to other,
        )
        val adopted = BuiltInTracking.adopt(look, candidates, "abc")
        assertEquals(BuiltInOrigin(BuiltInRef(BuiltInKind.Look, "same.json"), "abc"), adopted)
        assertNull(BuiltInTracking.adopt(doc("Mine", label = "Mine"), candidates, "abc"))
    }

    @Test
    fun `origin file round trips and a damaged file means no link`() {
        val full = BuiltInOrigin(BuiltInRef(BuiltInKind.Template, "kids.json"), "h1", "h2")
        assertEquals(full, BuiltInOriginFile.decode(BuiltInOriginFile.encode(full)))
        val noDismiss = full.copy(dismissedHash = null)
        assertEquals(noDismiss, BuiltInOriginFile.decode(BuiltInOriginFile.encode(noDismiss)))
        assertNull(BuiltInOriginFile.decode(BuiltInOriginFile.encode(null)))
        assertNull(BuiltInOriginFile.decode("not json"))
        assertNull(BuiltInOriginFile.decode("""{"kind":"look"}"""))
        assertNull(BuiltInOriginFile.decode("""{"kind":"other","file":"a.json","fileHash":"x"}"""))
    }

    @Test
    fun `every built-in layout reads back as the same content it was stored with`() {
        val files = File(root, "looks").listFiles { f -> f.extension == "json" }.orEmpty().toList() +
            File(root, "examples").listFiles { f -> f.extension == "json" }.orEmpty().toList()
        assertTrue(files.isNotEmpty(), "found no looks or examples under ${root.absolutePath}")
        for (file in files) {
            val doc = LayoutCodec.decode(file.readText()).document!!
            val stored = LayoutCodec.encode(doc)
            val reread = LayoutCodec.decode(stored).document!!
            assertEquals(stored, LayoutCodec.encode(reread), "${file.name} must encode the same after a round trip")
            assertEquals(BuiltInTracking.documentHash(doc), BuiltInTracking.documentHash(reread), file.name)
        }
    }

    @Test
    fun `different layouts have different hashes`() {
        assertNotEquals(BuiltInTracking.documentHash(doc("A")), BuiltInTracking.documentHash(doc("B")))
    }
}
