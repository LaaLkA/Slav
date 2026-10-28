package io.github.laalka.slav.core

import io.github.laalka.slav.core.CalcError.MissingPrice
import io.github.laalka.slav.core.Fixtures.ST3
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCalculatorTest {
    private val book = PriceBook.default()

    /** The golden case: Ст3, 2 mm, 1.5 m² of sheet, 12 min of laser, a run of 10. */
    private fun quick(
        grade: String = ST3,
        thickness: String = "2",
        area: String = "1.5",
        minutes: String = "12",
        quantity: Int = 10,
        materialExtra: ManualExtra? = null,
        cutExtra: ManualExtra? = null,
    ) = QuickCalculationInput("Лист на раскрой", quantity, grade, dec(thickness), dec(area), dec(minutes), materialExtra, cutExtra)

    private fun PriceBook.quickPriced(input: QuickCalculationInput): QuickResult =
        assertIs<QuickOutcome.Ok>(QuickCalculator(this).calculate(input), "expected Ok").result

    private fun PriceBook.quickErrors(input: QuickCalculationInput): List<CalcError> =
        assertIs<QuickOutcome.Failed>(QuickCalculator(this).calculate(input), "expected Failed").errors

    @Test
    fun `golden test on the default prices`() {
        val input = quick()
        val result = book.quickPriced(input)
        // 1.5 m² × 0.002 m × 8000 kg/m³ = 24.00 kg; × 200 ₽/kg (Ст3, 2 mm) = 4800.00.
        assertDecimal("24.00", result.massKg)
        assertKopecks("4800.00", result.material.cost)
        // 12 min × 50 ₽/min = 600.00.
        assertKopecks("600.00", result.cut.cost)
        assertKopecks("5400.00", result.unitPrice)
        assertKopecks("54000.00", result.total(input.quantity))
    }

    @Test
    fun `the manual extras join their lines before the lines are rounded`() {
        val result = book.quickPriced(
            quick(materialExtra = ManualExtra(Money(dec("200.50")), "Доставка листа"), cutExtra = ManualExtra(Money(dec("150")), "Снятие фаски")),
        )
        assertKopecks("5000.50", result.material.cost)
        assertKopecks("750.00", result.cut.cost)
        assertKopecks("5750.50", result.unitPrice)
        assertEquals(ManualExtra(Money(dec("200.50")), "Доставка листа"), result.material.manualExtra)
        assertEquals(ManualExtra(Money(dec("150")), "Снятие фаски"), result.cut.manualExtra)
        // The extra joins the formula value before rounding: 0.004 + 0.004 is 0.01, whereas 0.00 + 0.004 would stay 0.00.
        val cheap = book.copy(laserCutPerMinute = dec("0.4")).quickPriced(quick(minutes = "0.01", cutExtra = ManualExtra(Money(dec("0.004")), "")))
        assertKopecks("0.01", cheap.cut.cost)
    }

    @Test
    fun `every line is rounded half-up to kopecks and the unit price is the sum of the rounded lines`() {
        // 0.0001 m² × 0.001 m × 8000 = 0.0008 kg × 100 ₽/kg = 0.08; 0.01 min × 0.5 ₽/min = 0.005 → 0.01.
        val result = book.copy(laserCutPerMinute = dec("0.5")).quickPriced(quick(thickness = "1", area = "0.0001", minutes = "0.01"))
        assertKopecks("0.08", result.material.cost)
        assertKopecks("0.01", result.cut.cost)
        assertKopecks("0.09", result.unitPrice)
        assertKopecks("0.90", result.total(10))
        // Below the half: 0.004 → 0.00.
        assertKopecks("0.00", book.copy(laserCutPerMinute = dec("0.4")).quickPriced(quick(minutes = "0.01")).cut.cost)
    }

    @Test
    fun `a thickness is matched by value and the mass follows the density of the grade`() {
        val aluminium = book.copy(
            groups = book.groups + MaterialGroup("Алюминий"),
            grades = book.grades + Grade("АМг3", "Алюминий", dec("2700")),
            materialPricesPerKg = book.materialPricesPerKg + GradePrice("АМг3", dec("2.0"), dec("500")),
        )
        val result = aluminium.quickPriced(quick(grade = "АМг3", thickness = "2", area = "2"))
        // 2 m² × 0.002 m × 2700 = 10.8 kg × 500 = 5400.00.
        assertDecimal("10.8", result.massKg)
        assertKopecks("5400.00", result.material.cost)
    }

    @Test
    fun `an unknown grade is reported, and the missing laser rate with it`() {
        assertEquals(listOf<CalcError>(CalcError.UnknownGrade("Х18Н10Т")), book.quickErrors(quick(grade = "Х18Н10Т")))
        assertEquals(
            listOf(CalcError.UnknownGrade("Х18Н10Т"), MissingPrice(PriceTable.LaserCutPerMinute, null, null)),
            book.copy(laserCutPerMinute = null).quickErrors(quick(grade = "Х18Н10Т")),
        )
    }

    @Test
    fun `a missing price per kg names the grade and the thickness`() {
        assertEquals(
            listOf<CalcError>(MissingPrice(PriceTable.MaterialPerKg, ST3, "7")),
            book.quickErrors(quick(thickness = "7")),
        )
        assertEquals(
            listOf<CalcError>(MissingPrice(PriceTable.MaterialPerKg, ST3, "1.5")),
            book.quickErrors(quick(thickness = "1.50")),
        )
    }

    @Test
    fun `a missing laser rate and a missing price per kg come in one outcome`() {
        assertEquals(
            listOf(MissingPrice(PriceTable.LaserCutPerMinute, null, null), MissingPrice(PriceTable.MaterialPerKg, ST3, "7")),
            book.copy(laserCutPerMinute = null).quickErrors(quick(thickness = "7")),
        )
    }

    @Test
    fun `a zero rate is a price, an absent one is not`() {
        assertKopecks("0.00", book.copy(laserCutPerMinute = dec("0")).quickPriced(quick()).cut.cost)
        assertEquals(
            listOf<CalcError>(MissingPrice(PriceTable.LaserCutPerMinute, null, null)),
            book.copy(laserCutPerMinute = null).quickErrors(quick()),
        )
    }

    @Test
    fun `the quick input survives a JSON round trip with its defaults written`() {
        val input = quick(materialExtra = ManualExtra(Money(dec("200.50")), "Доставка листа"))
        val text = Json.encodeToString(input)
        assertEquals(input, Json.decodeFromString<QuickCalculationInput>(text))
        assertTrue("\"cutExtra\":null" in text && "\"sheetAreaM2\":\"1.5\"" in text, "defaults must be encoded: $text")
        // Saved without extras, read without extras.
        val bare = """{"name":"Лист","quantity":1,"grade":"Ст3","thicknessMm":"2","sheetAreaM2":"1","cutTimeMin":"3"}"""
        assertNull(Json.decodeFromString<QuickCalculationInput>(bare).cutExtra)
        assertNull(Json.decodeFromString<QuickCalculationInput>(bare).materialExtra)
    }
}
