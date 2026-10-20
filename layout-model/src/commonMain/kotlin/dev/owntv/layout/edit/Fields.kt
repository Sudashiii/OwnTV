package dev.owntv.layout.edit

import dev.owntv.layout.model.BackgroundSerializer
import dev.owntv.layout.model.ColorValue
import dev.owntv.layout.model.Doc
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How the editor shows and edits one property. */
enum class FieldKind {
    Text, Color, Package, StyleRef, NodeRef, PageRef,
    Bool, Int, Decimal, Enum, Size, Padding, Background,
    Object, Sealed, StringList, ObjectList, Unsupported,
}

/** One editable property, derived from the serializers and their annotations. */
data class FieldSpec(
    val name: String,
    val kind: FieldKind,
    val descriptor: SerialDescriptor,
    val doc: String?,
    /** True when the property may be left out (it has a default or is nullable). */
    val optional: Boolean,
    /** True when "not set" is a meaningful value of its own (the property is nullable). */
    val nullable: Boolean,
    val range: ClosedFloatingPointRange<Double>? = null,
    val enumValues: List<String> = emptyList(),
    /** For lists of strings: how each item is edited. */
    val itemKind: FieldKind? = null,
)

/** Builds editor forms from the layout model's serial descriptors. */
object Fields {
    private val nodeRefNames = setOf("initialFocus", "target", "up", "down", "left", "right")

    /** Fields of a node type: its own properties, then the common ones. Children are edited in the outline. */
    fun forNode(type: String): List<FieldSpec> {
        val nodeType = NodeRegistry.forType(type) ?: return emptyList()
        return forDescriptors(listOf(nodeType.serializer.descriptor, NodeRegistry.commonDescriptor), skip = setOf("children"))
    }

    fun forDescriptors(descriptors: List<SerialDescriptor>, skip: Set<String> = emptySet()): List<FieldSpec> =
        descriptors.flatMap { forDescriptor(it, skip) }

    fun forDescriptor(descriptor: SerialDescriptor, skip: Set<String> = emptySet()): List<FieldSpec> =
        (0 until descriptor.elementsCount).mapNotNull { i ->
            val name = descriptor.getElementName(i)
            if (name in skip) return@mapNotNull null
            val element = descriptor.getElementDescriptor(i)
            if (element.base() == NodeSerializer.SERIAL_NAME) return@mapNotNull null
            if (element.kind == StructureKind.LIST && element.getElementDescriptor(0).base() == NodeSerializer.SERIAL_NAME) return@mapNotNull null
            val annotations = descriptor.getElementAnnotations(i)
            val color = annotations.any { it is ColorValue }
            val kind = kindOf(name, element, color)
            FieldSpec(
                name = name,
                kind = kind,
                descriptor = element,
                doc = annotations.filterIsInstance<Doc>().firstOrNull()?.value,
                optional = descriptor.isElementOptional(i),
                nullable = element.isNullable,
                range = annotations.filterIsInstance<Range>().firstOrNull()?.let { it.min..it.max },
                enumValues = if (element.kind == SerialKind.ENUM) element.elementNames.toList() else emptyList(),
                itemKind = if (kind == FieldKind.StringList) itemKindOf(name, color) else null,
            )
        }

    /** The concrete descriptors a sealed or background value can take, by `"type"`. */
    fun subtypes(descriptor: SerialDescriptor): Map<String, SerialDescriptor> = when {
        descriptor.base() == BackgroundSerializer.SERIAL_NAME ->
            BackgroundSerializer.subtypes.mapValues { it.value.descriptor }
        descriptor.kind == PolymorphicKind.SEALED -> {
            val sub = descriptor.getElementDescriptor(1)
            sub.elementNames.zip(sub.elementDescriptors.toList()).toMap()
        }
        else -> emptyMap()
    }

    /** A minimal valid value for a new object of [descriptor] (required properties get placeholders). */
    fun newObject(descriptor: SerialDescriptor, type: String? = null): JsonObject {
        val entries = linkedMapOf<String, JsonElement>()
        if (type != null) entries["type"] = JsonPrimitive(type)
        for (i in 0 until descriptor.elementsCount) {
            if (descriptor.isElementOptional(i)) continue
            val element = descriptor.getElementDescriptor(i)
            val name = descriptor.getElementName(i)
            // A list source needs a valid list name; favorites is the one most layouts share.
            entries[name] = if (name == "list" && element.kind == PrimitiveKind.STRING) JsonPrimitive("favorites") else placeholder(element)
        }
        return JsonObject(entries)
    }

    /** A new value for a sealed descriptor: the first subtype, or [type] when given. */
    fun newSealed(descriptor: SerialDescriptor, type: String? = null): JsonObject {
        val subtypes = subtypes(descriptor)
        val chosen = type ?: subtypes.keys.first()
        return newObject(subtypes.getValue(chosen), chosen)
    }

    private fun placeholder(element: SerialDescriptor): JsonElement = when {
        element.base() == SizeSerializer.SERIAL_NAME -> JsonPrimitive("wrap")
        element.kind == PrimitiveKind.STRING -> JsonPrimitive("")
        element.kind == PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
        element.kind is PrimitiveKind -> JsonPrimitive(1)
        element.kind == SerialKind.ENUM -> JsonPrimitive(element.getElementName(0))
        element.kind == StructureKind.LIST -> JsonArray(emptyList())
        element.kind == StructureKind.MAP -> JsonObject(emptyMap())
        element.kind == PolymorphicKind.SEALED || element.base() == BackgroundSerializer.SERIAL_NAME -> newSealed(element)
        else -> newObject(element)
    }

    private fun kindOf(name: String, element: SerialDescriptor, color: Boolean): FieldKind = when {
        element.base() == SizeSerializer.SERIAL_NAME -> FieldKind.Size
        element.base() == PaddingSerializer.SERIAL_NAME -> FieldKind.Padding
        element.base() == BackgroundSerializer.SERIAL_NAME -> FieldKind.Background
        element.kind == PrimitiveKind.STRING -> when {
            color -> FieldKind.Color
            name == "package" -> FieldKind.Package
            name == "style" -> FieldKind.StyleRef
            name == "page" -> FieldKind.PageRef
            name in nodeRefNames -> FieldKind.NodeRef
            else -> FieldKind.Text
        }
        element.kind == PrimitiveKind.BOOLEAN -> FieldKind.Bool
        element.kind == PrimitiveKind.INT || element.kind == PrimitiveKind.LONG ||
            element.kind == PrimitiveKind.SHORT || element.kind == PrimitiveKind.BYTE -> FieldKind.Int
        element.kind == PrimitiveKind.FLOAT || element.kind == PrimitiveKind.DOUBLE -> FieldKind.Decimal
        element.kind == SerialKind.ENUM -> FieldKind.Enum
        element.kind == StructureKind.CLASS || element.kind == StructureKind.OBJECT -> FieldKind.Object
        element.kind == PolymorphicKind.SEALED -> FieldKind.Sealed
        element.kind == StructureKind.LIST -> {
            val item = element.getElementDescriptor(0)
            when (item.kind) {
                PrimitiveKind.STRING -> FieldKind.StringList
                StructureKind.CLASS -> FieldKind.ObjectList
                else -> FieldKind.Unsupported
            }
        }
        else -> FieldKind.Unsupported
    }

    private fun itemKindOf(name: String, color: Boolean): FieldKind = when {
        color -> FieldKind.Color
        name == "exclude" -> FieldKind.Package
        else -> FieldKind.Text
    }

    private fun SerialDescriptor.base() = serialName.removeSuffix("?")
}

/** One step into a JSON value: an object key or a list index. */
sealed interface PathStep {
    data class Key(val name: String) : PathStep
    data class Index(val index: Int) : PathStep
}

/** The value at [path], or null when it is missing. */
fun JsonElement.at(path: List<PathStep>): JsonElement? = path.fold(this as JsonElement?) { current, step ->
    when (step) {
        is PathStep.Key -> (current as? JsonObject)?.get(step.name)
        is PathStep.Index -> (current as? JsonArray)?.getOrNull(step.index)
    }
}

/** A copy with the value at [path] replaced; null removes the key or list item. Missing objects on the way are created. */
fun JsonElement.with(path: List<PathStep>, value: JsonElement?): JsonElement {
    if (path.isEmpty()) return value ?: JsonObject(emptyMap())
    return when (val step = path.first()) {
        is PathStep.Key -> {
            val obj = this as? JsonObject ?: JsonObject(emptyMap())
            val rest = path.drop(1)
            val child = if (rest.isEmpty()) value else (obj[step.name] ?: JsonObject(emptyMap())).with(rest, value)
            JsonObject(LinkedHashMap(obj).apply { if (child == null) remove(step.name) else put(step.name, child) })
        }
        is PathStep.Index -> {
            val list = (this as? JsonArray)?.toMutableList() ?: mutableListOf()
            val rest = path.drop(1)
            if (rest.isEmpty()) {
                if (value == null) {
                    if (step.index in list.indices) list.removeAt(step.index)
                } else if (step.index >= list.size) {
                    list.add(value)
                } else {
                    list[step.index] = value
                }
            } else if (step.index in list.indices) {
                list[step.index] = list[step.index].with(rest, value)
            }
            JsonArray(list)
        }
    }
}
