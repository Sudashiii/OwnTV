package dev.owntv.layout.validation

import dev.owntv.layout.model.BackgroundSerializer
import dev.owntv.layout.model.ColorValue
import dev.owntv.layout.model.Colors
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
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/** Extra constraints attached to a property through annotations. */
internal data class FieldMeta(
    val color: Boolean = false,
    val range: ClosedFloatingPointRange<Double>? = null,
)

/**
 * Checks raw JSON against the model's serial descriptors before decoding, so one pass reports
 * every missing field, wrong type, unknown enum value and unknown property with its JSON path.
 */
internal class StructureValidator(private val out: IssueCollector) {

    /** Checks a whole layout document, including its styles and type defaults. */
    fun checkDocument(descriptor: SerialDescriptor, doc: JsonObject) {
        check(descriptor, doc, "$")
        (doc["styles"] as? JsonObject)?.forEach { (name, style) ->
            (style as? JsonObject)?.let { checkStyle(it, "$.styles.$name", allowedTypes = null) }
        }
        (doc["defaults"] as? JsonObject)?.forEach { (type, style) ->
            val nodeType = NodeRegistry.forType(type)
            if (nodeType == null) {
                out.warning("$.defaults.$type", "\"$type\" is not a node type, so these defaults are never used. Node types: ${NodeRegistry.types.joinToString(", ")}.")
            } else {
                (style as? JsonObject)?.let { checkStyle(it, "$.defaults.$type", allowedTypes = setOf(type)) }
            }
        }
    }

    fun check(descriptor: SerialDescriptor, element: JsonElement, path: String, meta: FieldMeta = FieldMeta()) {
        if (element is JsonNull) {
            if (!descriptor.isNullable) out.error(path, "Must not be null.")
            return
        }
        when (descriptor.serialName.removeSuffix("?")) {
            NodeSerializer.SERIAL_NAME -> return checkNode(element, path)
            BackgroundSerializer.SERIAL_NAME -> return checkBackground(element, path)
            SizeSerializer.SERIAL_NAME -> return checkSize(element, path)
            PaddingSerializer.SERIAL_NAME -> return checkPadding(element, path)
        }
        when (descriptor.kind) {
            PrimitiveKind.STRING -> checkString(element, path, meta)
            PrimitiveKind.BOOLEAN -> checkBoolean(element, path)
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                checkNumber(element, path, meta, integer = true)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> checkNumber(element, path, meta, integer = false)
            SerialKind.ENUM -> checkEnum(descriptor, element, path)
            StructureKind.LIST -> {
                val array = element as? JsonArray ?: return out.error(path, "Must be an array [...].")
                array.forEachIndexed { i, item -> check(descriptor.getElementDescriptor(0), item, "$path[$i]", meta) }
            }
            StructureKind.MAP -> {
                val obj = element as? JsonObject ?: return out.error(path, "Must be an object {...}.")
                val valueDescriptor = descriptor.getElementDescriptor(1)
                // Styles and defaults hold free-form node properties; checkDocument looks at them.
                if (valueDescriptor.serialName.startsWith("kotlinx.serialization.json.JsonObject")) {
                    obj.forEach { (key, value) -> if (value !is JsonObject) out.error("$path.$key", "Must be an object {...}.") }
                    return
                }
                obj.forEach { (key, value) -> check(valueDescriptor, value, "$path.$key", meta) }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return out.error(path, "Must be an object {...}.")
                checkObject(listOf(descriptor), obj, path, skip = emptySet())
            }
            PolymorphicKind.SEALED -> checkSealed(descriptor, element, path)
            else -> Unit
        }
    }

    /** Checks [obj] against the union of [descriptors] (a node uses its own descriptor plus the common one). */
    private fun checkObject(descriptors: List<SerialDescriptor>, obj: JsonObject, path: String, skip: Set<String>, requireAll: Boolean = true) {
        for ((key, value) in obj) {
            if (key in skip) continue
            val match = descriptors.firstNotNullOfOrNull { d -> d.getElementIndex(key).takeIf { it != CompositeDecoder.UNKNOWN_NAME }?.let { d to it } }
            if (match == null) {
                val allowed = descriptors.flatMap { it.elementNames }.joinToString(", ")
                out.warning("$path.$key", "Unknown property \"$key\" is ignored. Allowed here: $allowed.")
                continue
            }
            val (descriptor, index) = match
            check(descriptor.getElementDescriptor(index), value, "$path.$key", metaFor(descriptor, index))
        }
        if (!requireAll) return
        for (descriptor in descriptors) {
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                if (!descriptor.isElementOptional(i) && name !in obj) {
                    out.error(path, "Missing required property \"$name\".")
                }
            }
        }
    }

    private fun checkSealed(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        val obj = element as? JsonObject ?: return out.error(path, "Must be an object with a \"type\".")
        val subtypes = descriptor.getElementDescriptor(1)
        val allowed = subtypes.elementNames.joinToString(", ")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return out.error(path, "Missing \"type\". One of: $allowed.")
        val index = subtypes.getElementIndex(type)
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            return out.error("$path.type", "Unknown type \"$type\". One of: $allowed.")
        }
        checkObject(listOf(subtypes.getElementDescriptor(index)), obj, path, skip = setOf("type"))
    }

    private fun checkBackground(element: JsonElement, path: String) {
        if (element is JsonPrimitive && element.isString) return checkString(element, path, FieldMeta(color = true))
        val obj = element as? JsonObject
            ?: return out.error(path, "Must be a color string like \"#202020\" or an object with a \"type\".")
        val allowed = BackgroundSerializer.subtypes.keys.joinToString(", ")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return out.error(path, "Missing \"type\". One of: $allowed.")
        val serializer = BackgroundSerializer.subtypes[type]
            ?: return out.error("$path.type", "Unknown background type \"$type\". One of: $allowed.")
        checkObject(listOf(serializer.descriptor), obj, path, skip = setOf("type"))
    }

    private fun checkNode(element: JsonElement, path: String) {
        val obj = element as? JsonObject ?: return out.error(path, "A node must be an object with a \"type\".")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return out.error(path, "Missing \"type\". One of: ${NodeRegistry.types.joinToString(", ")}.")
        val nodeType = NodeRegistry.forType(type)
        if (nodeType == null) {
            out.warning(
                "$path.type",
                "Unknown widget type \"$type\"; it is shown as a placeholder. Known types: ${NodeRegistry.types.joinToString(", ")}.",
            )
            return
        }
        checkObject(listOf(nodeType.serializer.descriptor, NodeRegistry.commonDescriptor), obj, path, skip = setOf("type"))
    }

    /** A style may hold any node property; each value is checked against the first node type that has that key. */
    private fun checkStyle(style: JsonObject, path: String, allowedTypes: Set<String>?) {
        val types = NodeRegistry.all.filter { allowedTypes == null || it.type in allowedTypes }
        for ((key, value) in style) {
            when (key) {
                "type", "id", "children" -> {
                    out.error("$path.$key", "\"$key\" cannot be set by a style or default.")
                    continue
                }
                "style" -> {
                    if (allowedTypes != null) out.error("$path.style", "Defaults cannot refer to a style; set \"style\" on the node instead.")
                    else if ((value as? JsonPrimitive)?.isString != true) out.error("$path.style", "Must be the name of another style.")
                    continue
                }
            }
            val descriptor = (types.map { it.serializer.descriptor } + NodeRegistry.commonDescriptor)
                .firstOrNull { it.getElementIndex(key) != CompositeDecoder.UNKNOWN_NAME }
            if (descriptor == null) {
                out.warning("$path.$key", "No node type has a property \"$key\", so it is never used.")
                continue
            }
            val index = descriptor.getElementIndex(key)
            check(descriptor.getElementDescriptor(index), value, "$path.$key", metaFor(descriptor, index))
        }
    }

    private fun checkString(element: JsonElement, path: String, meta: FieldMeta) {
        val p = element as? JsonPrimitive
        if (p == null || !p.isString) return out.error(path, "Must be a string in quotes.")
        if (meta.color && !Colors.isColor(p.content)) {
            out.error(path, "\"${p.content}\" is not a color. Use #RRGGBB, #AARRGGBB or a theme token like @primary.")
        }
    }

    private fun checkBoolean(element: JsonElement, path: String) {
        val p = element as? JsonPrimitive
        if (p == null || p.isString || p.booleanOrNull == null) out.error(path, "Must be true or false.")
    }

    private fun checkNumber(element: JsonElement, path: String, meta: FieldMeta, integer: Boolean) {
        val p = element as? JsonPrimitive
        val value = p?.takeUnless { it.isString }?.doubleOrNull
            ?: return out.error(path, if (integer) "Must be a whole number." else "Must be a number.")
        if (integer && value != floor(value)) return out.error(path, "Must be a whole number, got $value.")
        meta.range?.let { range ->
            if (value !in range) out.error(path, "Must be between ${range.start.clean()} and ${range.endInclusive.clean()}, got ${value.clean()}.")
        }
    }

    private fun checkEnum(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val allowed = descriptor.elementNames.toList()
        if (value == null || value !in allowed) {
            out.error(path, "Must be one of: ${allowed.joinToString(", ")}${value?.let { " (got \"$it\")" }.orEmpty()}.")
        }
    }

    private fun checkSize(element: JsonElement, path: String) {
        val p = element as? JsonPrimitive
        val ok = when {
            p == null -> false
            p.isString -> p.content == "fill" || p.content == "wrap"
            else -> p.doubleOrNull?.let { it in 0.0..4000.0 } == true
        }
        if (!ok) out.error(path, "Must be \"fill\", \"wrap\" or a number of dp between 0 and 4000.")
    }

    private fun checkPadding(element: JsonElement, path: String) {
        fun JsonElement.isDp() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.let { it in 0.0..400.0 } == true
        val ok = when (element) {
            is JsonPrimitive -> element.isDp()
            is JsonArray -> (element.size == 2 || element.size == 4) && element.all { it.isDp() }
            else -> false
        }
        if (!ok) out.error(path, "Must be a number, [horizontal, vertical] or [start, top, end, bottom], each 0 to 400 dp.")
    }

    private fun metaFor(descriptor: SerialDescriptor, index: Int): FieldMeta {
        val annotations = descriptor.getElementAnnotations(index)
        return FieldMeta(
            color = annotations.any { it is ColorValue },
            range = annotations.filterIsInstance<Range>().firstOrNull()?.let { it.min..it.max },
        )
    }

    private fun Double.clean(): String = if (this == floor(this)) toLong().toString() else toString()
}
