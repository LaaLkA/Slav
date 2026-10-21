package io.github.laalka.slav.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SerializationTest {
    private val json = Json

    @Test
    fun `BigDecimal is written as a plain string`() {
        assertEquals("\"0.0000001\"", json.encodeToString(BigDecimalSerializer, dec("1E-7")))
        assertEquals("\"1000\"", json.encodeToString(BigDecimalSerializer, dec("1E+3")))
        assertEquals("\"2.50\"", json.encodeToString(Money(dec("2.50"))))
        assertEquals(dec("2.50"), json.decodeFromString(BigDecimalSerializer, "\"2.50\""))
    }

    @Test
    fun `malformed decimal is a serialization error`() {
        assertFailsWith<SerializationException> { json.decodeFromString(BigDecimalSerializer, "\"12,5\"") }
    }

    @Test
    fun `default price book survives a JSON round trip`() {
        val book = PriceBook.default()
        val text = json.encodeToString(book)
        assertEquals(book, json.decodeFromString<PriceBook>(text))
        val tree = json.parseToJsonElement(text).jsonObject
        assertEquals(PriceBook.FORMAT_VERSION, tree.getValue("formatVersion").jsonPrimitive.int)
        assertTrue("\"densityKgM3\":\"7850\"" in text, text)
    }

    @Test
    fun `full calculation input survives a JSON round trip`() {
        val input = Fixtures.fullInput
        val text = json.encodeToString(input)
        assertEquals(input, json.decodeFromString<CalculationInput>(text))
        assertTrue("\"cuts\":2" in text && "\"manualExtra\":null" in text, "defaults must be encoded: $text")
    }

    @Test
    fun `fixture covers every material kind, operation and cut mode`() {
        val types = typeTags(json.encodeToJsonElement(CalculationInput.serializer(), Fixtures.fullInput))
        for (serializer in listOf<KSerializer<*>>(
            Material.serializer(),
            PartOperation.serializer(),
            ProductOperation.serializer(),
            SheetCutMode.serializer(),
        )) {
            val subclasses = serializer.descriptor.getElementDescriptor(1)
            val names = (0 until subclasses.elementsCount).map(subclasses::getElementName)
            assertTrue(names.isNotEmpty())
            assertTrue(types.containsAll(names), "fixture lacks ${names - types} of ${serializer.descriptor.serialName}")
        }
    }

    private fun typeTags(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> {
            val own = (element["type"] as? JsonPrimitive)?.content
            element.values.flatMap(::typeTags).toSet() + listOfNotNull(own)
        }
        is JsonArray -> element.flatMap(::typeTags).toSet()
        is JsonPrimitive -> emptySet()
    }
}
