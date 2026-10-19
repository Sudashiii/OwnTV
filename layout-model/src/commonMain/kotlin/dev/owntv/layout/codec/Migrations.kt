package dev.owntv.layout.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Steps that lift a layout by one schema version. Key = the version the step starts from. */
object LayoutMigrations {
    val steps: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        1 to ::v1ToV2,
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
}
