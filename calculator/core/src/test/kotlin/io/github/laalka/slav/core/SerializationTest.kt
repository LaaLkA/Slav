package io.github.laalka.slav.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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
        assertTrue("\"densityKgM3\":\"8000\"" in text, text)
    }

    @Test
    fun `full calculation input survives a JSON round trip`() {
        val input = Fixtures.fullInput
        val text = json.encodeToString(input)
        assertEquals(input, json.decodeFromString<CalculationInput>(text))
        assertTrue(
            "\"cuts\":2" in text && "\"manualExtra\":null" in text && "\"massOverrideKg\":\"0.1\"" in text &&
                "\"massOverrideKg\":null" in text,
            "defaults must be encoded: $text",
        )
    }

    @Test
    fun `sheet shapes and the shape cut mode have their own type names`() {
        val sheet = Material.Sheet("Ст3", dec("2"), SheetShape.Ring(dec("50"), dec("20")))
        assertEquals(
            """{"type":"sheet","grade":"Ст3","thicknessMm":"2","shape":{"type":"ring","outerRadiusMm":"50","innerRadiusMm":"20"}}""",
            json.encodeToString<Material>(sheet),
        )
        assertEquals("""{"type":"by_shape"}""", json.encodeToString<SheetCutMode>(SheetCutMode.ByShape))
        for (shape in listOf(
            SheetShape.Rectangle(dec("300"), dec("200")),
            SheetShape.Circle(dec("50")),
            SheetShape.Ring(dec("50"), dec("20")),
            SheetShape.Free(dec("12000"), dec("500")),
        )) {
            assertEquals(shape, json.decodeFromString<SheetShape>(json.encodeToString(shape)))
        }
    }

    @Test
    fun `a part saved without a mass from the drawing and settings saved without the painting minimum still decode`() {
        val part = """{"name":"Деталь","material":{"type":"sheet","grade":"Ст3","thicknessMm":"2","shape":{"type":"circle","radiusMm":"50"}},"operations":[]}"""
        assertEquals(null, json.decodeFromString<Part>(part).massOverrideKg)
        assertEquals(null, json.decodeFromString<Part>(part).materialExtra)

        val book = json.parseToJsonElement(json.encodeToString(PriceBook.default())).jsonObject
        val general = JsonObject(book.getValue("general").jsonObject - "minPaintAreaPerPartM2")
        val format1 = JsonObject(book + ("general" to general) + ("formatVersion" to JsonPrimitive(1)))
        val decoded = json.decodeFromJsonElement<PriceBook>(format1)
        assertDecimal("0.1", decoded.general.minPaintAreaPerPartM2)
        assertEquals(1, decoded.formatVersion)
    }

    @Test
    fun `fixture covers every material kind, sheet shape, operation and cut mode`() {
        val types = typeTags(json.encodeToJsonElement(CalculationInput.serializer(), Fixtures.fullInput))
        for (serializer in listOf<KSerializer<*>>(
            Material.serializer(),
            SheetShape.serializer(),
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
