package dev.owntv.layout.codec

import dev.owntv.layout.model.AppLists
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Steps that lift a layout by one schema version. Key = the version the step starts from. */
object LayoutMigrations {
    val steps: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        1 to ::v1ToV2,
        2 to ::v2ToV3,
    )

    private val textStyles = setOf("display", "headline", "title", "body", "caption")

    /**
     * Version 2 freed the name `style` for named styles:
     * - text and clock: `style` (size preset) became `textStyle`
     * - tile objects (`tile`, `itemTile`): `style` (banner/icon/card) became `shape`
     */
    private fun v1ToV2(doc: JsonObject): JsonObject {
        val pages = doc["pages"] as? JsonArray ?: return doc
        return JsonObject(doc + ("pages" to JsonArray(pages.map { page ->
            val obj = page as? JsonObject ?: return@map page
            val root = obj["root"] as? JsonObject ?: return@map page
            JsonObject(obj + ("root" to migrateNode(root)))
        })))
    }

    private fun migrateNode(node: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(node)
        val type = (node["type"] as? JsonPrimitive)?.contentOrNull
        val style = (node["style"] as? JsonPrimitive)?.contentOrNull
        if ((type == "text" || type == "clock") && style in textStyles && "textStyle" !in node) {
            result.remove("style")
            result["textStyle"] = JsonPrimitive(style)
        }
        for (key in listOf("tile", "itemTile")) {
            val tile = node[key] as? JsonObject ?: continue
            if ("style" in tile && "shape" !in tile) {
                result[key] = JsonObject(tile.mapKeys { (k, _) -> if (k == "style") "shape" else k })
            }
        }
        (node["children"] as? JsonArray)?.let { children ->
            result["children"] = JsonArray(children.map { (it as? JsonObject)?.let(::migrateNode) ?: it })
        }
        return JsonObject(result)
    }

    /**
     * Version 3 moved the apps out of the layout and onto the TV:
     * - `{"type": "packages", "packages": [...]}` sources became `{"type": "list", "list": "<node id>"}`
     * - `apps.<package>.hidden` was dropped
     * [extractAppLists] reads what this step drops, so the TV can keep it.
     */
    private fun v2ToV3(doc: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(doc)
        (doc["apps"] as? JsonObject)?.let { apps ->
            result["apps"] = JsonObject(apps.mapValues { (_, value) -> (value as? JsonObject)?.let { JsonObject(it - "hidden") } ?: value })
        }
        (doc["pages"] as? JsonArray)?.let { pages ->
            result["pages"] = JsonArray(pages.map { page ->
                val obj = page as? JsonObject ?: return@map page
                val root = obj["root"] as? JsonObject ?: return@map page
                JsonObject(obj + ("root" to packagesToLists(root)))
            })
        }
        return JsonObject(result)
    }

    private fun packagesToLists(node: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(node)
        val id = node.string("id")
        if (id != null && (node["source"] as? JsonObject)?.string("type") == "packages") {
            result["source"] = JsonObject(mapOf("type" to JsonPrimitive("list"), "list" to JsonPrimitive(listName(id))))
        }
        (node["children"] as? JsonArray)?.let { children ->
            result["children"] = JsonArray(children.map { (it as? JsonObject)?.let(::packagesToLists) ?: it })
        }
        return JsonObject(result)
    }

    /** The package lists and hidden apps of a layout older than version 3, for the TV to keep. */
    fun extractAppLists(doc: JsonObject): AppLists {
        val lists = LinkedHashMap<String, List<String>>()
        fun visit(node: JsonObject) {
            val id = node.string("id")
            val source = node["source"] as? JsonObject
            if (id != null && source?.string("type") == "packages") {
                val packages = (source["packages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                lists.getOrPut(listName(id)) { packages.distinct() }
            }
            (node["children"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::visit) }
        }
        (doc["pages"] as? JsonArray)?.forEach { page -> ((page as? JsonObject)?.get("root") as? JsonObject)?.let(::visit) }
        val hidden = (doc["apps"] as? JsonObject).orEmpty()
            .filter { (_, value) -> ((value as? JsonObject)?.get("hidden") as? JsonPrimitive)?.contentOrNull == "true" }
            .keys
        return AppLists(lists = lists, hidden = hidden)
    }

    /** List names allow letters, digits, _ and -; node ids may hold anything. */
    private fun listName(id: String): String = id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
