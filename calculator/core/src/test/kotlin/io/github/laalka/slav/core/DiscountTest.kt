package io.github.laalka.slav.core

import io.github.laalka.slav.core.Fixtures.ST3
import io.github.laalka.slav.core.Fixtures.STEEL
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The discount of a calculation, full and simplified: what it takes off the price and how the kopecks are rounded. */
class DiscountTest {
    private val book = PriceBook.default()

    /** The golden case of CalculatorTest: 11 340,00 a piece, a run of 10. */
    private val goldenInput = CalculationInput(
        name = "Кронштейн",
        quantity = 10,
        parts = listOf(
            Part(
                name = "Кронштейн",
                material = rectangleSheet("2", "1000", "1000"),
                operations = listOf(
                    PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("4000"), pierces = 3)),
                    PartOperation.Bending(listOf(BendRow(dec("80"), 4))),
                ),
            ),
        ),
        productOperations = listOf(ProductOperation.AssemblyWelding("полуавтомат", STEEL, dec("2"), seamLengthMm = dec("40"))),
    )

    /** The quick golden case: Ст3, 2 mm, 1.5 m² of sheet, 12 minutes of laser: 4 800,00 + 600,00 = 5 400,00. */
    private val quickInput = QuickCalculationInput("Лист на раскрой", 1, ST3, dec("2"), dec("1.5"), dec("12"))

    private fun PriceBook.quickPriced(input: QuickCalculationInput): QuickResult =
        assertIs<QuickOutcome.Ok>(QuickCalculator(this).calculate(input), "expected Ok").result

    /** A result whose subtotal is exactly [amount]: one product line, nothing else. */
    private fun fullResult(amount: String, percent: String?) =
        CalcResult(emptyList(), listOf(OperationLine(OperationKind.WELDING, Money(dec(amount)), null)), percent?.let(::dec))

    private fun quickResult(amount: String, percent: String?) =
        QuickResult(dec("1"), MaterialLine(Money(dec(amount)), null), CutLine(Money(dec("0.00")), null), percent?.let(::dec))

    @Test
    fun `without a discount the golden case is as it was and the discount is zero`() {
        val result = book.priced(goldenInput)
        assertNull(result.discountPercent)
        assertKopecks("11340.00", result.subtotal)
        assertKopecks("0.00", result.discount)
        assertKopecks("11340.00", result.unitPrice)
        assertKopecks("113400.00", result.total(10))
    }

    @Test
    fun `10 percent takes 1 134,00 off the golden 11 340,00`() {
        val result = book.priced(goldenInput.copy(discountPercent = dec("10")))
        assertDecimal("10", result.discountPercent)
        assertKopecks("11340.00", result.subtotal)
        assertKopecks("1134.00", result.discount)
        assertKopecks("10206.00", result.unitPrice)
        assertKopecks("102060.00", result.total(10))
    }

    @Test
    fun `5 percent takes 270,00 off the quick golden 5 400,00, and the unit price is the total`() {
        val result = book.quickPriced(quickInput.copy(discountPercent = dec("5")))
        assertKopecks("5400.00", result.subtotal)
        assertKopecks("270.00", result.discount)
        assertKopecks("5130.00", result.unitPrice)
        assertKopecks("5130.00", result.total(1))
        // Without a discount the quick golden is unchanged.
        val plain = book.quickPriced(quickInput)
        assertKopecks("5400.00", plain.unitPrice)
        assertKopecks("0.00", plain.discount)
    }

    @Test
    fun `a zero discount is no discount`() {
        val zero = book.priced(goldenInput.copy(discountPercent = dec("0")))
        assertKopecks("0.00", zero.discount)
        assertKopecks("11340.00", zero.unitPrice)
        assertKopecks("0.00", book.quickPriced(quickInput.copy(discountPercent = dec("0.0"))).discount)
    }

    @Test
    fun `the discount is rounded half-up to kopecks and the unit price is the subtotal less that rounded amount`() {
        // 333,33 × 15 % = 49,9995: truncating would give 49,99, half-up gives 50,00.
        fullResult("333.33", "15").let {
            assertKopecks("50.00", it.discount)
            assertKopecks("283.33", it.unitPrice)
        }
        // Exactly half a kopeck: 0,50 × 1 % = 0,005 goes up to 0,01, not to the even 0,00.
        fullResult("0.50", "1").let {
            assertKopecks("0.01", it.discount)
            assertKopecks("0.49", it.unitPrice)
        }
        // Below the half: 100,04 × 5 % = 5,002 stays 5,00.
        fullResult("100.04", "5").let {
            assertKopecks("5.00", it.discount)
            assertKopecks("95.04", it.unitPrice)
        }
        // An odd kopeck of a large run: 7 950,50 × 12,5 % = 993,8125 → 993,81; ×25 stays a sum of rounded amounts.
        fullResult("7950.50", "12.5").let {
            assertKopecks("993.81", it.discount)
            assertKopecks("6956.69", it.unitPrice)
            assertKopecks("173917.25", it.total(25))
        }
    }

    @Test
    fun `the quick result rounds the discount the same way`() {
        quickResult("333.33", "15").let {
            assertKopecks("50.00", it.discount)
            assertKopecks("283.33", it.unitPrice)
        }
        quickResult("0.50", "1").let {
            assertKopecks("0.01", it.discount)
            assertKopecks("0.49", it.unitPrice)
        }
    }

    @Test
    fun `a discount outside 0 up to but not including 100 is refused`() {
        for (bad in listOf("-0.01", "-5", "100", "100.00", "150")) {
            assertFailsWith<IllegalArgumentException>("input $bad") { goldenInput.copy(discountPercent = dec(bad)) }
            assertFailsWith<IllegalArgumentException>("quick input $bad") { quickInput.copy(discountPercent = dec(bad)) }
            assertFailsWith<IllegalArgumentException>("result $bad") { fullResult("100.00", bad) }
            assertFailsWith<IllegalArgumentException>("quick result $bad") { quickResult("100.00", bad) }
        }
        for (good in listOf("0", "0.01", "50", "99.99")) {
            goldenInput.copy(discountPercent = dec(good))
            quickInput.copy(discountPercent = dec(good))
        }
        assertKopecks("0.01", fullResult("100.00", "99.99").unitPrice)
    }

    @Test
    fun `the discount of the input reaches the result, and an input with no discount gives a result with none`() {
        assertDecimal("12.5", book.priced(goldenInput.copy(discountPercent = dec("12.5"))).discountPercent)
        assertNull(book.priced(goldenInput).discountPercent)
        assertDecimal("5", book.quickPriced(quickInput.copy(discountPercent = dec("5"))).discountPercent)
        assertNull(book.quickPriced(quickInput).discountPercent)
    }

    @Test
    fun `counterparty and discount are written with their defaults, and an input without them reads as none`() {
        val full = goldenInput.copy(counterparty = "ООО «Вектор»", discountPercent = dec("7.5"))
        val fullText = Json.encodeToString(full)
        assertEquals(full, Json.decodeFromString<CalculationInput>(fullText))
        val bareFullText = Json.encodeToString(goldenInput)
        assertTrue("\"counterparty\":\"\"" in bareFullText && "\"discountPercent\":null" in bareFullText, "defaults must be encoded: $bareFullText")

        val quick = quickInput.copy(counterparty = "ИП Орлов", discountPercent = dec("5"))
        assertEquals(quick, Json.decodeFromString<QuickCalculationInput>(Json.encodeToString(quick)))
        val bareQuickText = Json.encodeToString(quickInput)
        assertTrue("\"counterparty\":\"\"" in bareQuickText && "\"discountPercent\":null" in bareQuickText, "defaults must be encoded: $bareQuickText")

        // Written before the two fields existed.
        val oldFull = """{"name":"Рама","quantity":2,"parts":[],"productOperations":[]}"""
        Json.decodeFromString<CalculationInput>(oldFull).let {
            assertEquals("", it.counterparty)
            assertNull(it.discountPercent)
        }
        val oldQuick = """{"name":"Лист","quantity":1,"grade":"Ст3","thicknessMm":"2","sheetAreaM2":"1","cutTimeMin":"3"}"""
        Json.decodeFromString<QuickCalculationInput>(oldQuick).let {
            assertEquals("", it.counterparty)
            assertNull(it.discountPercent)
        }
    }

    @Test
    fun `money subtraction keeps the scale of the kopecks`() {
        assertKopecks("10206.00", Money(dec("11340.00")) - Money(dec("1134.00")))
        assertEquals(BigDecimal("0.00"), (Money(dec("5.00")) - Money(dec("5.00"))).amount)
    }
}
