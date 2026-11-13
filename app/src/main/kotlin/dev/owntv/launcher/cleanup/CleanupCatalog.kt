package dev.owntv.launcher.cleanup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What cleanyourtv (github.com/Gathin23/cleanyourtv, MIT) learned about TV packages, generated into
 * `assets/cleanup/catalog.json` by `scripts/cleanyourtv_catalog.py`: every package one of its tested
 * TV lists turns off, and every list.
 */
@Serializable
data class CleanupCatalog(
    val source: Source,
    val packages: Map<String, PackageInfo> = emptyMap(),
    val lists: List<TvList> = emptyList(),
) {
    @Serializable
    data class Source(val repo: String, val ref: String)

    /** [off]: how many tested TV lists turn the package off; [kept]: how many keep it on purpose. */
    @Serializable
    data class PackageInfo(val off: Int, val kept: Int = 0, val note: String = "")

    /** One tested list: the TV it was made on and the packages it turns off. */
    @Serializable
    data class TvList(
        val id: String,
        val tv: String,
        val brand: String,
        val os: String = "",
        val match: String = "",
        val off: List<String> = emptyList(),
        val variant: String? = null,
    ) {
        /** "Sony BRAVIA 8 II (…)", with the variant for lists like `debloat-keep-cast.txt`. */
        val title: String get() = if (variant == null) tv else "$tv · $variant"
    }

    companion object {
        const val ASSET = "cleanup/catalog.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): CleanupCatalog = json.decodeFromString(serializer(), text)
    }
}
