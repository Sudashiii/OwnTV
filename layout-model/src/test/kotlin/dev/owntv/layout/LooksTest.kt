package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.LayoutThemes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LooksTest {

    private val lookFiles: List<File> = File(rootDir, "looks").listFiles { f -> f.extension == "json" }!!.sorted()

    @Test
    fun `every built-in look is valid and warning free`() {
        assertTrue(lookFiles.size >= 6, "Expected the built-in looks in looks/")
        lookFiles.forEach { file ->
            val result = LayoutCodec.decode(file.readText())
            assertTrue(result.ok && result.issues.isEmpty(), "${file.name}:\n${result.issues.joinToString("\n")}")
            assertTrue(result.document!!.description != null, "${file.name} needs a description")
        }
    }

    @Test
    fun `applying only a look's style keeps the pages`() {
        val mine = LayoutCodec.decode(File(examplesDir, "kids.json").readText()).requireDocument()
        val look = LayoutCodec.decode(File(rootDir, "looks/apple-tv.json").readText()).requireDocument()
        val styled = LayoutThemes.applyLook(mine, look)
        assertEquals(mine.pages, styled.pages)
        assertEquals(look.theme.focus, styled.theme.focus)
        assertEquals(mine.theme.colors.custom["pink"], styled.theme.colors.custom["pink"])
        assertEquals(look.defaults, styled.defaults)
        val result = LayoutCodec.decode(LayoutCodec.encode(styled))
        assertTrue(result.ok, result.issues.joinToString("\n"))
    }
}
