package dev.owntv.web

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import dev.owntv.launcher.ui.render.FontFamilies
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The browser has no system fonts, so the preview loads the faces Android TV uses (Roboto, Noto
 * Serif, Noto Sans Mono, Dancing Script, Noto Color Emoji) from jsDelivr at pinned versions.
 */
object WebFonts {
    private const val CDN = "https://cdn.jsdelivr.net/npm/@expo-google-fonts"
    private val weights = listOf(FontWeight.Normal to "400Regular", FontWeight.Medium to "500Medium", FontWeight.Bold to "700Bold")

    private suspend fun family(pkg: String, file: String): FontFamily = coroutineScope {
        weights.map { (weight, style) ->
            async { Font("${file}_$style", fetchBytes("$CDN/$pkg/$style/${file}_$style.ttf"), weight) }
        }.awaitAll().let(::FontFamily)
    }

    suspend fun sans(): FontFamily = family("roboto@0.4.3", "Roboto")

    /** The other families; until they arrive, text that uses them falls back to [sans]. */
    suspend fun all(sans: FontFamily): FontFamilies = coroutineScope {
        val serif = async { runCatching { family("noto-serif@0.4.2", "NotoSerif") }.getOrDefault(sans) }
        val mono = async { runCatching { family("noto-sans-mono@0.4.2", "NotoSansMono") }.getOrDefault(sans) }
        val cursive = async { runCatching { family("dancing-script@0.4.2", "DancingScript") }.getOrDefault(sans) }
        FontFamilies(sans = sans, serif = serif.await(), mono = mono.await(), cursive = cursive.await())
    }

    /** About 25 MB; preloaded as the fallback for emoji in any text. */
    suspend fun emoji(): FontFamily =
        FontFamily(Font("NotoColorEmoji", fetchBytes("$CDN/noto-color-emoji@0.4.7/400Regular/NotoColorEmoji_400Regular.ttf")))
}
