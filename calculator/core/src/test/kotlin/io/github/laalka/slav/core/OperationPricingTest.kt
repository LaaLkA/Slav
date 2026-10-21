package io.github.laalka.slav.core

import io.github.laalka.slav.core.CalcError.MissingPrice
import io.github.laalka.slav.core.Fixtures.STEEL
import io.github.laalka.slav.core.Fixtures.ST3
import io.github.laalka.slav.core.Fixtures.angle
import io.github.laalka.slav.core.Fixtures.profileTube
import io.github.laalka.slav.core.Fixtures.roundTube
import io.github.laalka.slav.core.Fixtures.sheet
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One hand-computed test and one missing-price test per operation of spec 6.2–6.7. Default prices
 * for steel by thickness 1 / 2 / 3 / 4 / 5 mm: laser sheet 10 / 20 / 30 / 40 / 50 ₽/cm, sheet pierce
 * 0 / 0 / 0 / 10 / 10 ₽, bending up to 100 mm 20 / 25 / 30 / 35 / 40 ₽, semi-automatic welding
 * 10 / 10 / 10 / 10 / 15 ₽/cm. The tube-laser tables and the painting price are added here.
 */
class OperationPricingTest {
    private val book = PriceBook.default().copy(
        tubeLaserCutPerCm = listOf(steel("2", "25"), steel("3", "30"), steel("5", "50"), steel("6", "60")),
        tubeLaserPiercePerPiece = listOf(steel("2", "4"), steel("3", "5"), steel("5", "8"), steel("6", "9")),
        paintingPerM2 = dec("350"),
    )

    private fun steel(thicknessMm: String, price: String) = GroupPrice(STEEL, dec(thicknessMm), dec(price))

    private fun sheet(thicknessMm: String, lengthMm: String, widthMm: String) =
        Material.Sheet(ST3, dec(thicknessMm), lengthMm = dec(lengthMm), widthMm = dec(widthMm))

    // 6.2 Laser sheet

    @Test
    fun `laser sheet rectangle cuts the outline with one pierce`() {
        // 4 mm, 300 × 200: 2 × (300 + 200) = 1000 mm = 100 cm × 40 = 4000; 1 pierce × 10 = 10 → 4010.00.
        val cost = book.costOf(sheet("4", "300", "200"), PartOperation.LaserSheet(SheetCutMode.Rectangle))
        assertKopecks("4010.00", cost)
    }

    @Test
    fun `laser sheet contour length with several pierces`() {
        // 5 mm: 1234.5 mm = 123.45 cm × 50 = 6172.50; 3 pierces × 10 = 30 → 6202.50.
        val op = PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("1234.5"), pierces = 3))
        assertKopecks("6202.50", book.costOf(sheet("5", "1000", "500"), op))
    }

    @Test
    fun `laser sheet without a cut price for the thickness is reported`() {
        val noThree = book.copy(laserSheetCutPerCm = book.laserSheetCutPerCm.filterNot { it.thicknessMm.compareTo(dec("3")) == 0 })
        val errors = noThree.errorsOf(onePart(sheet("3", "300", "200"), PartOperation.LaserSheet(SheetCutMode.Rectangle)))
        assertEquals(listOf(MissingPrice(PriceTable.LaserSheetCut, STEEL, "3")), errors)
    }

    @Test
    fun `zero pierce price is a price, an empty pierce cell is not`() {
        // 2 mm: 100 mm = 10 cm × 20 = 200; 5 pierces × 0 = 0 → 200.00.
        val op = PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("100"), pierces = 5))
        assertKopecks("200.00", book.costOf(sheet, op))

        val noTwo = book.copy(laserSheetPiercePerPiece = book.laserSheetPiercePerPiece.filterNot { it.thicknessMm.compareTo(dec("2")) == 0 })
        assertEquals(listOf(MissingPrice(PriceTable.LaserSheetPierce, STEEL, "2")), noTwo.errorsOf(onePart(sheet, op)))
    }

    // 6.3 Tube laser

    @Test
    fun `tube laser cuts the section perimeter of a round tube`() {
        // 57 × 3: π × 57 = 179.0707812546… mm = 17.90707812546… cm; × 2 cuts × 30 = 1074.4246875… → 1074.42.
        assertKopecks("1074.42", book.costOf(roundTube, PartOperation.TubeLaser()))
    }

    @Test
    fun `tube laser on profile tube and angle`() {
        // 40 × 20 × 2: 2 × (40 + 20) = 120 mm = 12 cm × 4 cuts × 25 = 1200.00.
        assertKopecks("1200.00", book.costOf(profileTube, PartOperation.TubeLaser(cuts = 4)))
        // 50 × 50 × 5: 2 × (50 + 50) = 200 mm = 20 cm × 2 cuts × 50 = 2000.00.
        assertKopecks("2000.00", book.costOf(angle, PartOperation.TubeLaser()))
    }

    @Test
    fun `tube laser without a cut price is reported`() {
        // PriceBook.default() has no tube-laser prices.
        val errors = PriceBook.default().errorsOf(onePart(roundTube, PartOperation.TubeLaser()))
        assertEquals(listOf(MissingPrice(PriceTable.TubeLaserCut, STEEL, "3")), errors)
    }

    @Test
    fun `220 mm round tube is too large for the tube laser`() {
        val tube = Material.RoundTube(ST3, diameterMm = dec("220"), wallMm = dec("6"), lengthMm = dec("1000"))
        val errors = book.errorsOf(onePart(tube, PartOperation.TubeLaser()))
        assertEquals(listOf(CalcError.SectionTooLarge(OperationKind.TUBE_LASER, tube, BigDecimal(200))), errors)
    }

    // 6.4 Holes

    @Test
    fun `holes in a sheet use the sheet tables`() {
        // 4 mm, cut 40 ₽/cm, pierce 10:
        // 4 × (π × 10 / 10 × 40 + 10) = 4 × 135.6637061… = 542.6548245…
        // 1 × (π × 20 / 10 × 40 + 10) = 261.3274122…
        // Σ = 803.9822368… → 803.98.
        val op = PartOperation.Holes(listOf(HoleRow(dec("10"), count = 4), HoleRow(dec("20"), count = 1)))
        assertKopecks("803.98", book.costOf(sheet("4", "300", "200"), op))
    }

    @Test
    fun `hole through both walls of a tube counts twice at tube-laser prices`() {
        // 57 × 3, tube cut 30 ₽/cm, tube pierce 5: 2 holes × 2 walls × (π × 1 cm × 30 + 5) = 4 × 99.2477796… = 396.9911184… → 396.99.
        // At sheet prices (30 ₽/cm, pierce 0) it would be 376.99; counted once, 198.50.
        val op = PartOperation.Holes(listOf(HoleRow(dec("10"), count = 2, throughBothWalls = true)))
        assertKopecks("396.99", book.costOf(roundTube, op))
    }

    @Test
    fun `through both walls is ignored outside tubes`() {
        // Angle s = 5, tube cut 50 ₽/cm, tube pierce 8: 3 × (π × 1.2 cm × 50 + 8) = 3 × 196.4955592… = 589.4866776… → 589.49.
        val op = PartOperation.Holes(listOf(HoleRow(dec("12"), count = 3, throughBothWalls = true)))
        assertKopecks("589.49", book.costOf(angle, op))
    }

    @Test
    fun `holes without tube-laser prices report both cells`() {
        val op = PartOperation.Holes(listOf(HoleRow(dec("8"), count = 2)))
        val errors = PriceBook.default().errorsOf(onePart(profileTube, op))
        assertEquals(
            listOf(MissingPrice(PriceTable.TubeLaserCut, STEEL, "2"), MissingPrice(PriceTable.TubeLaserPierce, STEEL, "2")),
            errors,
        )
    }

    // 6.5 Bending

    private val withSecondRange = book.copy(
        bendingPerBend = book.bendingPerBend + BendPrice(STEEL, dec("3"), dec("300"), dec("45")),
    )

    @Test
    fun `bending prices each row by its length range`() {
        // 3 mm: 2 bends of 100 mm («до 100») × 30 = 60; 1 bend of 150 mm («100–300») × 45 = 45 → 105.00.
        val op = PartOperation.Bending(listOf(BendRow(dec("100"), 2), BendRow(dec("150"), 1)))
        assertKopecks("105.00", withSecondRange.costOf(sheet("3", "500", "300"), op))
    }

    @Test
    fun `a bend of exactly 100 mm is priced in the up-to-100 range`() {
        val part = sheet("3", "500", "300")
        assertKopecks("30.00", withSecondRange.costOf(part, PartOperation.Bending(listOf(BendRow(dec("100"), 1)))))
        assertKopecks("30.00", withSecondRange.costOf(part, PartOperation.Bending(listOf(BendRow(dec("100.0"), 1)))))
        assertKopecks("45.00", withSecondRange.costOf(part, PartOperation.Bending(listOf(BendRow(dec("100.1"), 1)))))
    }

    @Test
    fun `bending without a price for the range is reported`() {
        val op = PartOperation.Bending(listOf(BendRow(dec("350"), 1)))
        val errors = book.errorsOf(onePart(sheet("3", "500", "300"), op))
        assertEquals(listOf(MissingPrice(PriceTable.Bending(STEEL), "3", "300–500")), errors)
    }

    @Test
    fun `bending a tube is not applicable`() {
        val errors = book.errorsOf(onePart(roundTube, PartOperation.Bending(listOf(BendRow(dec("50"), 1)))))
        assertEquals(listOf(CalcError.NotApplicable(OperationKind.BENDING, roundTube)), errors)
    }

    // 6.6 Welding

    @Test
    fun `part welding takes group and thickness from the material`() {
        // Angle s = 5, semi-automatic 15 ₽/cm: (2 × 50 + 1 × 125) mm = 22.5 cm × 15 = 337.50.
        val op = PartOperation.Welding("полуавтомат", listOf(WeldRow(dec("50"), 2), WeldRow(dec("125"), 1)))
        assertKopecks("337.50", book.costOf(angle, op))
    }

    @Test
    fun `assembly welding uses the chosen group and thickness and its manual extra`() {
        val argon = book.copy(weldingPerCm = book.weldingPerCm + WeldPrice("аргон", STEEL, dec("3"), dec("18")))
        val welding = ProductOperation.AssemblyWelding(
            weldType = "аргон",
            group = STEEL,
            thicknessMm = dec("3"),
            rows = listOf(WeldRow(dec("80"), 6), WeldRow(dec("40.5"), 2)),
            manualExtra = ManualExtra(Money(dec("1000")), "Зачистка швов"),
        )
        // Argon, steel 3 mm, 18 ₽/cm: (6 × 80 + 2 × 40.5) mm = 561 mm = 56.1 cm × 18 = 1009.80; + 1000 → 2009.80.
        // The only part is a 1 mm sheet with no argon price: taking the part's thickness would fail.
        val input = onePart(sheet("1", "100", "100")).copy(productOperations = listOf(welding))
        val line = argon.priced(input).productLines.single()
        assertEquals(OperationKind.WELDING, line.kind)
        assertKopecks("2009.80", line.cost)
        assertEquals(welding.manualExtra, line.manualExtra)
    }

    @Test
    fun `welding without a price for the weld type is reported at part and product level`() {
        val input = onePart(angle, PartOperation.Welding("аргон", listOf(WeldRow(dec("50"), 1)))).copy(
            productOperations = listOf(
                ProductOperation.AssemblyWelding("полуавтомат", "Нержавеющая сталь", dec("3"), listOf(WeldRow(dec("10"), 1))),
            ),
        )
        assertEquals(
            listOf(
                MissingPrice(PriceTable.Welding("аргон"), STEEL, "5"),
                MissingPrice(PriceTable.Welding("полуавтомат"), "Нержавеющая сталь", "3"),
            ),
            book.errorsOf(input),
        )
    }

    // 6.7 Painting

    @Test
    fun `part painting area comes from the material or the override`() {
        // 2 mm, 1000 × 500: 2 × (1000 × 500 + 500 × 2 + 1000 × 2) = 1 006 000 mm² = 1.006 m² × 350 = 352.10.
        assertKopecks("352.10", book.costOf(sheet, PartOperation.Painting()))
        // Override 0.75 m² × 350 = 262.50.
        assertKopecks("262.50", book.costOf(sheet, PartOperation.Painting(areaOverrideM2 = dec("0.75"))))
    }

    @Test
    fun `product painting area is the sum of part areas times quantity unless overridden`() {
        fun input(painting: ProductOperation.Painting) = CalculationInput(
            name = "Рама",
            quantity = 1,
            parts = listOf(Part("Лист", 1, sheet, emptyList()), Part("Труба", 2, profileTube, emptyList())),
            productOperations = listOf(painting),
        )
        // Sheet 1.006 m² × 1 + profile tube 2 × (40 + 20) × 6000 = 720 000 mm² = 0.72 m² × 2 = 1.44 → 2.446 m² × 350 = 856.10.
        assertKopecks("856.10", book.priced(input(ProductOperation.Painting())).productLines.single().cost)
        // Override 3 m² × 350 = 1050.00.
        assertKopecks("1050.00", book.priced(input(ProductOperation.Painting(dec("3")))).productLines.single().cost)
    }

    @Test
    fun `painting without a price is reported`() {
        // PriceBook.default() has no painting price.
        val errors = PriceBook.default().errorsOf(onePart(sheet, PartOperation.Painting()))
        assertEquals(listOf(MissingPrice(PriceTable.Painting, null, null)), errors)
    }

    // Material line (spec 5)

    @Test
    fun `material line is mass times price per kg plus the manual extra`() {
        // 57 × 3, 1000 mm: 3.9951633775701… kg × 300 ₽/kg = 1198.5490132710…; + 200.50 = 1399.0490132… → 1399.05.
        val extra = ManualExtra(Money(dec("200.50")), "Доставка")
        val part = book.priced(onePart(roundTube, materialExtra = extra)).parts.single()
        assertKopecks("1399.05", part.material.cost)
        assertEquals(extra, part.material.manualExtra)
        assertKopecks("1399.05", part.cost)
    }

    @Test
    fun `material without a price for the thickness is reported`() {
        // Channel 10П has a 4.5 mm web; the default thickness list has no 4.5.
        val errors = book.errorsOf(onePart(Fixtures.channel))
        assertEquals(listOf(MissingPrice(PriceTable.MaterialPerKg, ST3, "4.5")), errors)
    }

    @Test
    fun `unknown grade is reported`() {
        val errors = book.errorsOf(onePart(sheet.copy(grade = "09Г2С"), PartOperation.Painting()))
        assertEquals(listOf(CalcError.UnknownGrade("09Г2С")), errors)
    }
}
