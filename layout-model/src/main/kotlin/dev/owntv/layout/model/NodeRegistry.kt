package dev.owntv.layout.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass

enum class NodeCategory { Container, Apps, Content }

class NodeType<T : Node>(
    val kClass: KClass<T>,
    val serializer: KSerializer<T>,
    val category: NodeCategory,
) {
    /** The JSON `"type"` value, taken from the class's `@SerialName`. */
    val type: String get() = serializer.descriptor.serialName

    val description: String
        get() = serializer.descriptor.annotations.filterIsInstance<Doc>().firstOrNull()?.value.orEmpty()

    /** The node's own JSON keys (without `type` and the common keys). */
    val ownKeys: Set<String> by lazy { serializer.descriptor.elementNames.toSet() }

    /** Every key a node of this type accepts besides `type`. */
    val allKeys: Set<String> by lazy { ownKeys + NodeRegistry.commonKeys }
}

/** Every node type the format knows. Drives decoding, the JSON Schema and the editor palette. */
object NodeRegistry {
    val all: List<NodeType<out Node>> = listOf(
        NodeType(ColumnNode::class, ColumnNode.serializer(), NodeCategory.Container),
        NodeType(RowNode::class, RowNode.serializer(), NodeCategory.Container),
        NodeType(GridNode::class, GridNode.serializer(), NodeCategory.Container),
        NodeType(StackNode::class, StackNode.serializer(), NodeCategory.Container),
        NodeType(AppTileNode::class, AppTileNode.serializer(), NodeCategory.Apps),
        NodeType(AppRowNode::class, AppRowNode.serializer(), NodeCategory.Apps),
        NodeType(AppGridNode::class, AppGridNode.serializer(), NodeCategory.Apps),
        NodeType(FolderNode::class, FolderNode.serializer(), NodeCategory.Apps),
        NodeType(ShortcutNode::class, ShortcutNode.serializer(), NodeCategory.Apps),
        NodeType(HeroNode::class, HeroNode.serializer(), NodeCategory.Content),
        NodeType(TextNode::class, TextNode.serializer(), NodeCategory.Content),
        NodeType(ClockNode::class, ClockNode.serializer(), NodeCategory.Content),
        NodeType(ImageNode::class, ImageNode.serializer(), NodeCategory.Content),
        NodeType(SpacerNode::class, SpacerNode.serializer(), NodeCategory.Content),
    )

    val commonDescriptor: SerialDescriptor get() = NodeCommon.serializer().descriptor
    val commonKeys: Set<String> by lazy { commonDescriptor.elementNames.toSet() }

    private val byType = all.associateBy { it.type }
    private val byClass: Map<KClass<*>, NodeType<out Node>> = all.associateBy { it.kClass }

    val types: List<String> get() = all.map { it.type }

    fun forType(type: String?): NodeType<out Node>? = byType[type]

    fun forNode(node: Node): NodeType<out Node>? = byClass[node::class]
}

/**
 * Reads and writes nodes as one flat JSON object: `"type"`, the node's own properties and the
 * [NodeCommon] properties. Unknown types become [UnknownNode] instead of failing.
 */
object NodeSerializer : KSerializer<Node> {
    const val SERIAL_NAME = "dev.owntv.layout.Node"

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor(SERIAL_NAME, PolymorphicKind.SEALED)

    override fun deserialize(decoder: Decoder): Node {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Nodes can only be read from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val nodeType = NodeRegistry.forType(type) ?: return UnknownNode(type.orEmpty(), obj)
        val own = JsonObject(obj.filterKeys { it in nodeType.ownKeys })
        val common = JsonObject(obj.filterKeys { it in NodeRegistry.commonKeys })
        val node = input.json.decodeFromJsonElement(nodeType.serializer, own)
        return node.withCommon(input.json.decodeFromJsonElement(NodeCommon.serializer(), common))
    }

    override fun serialize(encoder: Encoder, value: Node) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Nodes can only be written as JSON")
        if (value is UnknownNode) {
            output.encodeJsonElement(value.raw)
            return
        }
        val nodeType = NodeRegistry.forNode(value) ?: throw SerializationException("Unregistered node ${value::class}")
        @Suppress("UNCHECKED_CAST")
        val own = output.json.encodeToJsonElement(nodeType.serializer as KSerializer<Node>, value).jsonObject
        val common = output.json.encodeToJsonElement(NodeCommon.serializer(), value.common).jsonObject
        output.encodeJsonElement(JsonObject(linkedMapOf<String, JsonElement>("type" to JsonPrimitive(nodeType.type)) + own + common))
    }
}
