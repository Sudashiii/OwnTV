package dev.owntv.layout.validation

import dev.owntv.layout.model.ColorValue
import dev.owntv.layout.model.Colors
import dev.owntv.layout.model.CommonNodeFields
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

    fun check(descriptor: SerialDescriptor, element: JsonElement, path: String, meta: FieldMeta = FieldMeta()) {
        if (element is JsonNull) {
            if (!descriptor.isNullable) out.error(path, "Must not be null.")
            return
        }
        when (descriptor.serialName.removeSuffix("?")) {
            NodeSerializer.SERIAL_NAME -> return checkNode(element, path)
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
                obj.forEach { (key, value) -> check(descriptor.getElementDescriptor(1), value, "$path.$key", meta) }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return out.error(path, "Must be an object {...}.")
                checkObject(descriptor, obj, path, isNode = false, skip = emptySet())
            }
            PolymorphicKind.SEALED -> checkSealed(descriptor, element, path)
            else -> Unit
        }
    }

    private fun checkObject(descriptor: SerialDescriptor, obj: JsonObject, path: String, isNode: Boolean, skip: Set<String>) {
        for ((key, value) in obj) {
            if (key in skip) continue
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                val allowed = descriptor.elementNames.joinToString(", ")
                out.warning("$path.$key", "Unknown property \"$key\" is ignored. Allowed here: $allowed.")
                continue
            }
            check(descriptor.getElementDescriptor(index), value, "$path.$key", metaFor(descriptor, index, isNode))
        }
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            if (!descriptor.isElementOptional(i) && name !in obj) {
                out.error(path, "Missing required property \"$name\".")
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
        checkObject(subtypes.getElementDescriptor(index), obj, path, isNode = false, skip = setOf("type"))
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
        checkObject(nodeType.serializer.descriptor, obj, path, isNode = true, skip = setOf("type"))
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
        if (integer && value != Math.floor(value)) return out.error(path, "Must be a whole number, got $value.")
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

    private fun metaFor(descriptor: SerialDescriptor, index: Int, isNode: Boolean): FieldMeta {
        val annotations = descriptor.getElementAnnotations(index)
        val name = descriptor.getElementName(index)
        val range = annotations.filterIsInstance<Range>().firstOrNull()?.let { it.min..it.max }
            ?: if (isNode) CommonNodeFields.ranges[name] else null
        val color = annotations.any { it is ColorValue } || (isNode && name in CommonNodeFields.colorFields)
        return FieldMeta(color = color, range = range)
    }

    private fun Double.clean(): String = if (this == Math.floor(this)) toLong().toString() else toString()
}
