package dev.owntv.launcher.data.layout

import android.content.Context

/** Files packed into the APK: layout templates, built-in looks, AI docs and the phone paste page. */
class BundledAssets(private val context: Context) {
    fun templateFiles(): List<String> = context.assets.list(TEMPLATES)?.filter { it.endsWith(".json") }?.sorted().orEmpty()

    fun template(fileName: String): String = read("$TEMPLATES/$fileName")

    fun lookFiles(): List<String> = context.assets.list(LOOKS)?.filter { it.endsWith(".json") }?.sorted().orEmpty()

    fun look(fileName: String): String = read("$LOOKS/$fileName")

    fun aiInstructions(): String = read("ai/ai-prompt.md")

    fun layoutSpec(): String = read("ai/layout-spec.md")

    fun pastePage(): String = read("web/paste.html")

    private fun read(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        const val TEMPLATES = "templates"
        const val LOOKS = "looks"
        const val DEFAULT_TEMPLATE = "default.json"
    }
}
