package io.github.laalka.slav.core

import io.github.laalka.slav.core.CalcError.MissingPrice
import io.github.laalka.slav.core.Fixtures.STEEL
import io.github.laalka.slav.core.Fixtures.ST3
import io.github.laalka.slav.core.Fixtures.roundTube
import io.github.laalka.slav.core.Fixtures.sheet
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CalculatorTest {
    /** The golden case: one part «Кронштейн» and the welding of the assembly, on the default prices. */
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

    @Test
    fun `golden test on the default prices`() {
        val result = PriceBook.default().priced(goldenInput)
        val part = result.parts.single()
        // 1000 × 1000 × 2 mm = 0.002 m³ × 8000 = 16.00 kg; × 200 ₽/kg (Ст3, 2 mm) = 3200.00.
        assertDecimal("16.00", part.massKg)
        assertKopecks("3200.00", part.material.cost)
        // Laser: 4000 mm = 400 cm × 20 ₽/cm = 8000.00; 3 pierces × 0 = 0.
        // Bending: 4 bends of 80 mm («до 100», 2 mm) × 25 = 100.00.
        assertEquals(listOf(OperationKind.LASER_SHEET, OperationKind.BENDING), part.operations.map { it.kind })
        assertEquals(listOf("8000.00", "100.00"), part.operations.map { it.cost.amount.toPlainString() })
        assertKopecks("11300.00", part.cost)
        // Assembly welding: semi-automatic, steel, 2 mm, seam 40 mm = 4 cm × 10 ₽/cm = 40.00.
        assertEquals(listOf(OperationKind.WELDING), result.productLines.map { it.kind })
        assertKopecks("40.00", result.productLines.single().cost)
        // 3200 + 8000 + 0 + 100 + 40 = 11340.00; × 10 = 113400.00.
        assertKopecks("11340.00", result.unitPrice)
        assertKopecks("113400.00", result.total(10))
        assertDecimal("16.00", result.unitMassKg)
    }

    @Test
    fun `a rectangle with a mass from the drawing prices as it did before sheets could be set by weight`() {
        val withDrawingMass = goldenInput.copy(parts = goldenInput.parts.map { it.copy(massOverrideKg = dec("15.5")) })
        val result = PriceBook.default().priced(withDrawingMass)
        val part = result.parts.single()
        // The dimensions stay in the input and the laser, the bending and the welding do not change; only the mass does:
        // 15.5 kg × 200 ₽/kg = 3100.00 instead of 3200.00.
        assertDecimal("15.5", part.massKg)
        assertKopecks("3100.00", part.material.cost)
        assertEquals(listOf("8000.00", "100.00"), part.operations.map { it.cost.amount.toPlainString() })
        // 3100 + 8000 + 100 + 40 = 11240.00; × 10 = 112400.00.
        assertKopecks("11240.00", result.unitPrice)
        assertKopecks("112400.00", result.total(10))
    }

    @Test
    fun `a sheet set by weight is priced by that weight and needs no dimensions`() {
        val byMass = Part(
            name = "Пластина",
            material = Material.Sheet(ST3, dec("2"), SheetShape.ByMass(dec("16"))),
            operations = listOf(
                PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("4000"), pierces = 3)),
                PartOperation.Bending(listOf(BendRow(dec("80"), 4))),
            ),
        )
        val result = PriceBook.default().priced(goldenInput.copy(parts = listOf(byMass)))
        val part = result.parts.single()
        // 16 kg typed × 200 ₽/kg = 3200.00; the laser and the bending of the golden case are the same, so the whole case is 11340.00.
        assertDecimal("16", part.massKg)
        assertKopecks("3200.00", part.material.cost)
        assertKopecks("11340.00", result.unitPrice)
        assertKopecks("113400.00", result.total(10))
    }

    @Test
    fun `two missing cells yield two errors in one outcome`() {
        val broken = PriceBook.default().let { book ->
            book.copy(
                laserSheetCutPerCm = book.laserSheetCutPerCm.filterNot { it.thicknessMm.compareTo(dec("3")) == 0 },
                paintingPerM2 = null,
            )
        }
        val input = onePart(rectangleSheet("3", "400", "300"), PartOperation.LaserSheet(SheetCutMode.ByShape))
            .copy(productOperations = listOf(ProductOperation.Painting()))
        // Neither the 3 mm laser cell nor the painting price is filled.
        assertEquals(
            listOf(MissingPrice(PriceTable.LaserSheetCut, STEEL, "3"), MissingPrice(PriceTable.Painting, null, null)),
            broken.errorsOf(input),
        )
    }

    @Test
    fun `errors of every kind and every part are reported together`() {
        val bigTube = Material.RoundTube(ST3, diameterMm = dec("220"), wallMm = dec("6"), lengthMm = dec("1000"))
        val input = CalculationInput(
            name = "Ошибки",
            quantity = 1,
            parts = listOf(
                Part("Неизвестная марка", sheet.copy(grade = "AISI 304"), listOf(PartOperation.LaserSheet(SheetCutMode.ByShape))),
                Part("Гнутая труба", roundTube, listOf(PartOperation.Bending(listOf(BendRow(dec("50"), 1))))),
                Part("Большая труба", bigTube, listOf(PartOperation.TubeLaser())),
            ),
            productOperations = listOf(ProductOperation.Painting()),
        )
        assertEquals(
            listOf(
                CalcError.UnknownGrade("AISI 304"),
                CalcError.NotApplicable(OperationKind.BENDING, roundTube),
                CalcError.SectionTooLarge(OperationKind.TUBE_LASER, bigTube, BigDecimal(200)),
                MissingPrice(PriceTable.Painting, null, null),
            ),
            PriceBook.default().copy(paintingPerM2 = null).errorsOf(input),
        )
    }

    @Test
    fun `a missing cell needed by several lines is reported once`() {
        val input = CalculationInput(
            name = "Две покраски",
            quantity = 1,
            parts = listOf(Part("А", sheet, emptyList()), Part("Б", roundTube, emptyList())),
            productOperations = listOf(ProductOperation.Painting(), ProductOperation.Painting(dec("2"))),
        )
        assertEquals(
            listOf(MissingPrice(PriceTable.Painting, null, null)),
            PriceBook.default().copy(paintingPerM2 = null).errorsOf(input),
        )
    }

    @Test
    fun `every line is rounded half-up and totals are sums of rounded lines`() {
        val default = PriceBook.default()
        fun isOne(t: BigDecimal) = t.compareTo(BigDecimal.ONE) == 0
        val book = default.copy(
            materialPricesPerKg = default.materialPricesPerKg.map { if (isOne(it.thicknessMm)) it.copy(pricePerKg = dec("0.1")) else it },
            laserSheetCutPerCm = default.laserSheetCutPerCm.map { if (isOne(it.thicknessMm)) it.copy(price = dec("1")) else it },
        )
        fun leaf(name: String) = Part(
            name = name,
            material = Material.Sheet(ST3, dec("1"), SheetShape.Rectangle(dec("1000"), dec("1000"))),
            operations = listOf(PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("7.85")))),
        )
        val input = CalculationInput(name = "Округление", quantity = 2, parts = listOf(leaf("Лист 1"), leaf("Лист 2")), productOperations = emptyList())
        val result = book.priced(input)
        val part = result.parts.first()
        // 1000 × 1000 × 1 mm = 0.001 m³ × 8000 = 8 kg; material 8 × 0.1 = 0.80.
        assertKopecks("0.80", part.material.cost)
        // Laser 7.85 mm = 0.785 cm × 1 ₽/cm + 1 pierce × 0 = 0.785 → 0.79 (half-even would give 0.78).
        assertKopecks("0.79", part.operations.single().cost)
        // 0.80 + 0.79 = 1.59; two parts make 3.18, the sum of rounded lines, not round(2 × 1.585) = 3.17.
        assertKopecks("1.59", part.cost)
        assertKopecks("3.18", result.unitPrice)
        assertKopecks("6.36", result.total(2))
    }

    @Test
    fun `operation manual extra is part of its rounded line and keeps its comment`() {
        val book = PriceBook.default()
        val extra = ManualExtra(Money(dec("150")), "Снятие фаски")
        // 4 mm, 300 × 200: 100 cm × 40 + 1 × 10 = 4010; + 150 = 4160.00.
        val line = book.priced(onePart(rectangleSheet("4", "300", "200"), PartOperation.LaserSheet(SheetCutMode.ByShape, extra)))
            .parts.single().operations.single()
        assertKopecks("4160.00", line.cost)
        assertEquals(extra, line.manualExtra)
    }

    @Test
    fun `unit price and mass add up the parts, identical parts are listed one by one`() {
        val leaf = Part("Лист", rectangleSheet("1", "1000", "1000"), emptyList())
        val input = CalculationInput(
            name = "Сборка",
            quantity = 4,
            parts = listOf(leaf, leaf, Part("Труба", roundTube, emptyList()), Part("Труба", roundTube, emptyList()), Part("Труба", roundTube, emptyList())),
            productOperations = listOf(ProductOperation.AssemblyWelding("полуавтомат", STEEL, dec("2"), dec("100"))),
        )
        val result = PriceBook.default().priced(input)
        // Sheet: 8 kg × 100 = 800.00. Tube 57 × 3 × 1000: 4.0715040790523… kg × 300 = 1221.4512237157… → 1221.45.
        // Assembly welding: 100 mm = 10 cm × 10 ₽/cm = 100.00.
        // 800.00 × 2 + 1221.45 × 3 + 100.00 = 1600.00 + 3664.35 + 100.00 = 5364.35; × 4 = 21457.40.
        assertKopecks("5364.35", result.unitPrice)
        assertKopecks("21457.40", result.total(4))
        // 8 × 2 + 4.0715040790523… × 3 = 16 + 12.2145122371571… = 28.2145122371571…
        assertDecimalRounded("28.214512237157", result.unitMassKg)
    }

    @Test
    fun `the mass from the drawing replaces the computed mass of every material kind in the material line`() {
        val book = PriceBook.default()
        for (material in Fixtures.allMaterials) {
            val computed = book.priced(onePart(material)).parts.single().massKg
            assertNotEquals(0, dec("10").compareTo(computed), "the drawing mass must differ from the computed one for $material")
            val input = onePart(material).let { it.copy(parts = it.parts.map { part -> part.copy(massOverrideKg = dec("10")) }) }
            val result = book.priced(input)
            val part = result.parts.single()
            val perKg = checkNotNull(book.materialPricesPerKg.priceFor(ST3, material.thicknessMm)) { "no price for $material" }
            assertDecimal("10", part.massKg)
            assertKopecks(Money(dec("10") * perKg).rounded().amount.toPlainString(), part.material.cost)
            assertDecimal("10", result.unitMassKg)
        }
    }

    @Test
    fun `the mass from the drawing is summed with the computed masses of the other parts`() {
        val input = CalculationInput(
            name = "Масса",
            quantity = 1,
            parts = listOf(
                Part("Лист", rectangleSheet("2", "1000", "1000"), emptyList(), massOverrideKg = dec("15.5")),
                Part("Лист", rectangleSheet("2", "1000", "1000"), emptyList()),
            ),
            productOperations = emptyList(),
        )
        val result = PriceBook.default().priced(input)
        // 15.5 kg from the drawing × 200 = 3100.00 and the computed 16 kg × 200 = 3200.00.
        assertEquals(listOf("3100.00", "3200.00"), result.parts.map { it.material.cost.amount.toPlainString() })
        assertDecimal("31.5", result.unitMassKg)
        assertKopecks("6300.00", result.unitPrice)
    }

    @Test
    fun `the price book argument alone decides snapshot or current prices`() {
        val snapshot = PriceBook.default()
        val current = snapshot.copy(
            materialPricesPerKg = snapshot.materialPricesPerKg.map {
                if (it.thicknessMm.compareTo(dec("2")) == 0) it.copy(pricePerKg = dec("250")) else it
            },
        )
        assertKopecks("11340.00", snapshot.priced(goldenInput).unitPrice)
        // 16 kg × 250 = 4000.00 instead of 3200.00: 11340.00 + 800.00 = 12140.00.
        assertKopecks("12140.00", current.priced(goldenInput).unitPrice)
        // Recalculating on the snapshot afterwards still gives the old price.
        assertKopecks("11340.00", snapshot.priced(goldenInput).unitPrice)
    }
}
