package dev.owntv.layout.schema

import dev.owntv.layout.model.BackgroundSerializer
import dev.owntv.layout.model.ColorValue
import dev.owntv.layout.model.Doc
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.NodeRegistry
import dev.owntv.layout.model.NodeSerializer
import dev.owntv.layout.model.PaddingSerializer
import dev.owntv.layout.model.Range
import dev.owntv.layout.model.SizeSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.floor

/** Generates a JSON Schema (2020-12) for [LayoutDocument] from the serializers and their [Doc] annotations. */
object JsonSchemaGenerator {
    const val SCHEMA_ID = "https://owntv.dev/schema/owntv-layout.v2.schema.json"
    const val COLOR_PATTERN = "^(#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})|@[A-Za-z][A-Za-z0-9_]*)$"

    private val printer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun generateText(): String = printer.encodeToString(JsonObject.serializer(), generate()) + "\n"

    fun generate(): JsonObject {
        val builder = Builder()
        val rootRef = builder.schemaFor(LayoutDocument.serializer().descriptor, Meta())
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$id", SCHEMA_ID)
            put("title", "OwnTV layout")
            put(
                "description",
                "Home screen layout for the OwnTV Android TV launcher. Generated from the Kotlin model; do not edit by hand.",
            )
            rootRef.forEach { (k, v) -> put(k, v) }
            put("\$defs", JsonObject(builder.withDefaultsKeyedByType()))
        }
    }

    private data class Meta(
        val color: Boolean = false,
        val range: ClosedFloatingPointRange<Double>? = null,
        val description: String? = null,
    )

    private class Builder {
        val defs = linkedMapOf<String, JsonElement>()

        fun schemaFor(descriptor: SerialDescriptor, meta: Meta): JsonObject {
            val base = when (descriptor.serialName.removeSuffix("?")) {
                NodeSerializer.SERIAL_NAME -> ref("Node") { nodeDef() }
                SizeSerializer.SERIAL_NAME -> buildJsonObject {
                    putJsonArray("oneOf") {
                        addJsonObject { put("type", "string"); putJsonArray("enum") { add("fill"); add("wrap") } }
                        addJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 4000) }
                    }
                }
                PaddingSerializer.SERIAL_NAME -> paddingSchema()
                BackgroundSerializer.SERIAL_NAME -> ref("Background") { backgroundDef() }
                else -> when {
                    descriptor.serialName.startsWith("kotlinx.serialization.json.JsonObject") -> buildJsonObject {
                        put("type", "object")
                        put("description", "Node properties (any property a node accepts, except type, id and children).")
                    }
                    else -> byKind(descriptor, meta)
                }
            }
            return withMeta(base, meta)
        }

        private fun byKind(descriptor: SerialDescriptor, meta: Meta): JsonObject = when (descriptor.kind) {
            PrimitiveKind.STRING -> buildJsonObject {
                put("type", "string")
                if (meta.color) put("pattern", COLOR_PATTERN)
            }
            PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                numberSchema("integer", meta)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> numberSchema("number", meta)
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(descriptor.elementNames.map(::JsonPrimitive)))
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schemaFor(descriptor.getElementDescriptor(0), meta.copy(description = null)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schemaFor(descriptor.getElementDescriptor(1), meta.copy(description = null)))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val name = simpleName(descriptor.serialName)
                ref(name) { objectSchema(listOf(descriptor), typeConst = null) }
            }
            PolymorphicKind.SEALED -> {
                val name = simpleName(descriptor.serialName)
                ref(name) { sealedDef(name, descriptor) }
            }
            else -> JsonObject(emptyMap())
        }

        private fun nodeDef(): JsonObject = buildJsonObject {
            put("description", "A layout node. The \"type\" field selects the widget or container.")
            putJsonArray("oneOf") {
                NodeRegistry.all.forEach { nodeType ->
                    val name = "Node.${nodeType.type}"
                    add(ref(name) { objectSchema(listOf(nodeType.serializer.descriptor, NodeRegistry.commonDescriptor), nodeType.type) })
                }
            }
        }

        private fun sealedDef(baseName: String, descriptor: SerialDescriptor): JsonObject {
            val subtypes = descriptor.getElementDescriptor(1)
            return buildJsonObject {
                classDoc(descriptor)?.let { put("description", it) }
                putJsonArray("oneOf") {
                    subtypes.elementDescriptors.forEach { sub ->
                        add(ref("$baseName.${sub.serialName}") { objectSchema(listOf(sub), sub.serialName) })
                    }
                }
            }
        }

        private fun objectSchema(descriptors: List<SerialDescriptor>, typeConst: String?): JsonObject {
            val required = mutableListOf<String>()
            val properties = buildJsonObject {
                if (typeConst != null) {
                    putJsonObject("type") { put("const", typeConst) }
                    required += "type"
                }
                for (descriptor in descriptors) {
                    for (i in 0 until descriptor.elementsCount) {
                        val name = descriptor.getElementName(i)
                        put(name, schemaFor(descriptor.getElementDescriptor(i), metaFor(descriptor, i)))
                        if (!descriptor.isElementOptional(i)) required += name
                    }
                }
            }
            return buildJsonObject {
                put("type", "object")
                classDoc(descriptors.first())?.let { put("description", it) }
                put("properties", properties)
                if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
                put("additionalProperties", false)
            }
        }

        private fun metaFor(descriptor: SerialDescriptor, index: Int): Meta {
            val annotations = descriptor.getElementAnnotations(index)
            return Meta(
                color = annotations.any { it is ColorValue },
                range = annotations.filterIsInstance<Range>().firstOrNull()?.let { it.min..it.max },
                description = annotations.filterIsInstance<Doc>().firstOrNull()?.value,
            )
        }

        private fun backgroundDef(): JsonObject = buildJsonObject {
            put("description", "A color string (shorthand for a solid color) or a gradient or image object.")
            putJsonArray("oneOf") {
                addJsonObject {
                    put("type", "string")
                    put("pattern", COLOR_PATTERN)
                    put("description", "A solid color: #RRGGBB, #AARRGGBB or a theme token like @surface.")
                }
                BackgroundSerializer.subtypes.forEach { (type, serializer) ->
                    if (type != "color") add(ref("Background.$type") { objectSchema(listOf(serializer.descriptor), type) })
                }
            }
        }

        /** The document's `defaults` map may only use node types as keys. */
        fun withDefaultsKeyedByType(): Map<String, JsonElement> {
            val doc = defs["LayoutDocument"] as? JsonObject ?: return defs
            val properties = doc["properties"] as? JsonObject ?: return defs
            val defaults = properties["defaults"] as? JsonObject ?: return defs
            val patched = JsonObject(defaults + ("propertyNames" to buildJsonObject {
                put("enum", JsonArray(NodeRegistry.types.map(::JsonPrimitive)))
            }))
            val result = LinkedHashMap(defs)
            result["LayoutDocument"] = JsonObject(doc + ("properties" to JsonObject(properties + ("defaults" to patched))))
            return result
        }

        /** Registers a definition once and returns a `$ref` to it. */
        private fun ref(name: String, build: () -> JsonObject): JsonObject {
            if (name !in defs) {
                defs[name] = JsonObject(emptyMap()) // placeholder breaks recursion (Node -> children -> Node)
                defs[name] = build()
            }
            return buildJsonObject { put("\$ref", "#/\$defs/$name") }
        }

        private fun numberSchema(type: String, meta: Meta) = buildJsonObject {
            put("type", type)
            meta.range?.let {
                put("minimum", it.start.clean())
                put("maximum", it.endInclusive.clean())
            }
        }

        private fun paddingSchema() = buildJsonObject {
            val dp = buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 400) }
            putJsonArray("oneOf") {
                add(dp)
                addJsonObject { put("type", "array"); put("items", dp); put("minItems", 2); put("maxItems", 2) }
                addJsonObject { put("type", "array"); put("items", dp); put("minItems", 4); put("maxItems", 4) }
            }
        }

        private fun withMeta(schema: JsonObject, meta: Meta): JsonObject {
            if (meta.description == null) return schema
            return JsonObject(linkedMapOf<String, JsonElement>("description" to JsonPrimitive(meta.description)) + schema)
        }

        private fun classDoc(descriptor: SerialDescriptor): String? =
            descriptor.annotations.filterIsInstance<Doc>().firstOrNull()?.value

        private fun simpleName(serialName: String) = serialName.removeSuffix("?").substringAfterLast('.')

        private fun Double.clean(): JsonPrimitive =
            if (this == floor(this)) JsonPrimitive(toLong()) else JsonPrimitive(this)
    }
}
