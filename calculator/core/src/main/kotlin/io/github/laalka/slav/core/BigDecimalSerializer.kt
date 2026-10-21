package io.github.laalka.slav.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

/** Writes BigDecimal as a plain JSON string ("0.0000001", never "1E-7") so no precision is lost. */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("io.github.laalka.slav.core.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = decoder.decodeString()
        return try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw SerializationException("Invalid decimal number: '$text'", e)
        }
    }
}

/** BigDecimal for @Serializable classes. */
typealias Decimal = @Serializable(with = BigDecimalSerializer::class) BigDecimal
