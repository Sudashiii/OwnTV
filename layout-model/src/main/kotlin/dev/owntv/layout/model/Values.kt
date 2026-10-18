package dev.owntv.layout.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlin.math.roundToInt

/** A width or height: `"fill"`, `"wrap"` or a number of dp. */
@Serializable(with = SizeSerializer::class)
sealed interface Size {
    data object Fill : Size
    data object Wrap : Size
    data class Fixed(val dp: Int) : Size
}

object SizeSerializer : KSerializer<Size> {
    const val SERIAL_NAME = "dev.owntv.layout.Size"
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Size) {
        val element = when (value) {
            Size.Fill -> JsonPrimitive("fill")
            Size.Wrap -> JsonPrimitive("wrap")
            is Size.Fixed -> JsonPrimitive(value.dp)
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Size {
        val p = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("A size must be \"fill\", \"wrap\" or a number")
        if (p.isString) {
            return when (p.content) {
                "fill" -> Size.Fill
                "wrap" -> Size.Wrap
                else -> throw SerializationException("Unknown size \"${p.content}\"")
            }
        }
        val n = p.floatOrNull ?: throw SerializationException("A size must be \"fill\", \"wrap\" or a number")
        return Size.Fixed(n.roundToInt())
    }
}

/** Padding in dp. JSON form: `16`, `[horizontal, vertical]` or `[start, top, end, bottom]`. */
@Serializable(with = PaddingSerializer::class)
data class Padding(val start: Int, val top: Int, val end: Int, val bottom: Int) {
    companion object {
        fun all(value: Int) = Padding(value, value, value, value)
        fun symmetric(horizontal: Int, vertical: Int) = Padding(horizontal, vertical, horizontal, vertical)
    }
}

object PaddingSerializer : KSerializer<Padding> {
    const val SERIAL_NAME = "dev.owntv.layout.Padding"
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Padding) {
        val element = with(value) {
            when {
                start == top && top == end && end == bottom -> JsonPrimitive(start)
                start == end && top == bottom -> JsonArray(listOf(JsonPrimitive(start), JsonPrimitive(top)))
                else -> JsonArray(listOf(start, top, end, bottom).map(::JsonPrimitive))
            }
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Padding {
        return when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonPrimitive -> Padding.all(element.number())
            is JsonArray -> when (element.size) {
                2 -> Padding.symmetric(element[0].number(), element[1].number())
                4 -> Padding(element[0].number(), element[1].number(), element[2].number(), element[3].number())
                else -> throw SerializationException("Padding arrays need 2 or 4 numbers")
            }
            else -> throw SerializationException("Padding must be a number or an array")
        }
    }

    private fun kotlinx.serialization.json.JsonElement.number(): Int =
        ((this as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
            ?: throw SerializationException("Padding values must be numbers")).roundToInt()
}

/** Parses colors and resolves theme tokens. Colors are returned as ARGB ints. */
object Colors {
    private val hex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val token = Regex("^@[A-Za-z][A-Za-z0-9_]*$")

    fun isHex(value: String) = hex.matches(value)
    fun isToken(value: String) = token.matches(value)
    fun isColor(value: String) = isHex(value) || isToken(value)

    fun parseHex(value: String): Int? {
        if (!isHex(value)) return null
        val digits = value.substring(1)
        val argb = if (digits.length == 6) "FF$digits" else digits
        return argb.toLong(16).toInt()
    }
}
