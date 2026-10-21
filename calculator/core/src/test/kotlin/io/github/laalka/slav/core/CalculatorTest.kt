package io.github.laalka.slav.core

import io.github.laalka.slav.core.CalcError.MissingPrice
import io.github.laalka.slav.core.Fixtures.STEEL
import io.github.laalka.slav.core.Fixtures.ST3
import io.github.laalka.slav.core.Fixtures.roundTube
import io.github.laalka.slav.core.Fixtures.sheet
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class CalculatorTest {
    /** The structure of a calculation in the old spreadsheet (sheet «прощет») with its example prices. */
    private val spreadsheetInput = CalculationInput(
        name = "Просчёт",
        quantity = 10,
        parts = listOf(
            Part(
                name = "Деталь",
                qtyPerProduct = 1,
                material = Material.Sheet(ST3, dec("2"), lengthMm = dec("1000"), widthMm = dec("1000")),
                operations = listOf(
                    PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("4000"), pierces = 3)),
                    PartOperation.Bending(listOf(BendRow(dec("80"), 4))),
                    PartOperation.Welding("полуавтомат", listOf(WeldRow(dec("40"), 1))),
                ),
            ),
        ),
        productOperations = emptyList(),
    )

    @Test
    fun `golden test reproduces the old spreadsheet`() {
        val result = PriceBook.default().priced(spreadsheetInput)
        val part = result.parts.single()
        // 1000 × 1000 × 2 mm = 0.002 m³ × 7850 = 15.70 kg; × 200 ₽/kg (Ст3, 2 mm) = 3140.00.
        assertDecimal("15.70", part.massKg)
        assertKopecks("3140.00", part.material.cost)
        // Laser: 4000 mm = 400 cm × 20 ₽/cm = 8000.00; 3 pierces × 0 = 0.
        // Bending: 4 bends of 80 mm («до 100», 2 mm) × 25 = 100.00.
        // Welding: semi-automatic, 40 mm = 4 cm × 10 ₽/cm = 40.00.
        assertEquals(
            listOf(OperationKind.LASER_SHEET, OperationKind.BENDING, OperationKind.WELDING),
            part.operations.map { it.kind },
        )
        assertEquals(listOf("8000.00", "100.00", "40.00"), part.operations.map { it.cost.amount.toPlainString() })
        // 3140 + 8000 + 100 + 40 = 11280.00; × 10 = 112800.00.
        assertKopecks("11280.00", part.cost)
        assertKopecks("11280.00", result.unitPrice)
        assertKopecks("112800.00", result.total(10))
        assertDecimal("15.70", result.unitMassKg)
    }

    @Test
    fun `two missing cells yield two errors in one outcome`() {
        val noThree = PriceBook.default().let { book ->
            book.copy(laserSheetCutPerCm = book.laserSheetCutPerCm.filterNot { it.thicknessMm.compareTo(dec("3")) == 0 })
        }
        val part = Material.Sheet(ST3, dec("3"), lengthMm = dec("400"), widthMm = dec("300"))
        // Neither the 3 mm laser cell nor the painting price (unset by default) is filled.
        val errors = noThree.errorsOf(onePart(part, PartOperation.LaserSheet(SheetCutMode.Rectangle), PartOperation.Painting()))
        assertEquals(
            listOf(MissingPrice(PriceTable.LaserSheetCut, STEEL, "3"), MissingPrice(PriceTable.Painting, null, null)),
            errors,
        )
    }

    @Test
    fun `errors of every kind and every part are reported together`() {
        val bigTube = Material.RoundTube(ST3, diameterMm = dec("220"), wallMm = dec("6"), lengthMm = dec("1000"))
        val input = CalculationInput(
            name = "Ошибки",
            quantity = 1,
            parts = listOf(
                Part("Неизвестная марка", 1, sheet.copy(grade = "AISI 304"), listOf(PartOperation.LaserSheet(SheetCutMode.Rectangle))),
                Part("Гнутая труба", 1, roundTube, listOf(PartOperation.Bending(listOf(BendRow(dec("50"), 1))))),
                Part("Большая труба", 1, bigTube, listOf(PartOperation.TubeLaser())),
                Part("Окрашенный лист", 1, sheet, listOf(PartOperation.Painting())),
            ),
            productOperations = emptyList(),
        )
        assertEquals(
            listOf(
                CalcError.UnknownGrade("AISI 304"),
                CalcError.NotApplicable(OperationKind.BENDING, roundTube),
                CalcError.SectionTooLarge(OperationKind.TUBE_LASER, bigTube, BigDecimal(200)),
                MissingPrice(PriceTable.Painting, null, null),
            ),
            PriceBook.default().errorsOf(input),
        )
    }

    @Test
    fun `a missing cell needed by several lines is reported once`() {
        val input = CalculationInput(
            name = "Две покраски",
            quantity = 1,
            parts = listOf(Part("А", 1, sheet, listOf(PartOperation.Painting())), Part("Б", 2, roundTube, listOf(PartOperation.Painting()))),
            productOperations = listOf(ProductOperation.Painting()),
        )
        assertEquals(listOf(MissingPrice(PriceTable.Painting, null, null)), PriceBook.default().errorsOf(input))
    }

    @Test
    fun `every line is rounded half-up and totals are sums of rounded lines`() {
        val default = PriceBook.default()
        fun isOne(t: BigDecimal) = t.compareTo(BigDecimal.ONE) == 0
        val book = default.copy(
            materialPricesPerKg = default.materialPricesPerKg.map { if (isOne(it.thicknessMm)) it.copy(pricePerKg = dec("0.1")) else it },
            laserSheetCutPerCm = default.laserSheetCutPerCm.map { if (isOne(it.thicknessMm)) it.copy(price = dec("1")) else it },
        )
        val input = CalculationInput(
            name = "Округление",
            quantity = 2,
            parts = listOf(
                Part(
                    name = "Лист",
                    qtyPerProduct = 3,
                    material = Material.Sheet(ST3, dec("1"), lengthMm = dec("1000"), widthMm = dec("1000")),
                    operations = listOf(PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("7.85")))),
                ),
            ),
            productOperations = emptyList(),
        )
        val result = book.priced(input)
        val part = result.parts.single()
        // Material 7.85 kg × 0.1 = 0.785 → 0.79 (half-even would give 0.78).
        assertKopecks("0.79", part.material.cost)
        // Laser 7.85 mm = 0.785 cm × 1 ₽/cm + 1 pierce × 0 = 0.785 → 0.79.
        assertKopecks("0.79", part.operations.single().cost)
        // 0.79 + 0.79 = 1.58, not round(0.785 + 0.785) = 1.57.
        assertKopecks("1.58", part.cost)
        assertKopecks("4.74", result.unitPrice)
        assertKopecks("9.48", result.total(2))
    }

    @Test
    fun `operation manual extra is part of its rounded line and keeps its comment`() {
        val book = PriceBook.default()
        val extra = ManualExtra(Money(dec("150")), "Снятие фаски")
        // 4 mm, 300 × 200: 100 cm × 40 + 1 × 10 = 4010; + 150 = 4160.00.
        val part = Material.Sheet(ST3, dec("4"), lengthMm = dec("300"), widthMm = dec("200"))
        val line = book.priced(onePart(part, PartOperation.LaserSheet(SheetCutMode.Rectangle, extra))).parts.single().operations.single()
        assertKopecks("4160.00", line.cost)
        assertEquals(extra, line.manualExtra)
    }

    @Test
    fun `unit price and mass count each part by its quantity per product`() {
        val input = CalculationInput(
            name = "Сборка",
            quantity = 4,
            parts = listOf(
                Part("Лист", 2, Material.Sheet(ST3, dec("1"), lengthMm = dec("1000"), widthMm = dec("1000")), emptyList()),
                Part("Труба", 3, roundTube, emptyList()),
            ),
            productOperations = listOf(
                ProductOperation.AssemblyWelding("полуавтомат", STEEL, dec("2"), listOf(WeldRow(dec("100"), 1))),
            ),
        )
        val result = PriceBook.default().priced(input)
        // Sheet: 7.85 kg × 100 = 785.00. Tube 57 × 3 × 1000: 3.9951633775701… kg × 300 = 1198.549… → 1198.55.
        // Assembly welding: 100 mm = 10 cm × 10 ₽/cm = 100.00.
        // 785.00 × 2 + 1198.55 × 3 + 100.00 = 1570.00 + 3595.65 + 100.00 = 5265.65; × 4 = 21062.60.
        assertKopecks("5265.65", result.unitPrice)
        assertKopecks("21062.60", result.total(4))
        // 7.85 × 2 + 3.9951633775701… × 3 = 15.70 + 11.9854901327104… = 27.6854901327104…
        assertDecimalRounded("27.6854901327104", result.unitMassKg)
    }

    @Test
    fun `the price book argument alone decides snapshot or current prices`() {
        val snapshot = PriceBook.default()
        val current = snapshot.copy(
            materialPricesPerKg = snapshot.materialPricesPerKg.map {
                if (it.thicknessMm.compareTo(dec("2")) == 0) it.copy(pricePerKg = dec("250")) else it
            },
        )
        assertKopecks("11280.00", snapshot.priced(spreadsheetInput).unitPrice)
        // 15.70 kg × 250 = 3925.00 instead of 3140.00: 11280.00 + 785.00 = 12065.00.
        assertKopecks("12065.00", current.priced(spreadsheetInput).unitPrice)
        // Recalculating on the snapshot afterwards still gives the old price.
        assertKopecks("11280.00", snapshot.priced(spreadsheetInput).unitPrice)
    }
}
