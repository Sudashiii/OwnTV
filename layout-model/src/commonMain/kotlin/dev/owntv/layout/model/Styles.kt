package dev.owntv.layout.model

import dev.owntv.layout.codec.LayoutCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

/**
 * Applies `defaults` (per node type) and named `styles` to nodes. Precedence, lowest first:
 * type default, then the style chain (a style's own `"style"` first), then the node itself.
 * Objects such as `tile` are merged key by key. Keys a node type does not have are skipped,
 * so one style can serve several node types.
 */
object StyleResolver {
    /** Keys that never come from a style. */
    private val reserved = setOf("type", "id", "children", "style")

    const val MAX_CHAIN = 8

    /** Typed objects that are replaced, not merged: merging a gradient into an image makes no sense. */
    private val replacedWhole = setOf("source", "action", "background")

    /**
     * The last document resolved, by identity, and its result. Validation (on a background thread) and
     * every view of the same layout resolve it again; this keeps the work off the main thread.
     */
    @Volatile
    private var last: Pair<LayoutDocument, LayoutDocument>? = null

    fun resolve(doc: LayoutDocument): LayoutDocument {
        if (doc.styles.isEmpty() && doc.defaults.isEmpty()) return doc
        last?.let { (from, resolved) -> if (from === doc) return resolved }
        val json = LayoutCodec.json.encodeToJsonElement(LayoutDocument.serializer(), doc).jsonObject
        return LayoutCodec.json.decodeFromJsonElement(LayoutDocument.serializer(), expand(json)).also { last = doc to it }
    }

    /** Expands styles in a raw layout object. Unknown style names and cycles are skipped here; the validator reports them. */
    fun expand(doc: JsonObject): JsonObject {
        val styles = doc["styles"] as? JsonObject ?: JsonObject(emptyMap())
        val defaults = doc["defaults"] as? JsonObject ?: JsonObject(emptyMap())
        if (styles.isEmpty() && defaults.isEmpty()) return doc
        val pages = (doc["pages"] as? JsonArray ?: return doc).map { page ->
            val obj = page as? JsonObject ?: return@map page
            val root = obj["root"] as? JsonObject ?: return@map page
            JsonObject(obj + ("root" to expandNode(root, styles, defaults)))
        }
        return JsonObject(doc + ("pages" to JsonArray(pages)))
    }

    private fun expandNode(node: JsonObject, styles: JsonObject, defaults: JsonObject): JsonObject {
        val type = (node["type"] as? JsonPrimitive)?.contentOrNull
        val nodeType = NodeRegistry.forType(type) ?: return node
        var base = JsonObject(emptyMap())
        (defaults[type] as? JsonObject)?.let { base = deepMerge(base, it) }
        styleChain((node["style"] as? JsonPrimitive)?.contentOrNull, styles).forEach { base = deepMerge(base, it) }
        val inherited = JsonObject(base.filterKeys { it in nodeType.allKeys && it !in reserved })
        var merged = deepMerge(inherited, node)
        (merged["children"] as? JsonArray)?.let { children ->
            merged = JsonObject(merged + ("children" to JsonArray(children.map { child ->
                (child as? JsonObject)?.let { expandNode(it, styles, defaults) } ?: child
            })))
        }
        return merged
    }

    /** Style objects from the most basic ancestor to [name] itself. */
    fun styleChain(name: String?, styles: JsonObject): List<JsonObject> {
        val chain = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var current = name
        while (current != null && seen.add(current) && chain.size < MAX_CHAIN) {
            val style = styles[current] as? JsonObject ?: break
            chain += style
            current = (style["style"] as? JsonPrimitive)?.contentOrNull
        }
        return chain.asReversed()
    }

    /** Merges [over] onto [base]; nested objects merge recursively, everything else is replaced. */
    fun deepMerge(base: JsonObject, over: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in over) {
            val existing = result[key]
            result[key] = if (existing is JsonObject && value is JsonObject && key !in replacedWhole) {
                deepMerge(existing, value)
            } else {
                value
            }
        }
        return JsonObject(result)
    }
}

/** `{placeholders}` inside text nodes. */
object Placeholders {
    sealed interface Part {
        data class Literal(val text: String) : Part
        data class Token(val name: String, val argument: String?) : Part
    }

    val names = listOf("greeting", "time", "date", "focused", "description", "page")

    fun parse(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        val literal = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '{' && text.startsWith("{{", i) -> { literal.append('{'); i += 2 }
                c == '}' && text.startsWith("}}", i) -> { literal.append('}'); i += 2 }
                c == '{' -> {
                    val end = text.indexOf('}', i)
                    if (end < 0) { literal.append(text, i, text.length); i = text.length; continue }
                    if (literal.isNotEmpty()) { parts += Part.Literal(literal.toString()); literal.clear() }
                    val body = text.substring(i + 1, end).trim()
                    val name = body.substringBefore(':').trim()
                    val argument = if (':' in body) body.substringAfter(':').trim() else null
                    parts += Part.Token(name, argument)
                    i = end + 1
                }
                else -> { literal.append(c); i++ }
            }
        }
        if (literal.isNotEmpty()) parts += Part.Literal(literal.toString())
        return parts
    }

    fun tokens(text: String): List<Part.Token> = parse(text).filterIsInstance<Part.Token>()

    /** True when the text changes over time or with focus. */
    fun isDynamic(text: String): Boolean = tokens(text).isNotEmpty()

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Good night"
    }
}

/** Built-in "looks": full layouts that can also lend only their visual style to another layout. */
object LayoutThemes {
    /**
     * Gives [current] the look of [look] while keeping its pages: theme, background, type defaults
     * and styles. On name clashes the look wins; custom color tokens only the current layout
     * defines are kept so its nodes still resolve.
     */
    fun applyLook(current: LayoutDocument, look: LayoutDocument): LayoutDocument = current.copy(
        theme = look.theme.copy(
            colors = look.theme.colors.copy(custom = current.theme.colors.custom + look.theme.colors.custom),
        ),
        background = look.background,
        defaults = look.defaults,
        styles = current.styles + look.styles,
    )
}
