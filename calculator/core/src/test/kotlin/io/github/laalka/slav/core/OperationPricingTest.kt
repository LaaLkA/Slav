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
 * 0 / 0 / 0 / 10 / 10 ₽, bending up to 100 mm 20 / 25 / 30 / 35 / 40 ₽ (1.5 times that for
 * 100–300 mm), semi-automatic welding 10 / 10 / 10 / 10 / 15 ₽/cm. The tube-laser tables and the
 * painting price are replaced here by round numbers of their own, so a result does not depend on
 * the test values of the default book.
 */
class OperationPricingTest {
    private val book = PriceBook.default().copy(
        tubeLaserCutPerCm = listOf(steel("2", "25"), steel("3", "30"), steel("5", "50"), steel("6", "60")),
        tubeLaserPiercePerPiece = listOf(steel("2", "4"), steel("3", "5"), steel("5", "8"), steel("6", "9")),
        paintingPerM2 = dec("350"),
    )

    private fun steel(thicknessMm: String, price: String) = GroupPrice(STEEL, dec(thicknessMm), dec(price))

    private fun sheet(thicknessMm: String, lengthMm: String, widthMm: String) = rectangleSheet(thicknessMm, lengthMm, widthMm)

    // 6.2 Laser sheet

    @Test
    fun `laser by shape cuts the outline of a rectangle with one pierce`() {
        // 4 mm, 300 × 200: 2 × (300 + 200) = 1000 mm = 100 cm × 40 = 4000; 1 pierce × 10 = 10 → 4010.00.
        val cost = book.costOf(sheet("4", "300", "200"), PartOperation.LaserSheet(SheetCutMode.ByShape))
        assertKopecks("4010.00", cost)
    }

    @Test
    fun `laser by shape takes the cut length and the pierces from the shape`() {
        fun byShape(shape: SheetShape) = book.costOf(Material.Sheet(ST3, dec("4"), shape), PartOperation.LaserSheet(SheetCutMode.ByShape))
        // Circle R = 50, 4 mm: 2 × π × 50 = 314.159265358979… mm = 31.4159265358979… cm × 40 = 1256.637061435917…; 1 pierce × 10 → 1266.64.
        assertKopecks("1266.64", byShape(SheetShape.Circle(dec("50"))))
        // Ring R = 50, r = 20: 2 × π × 70 = 439.822971502571… mm = 43.9822971502571… cm × 40 = 1759.291886010284…; 2 pierces × 10 → 1779.29.
        assertKopecks("1779.29", byShape(SheetShape.Ring(dec("50"), dec("20"))))
        // Free: the typed perimeter 640 mm = 64 cm × 40 = 2560; 1 pierce × 10 → 2570.00, whatever the area.
        assertKopecks("2570.00", byShape(SheetShape.Free(areaMm2 = dec("20000"), perimeterMm = dec("640"))))
    }

    @Test
    fun `contour length ignores the shape`() {
        // 4 mm: 250 mm = 25 cm × 40 = 1000; 2 pierces × 10 = 20 → 1020.00 on a ring as on a rectangle.
        val op = PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("250"), pierces = 2))
        assertKopecks("1020.00", book.costOf(Material.Sheet(ST3, dec("4"), SheetShape.Ring(dec("50"), dec("20"))), op))
        assertKopecks("1020.00", book.costOf(sheet("4", "300", "200"), op))
    }

    @Test
    fun `laser by shape of a sheet set by weight cuts the typed perimeter with one pierce`() {
        // 4 mm, 12 kg, perimeter 3000 mm = 300 cm × 40 = 12000; 1 pierce × 10 = 10 → 12010.00.
        val byMass = Material.Sheet(ST3, dec("4"), SheetShape.ByMass(dec("12"), dec("3000")))
        assertKopecks("12010.00", book.costOf(byMass, PartOperation.LaserSheet(SheetCutMode.ByShape)))
    }

    @Test
    fun `laser by shape of a sheet set by weight without a perimeter is reported and the line is zero`() {
        val byMass = Material.Sheet(ST3, dec("4"), SheetShape.ByMass(dec("12")))
        val input = onePart(byMass, PartOperation.LaserSheet(SheetCutMode.ByShape))
        assertEquals(listOf(CalcError.MissingCutLength(byMass)), book.errorsOf(input))

        // The line itself is zero, not the 1 pierce × 10 ₽ the shape would still have.
        val errors = Errors()
        assertMoney("0", priceLaserSheet(PartOperation.LaserSheet(SheetCutMode.ByShape), byMass, STEEL, book, errors))
        assertEquals(listOf(CalcError.MissingCutLength(byMass)), errors.all)
    }

    @Test
    fun `a missing cut length is reported together with a missing price`() {
        val byMass = Material.Sheet(ST3, dec("4"), SheetShape.ByMass(dec("12")))
        val noFour = book.copy(laserSheetCutPerCm = book.laserSheetCutPerCm.filterNot { it.thicknessMm.compareTo(dec("4")) == 0 })
        val errors = noFour.errorsOf(onePart(byMass, PartOperation.LaserSheet(SheetCutMode.ByShape)))
        assertEquals(listOf(MissingPrice(PriceTable.LaserSheetCut, STEEL, "4"), CalcError.MissingCutLength(byMass)), errors)
    }

    @Test
    fun `laser contour length of a sheet set by weight needs no perimeter`() {
        // 4 mm: 250 mm = 25 cm × 40 = 1000; 2 pierces × 10 = 20 → 1020.00, as on any other shape.
        val byMass = Material.Sheet(ST3, dec("4"), SheetShape.ByMass(dec("12")))
        assertKopecks("1020.00", book.costOf(byMass, PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("250"), pierces = 2))))
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
        val errors = noThree.errorsOf(onePart(sheet("3", "300", "200"), PartOperation.LaserSheet(SheetCutMode.ByShape)))
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
        val noTubePrices = book.copy(tubeLaserCutPerCm = emptyList(), tubeLaserPiercePerPiece = emptyList())
        val errors = noTubePrices.errorsOf(onePart(roundTube, PartOperation.TubeLaser()))
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
        val noTubePrices = book.copy(tubeLaserCutPerCm = emptyList(), tubeLaserPiercePerPiece = emptyList())
        val errors = noTubePrices.errorsOf(onePart(profileTube, op))
        assertEquals(
            listOf(MissingPrice(PriceTable.TubeLaserCut, STEEL, "2"), MissingPrice(PriceTable.TubeLaserPierce, STEEL, "2")),
            errors,
        )
    }

    // 6.5 Bending

    @Test
    fun `bending prices each row by its length range`() {
        // 3 mm: 2 bends of 100 mm («до 100») × 30 = 60; 1 bend of 150 mm («100–300», 1.5 × 30) × 45 = 45 → 105.00.
        val op = PartOperation.Bending(listOf(BendRow(dec("100"), 2), BendRow(dec("150"), 1)))
        assertKopecks("105.00", book.costOf(sheet("3", "500", "300"), op))
    }

    @Test
    fun `a bend of exactly 100 mm is priced in the up-to-100 range`() {
        val part = sheet("3", "500", "300")
        assertKopecks("30.00", book.costOf(part, PartOperation.Bending(listOf(BendRow(dec("100"), 1)))))
        assertKopecks("30.00", book.costOf(part, PartOperation.Bending(listOf(BendRow(dec("100.0"), 1)))))
        assertKopecks("45.00", book.costOf(part, PartOperation.Bending(listOf(BendRow(dec("100.1"), 1)))))
    }

    @Test
    fun `bending without a price for the range is reported`() {
        val op = PartOperation.Bending(listOf(BendRow(dec("350"), 1)))
        val noRange = book.copy(bendingPerBend = book.bendingPerBend.filterNot { it.thicknessMm.compareTo(dec("3")) == 0 && it.upToMm?.compareTo(dec("500")) == 0 })
        val errors = noRange.errorsOf(onePart(sheet("3", "500", "300"), op))
        assertEquals(listOf(MissingPrice(PriceTable.Bending(STEEL), "3", "300–500")), errors)
    }

    @Test
    fun `bending a tube is not applicable`() {
        val errors = book.errorsOf(onePart(roundTube, PartOperation.Bending(listOf(BendRow(dec("50"), 1)))))
        assertEquals(listOf(CalcError.NotApplicable(OperationKind.BENDING, roundTube)), errors)
    }

    // 6.6 Welding

    @Test
    fun `assembly welding is the total seam length times the chosen price and its manual extra`() {
        val argon = book.copy(weldingPerCm = book.weldingPerCm.filterNot { it.weldType == "аргон" } + WeldPrice("аргон", STEEL, dec("3"), dec("18")))
        val welding = ProductOperation.AssemblyWelding(
            weldType = "аргон",
            group = STEEL,
            thicknessMm = dec("3"),
            seamLengthMm = dec("561"),
            manualExtra = ManualExtra(Money(dec("1000")), "Зачистка швов"),
        )
        // Argon, steel 3 mm, 18 ₽/cm: 561 mm = 56.1 cm × 18 = 1009.80; + 1000 → 2009.80.
        // The only part is a 1 mm sheet with no argon price: taking the part's thickness would fail.
        val input = onePart(sheet("1", "100", "100")).copy(productOperations = listOf(welding))
        val line = argon.priced(input).productLines.single()
        assertEquals(OperationKind.WELDING, line.kind)
        assertKopecks("2009.80", line.cost)
        assertEquals(welding.manualExtra, line.manualExtra)
    }

    @Test
    fun `assembly welding of a short seam in the default book`() {
        // Semi-automatic, steel, 2 mm, 10 ₽/cm: 40 mm = 4 cm → 40.00.
        val welding = ProductOperation.AssemblyWelding("полуавтомат", STEEL, dec("2"), dec("40"))
        assertKopecks("40.00", PriceBook.default().priced(onePart(sheet).copy(productOperations = listOf(welding))).productLines.single().cost)
    }

    @Test
    fun `assembly welding without a price for the weld type, group and thickness is reported`() {
        val input = onePart(angle).copy(
            productOperations = listOf(
                ProductOperation.AssemblyWelding("полуавтомат", "Нержавеющая сталь", dec("3"), dec("10")),
                ProductOperation.AssemblyWelding("газ", STEEL, dec("3"), dec("10")),
            ),
        )
        assertEquals(
            listOf(
                MissingPrice(PriceTable.Welding("полуавтомат"), "Нержавеющая сталь", "3"),
                MissingPrice(PriceTable.Welding("газ"), STEEL, "3"),
            ),
            book.errorsOf(input),
        )
    }

    // 6.7 Painting

    /** A 2 mm sheet of a free shape: its painted surface is 2 × area + perimeter × 2 mm². */
    private fun paintedSheet(areaMm2: String, perimeterMm: String) =
        Material.Sheet(ST3, dec("2"), SheetShape.Free(dec(areaMm2), dec(perimeterMm)))

    private fun productPainting(parts: List<Part>, painting: ProductOperation.Painting = ProductOperation.Painting()) =
        CalculationInput("Рама", 1, parts, listOf(painting))

    @Test
    fun `product painting area is the sum of part areas unless overridden`() {
        val input = productPainting(listOf(Part("Лист", sheet, emptyList()), Part("Труба", profileTube, emptyList())))
        // Sheet 1.006 m² + profile tube 2 × (40 + 20) × 6000 = 720 000 mm² = 0.72 m² → 1.726 m² × 350 = 604.10.
        assertKopecks("604.10", book.priced(input).productLines.single().cost)
        // Override 3 m² × 350 = 1050.00.
        assertKopecks("1050.00", book.priced(input.copy(productOperations = listOf(ProductOperation.Painting(dec("3"))))).productLines.single().cost)
    }

    @Test
    fun `a part smaller than the minimum is painted as the minimum`() {
        // 2 × 24 000 + 1000 × 2 = 50 000 mm² = 0.05 m² and 2 × 149 000 + 1000 × 2 = 300 000 mm² = 0.30 m².
        val small = Part("Малая", paintedSheet("24000", "1000"), emptyList())
        val large = Part("Большая", paintedSheet("149000", "1000"), emptyList())
        assertDecimal("0.05", small.material.paintAreaM2(dec("8000")))
        assertDecimal("0.3", large.material.paintAreaM2(dec("8000")))
        // The small part counts as 0.1: 0.1 + 0.30 = 0.40 m² × 350 = 140.00, not 0.35 m² = 122.50.
        assertKopecks("140.00", book.priced(productPainting(listOf(small, large))).productLines.single().cost)
    }

    @Test
    fun `the minimum comes from the settings and a part at the minimum is not raised`() {
        val small = Part("Малая", paintedSheet("24000", "1000"), emptyList())
        val large = Part("Большая", paintedSheet("149000", "1000"), emptyList())
        val input = productPainting(listOf(small, large))
        fun withMinimum(value: String) = book.copy(general = book.general.copy(minPaintAreaPerPartM2 = dec(value)))
        // Minimum 0.35: 0.35 + 0.35 = 0.70 m² × 350 = 245.00. Minimum 0: 0.05 + 0.30 = 0.35 m² = 122.50. Minimum 0.05: 0.35 m² as well.
        assertKopecks("245.00", withMinimum("0.35").priced(input).productLines.single().cost)
        assertKopecks("122.50", withMinimum("0").priced(input).productLines.single().cost)
        assertKopecks("122.50", withMinimum("0.05").priced(input).productLines.single().cost)
    }

    @Test
    fun `the area override replaces the minimum too, and the mass from the drawing never changes the area`() {
        val small = Part("Малая", paintedSheet("24000", "1000"), emptyList(), massOverrideKg = dec("50"))
        // Override 0.2 m² × 350 = 70.00 although the sum would be 0.1 m².
        assertKopecks("70.00", book.priced(productPainting(listOf(small), ProductOperation.Painting(dec("0.2")))).productLines.single().cost)
        // No override: the area of a 50 kg part is still its dimensions' 0.05 m², raised to 0.1 → 35.00.
        assertKopecks("35.00", book.priced(productPainting(listOf(small))).productLines.single().cost)
    }

    @Test
    fun `a sheet set by weight is painted by the area its weight gives`() {
        // 3 mm, 12 kg, perimeter 3000 mm: 2 × 12 / (0.003 × 8000) + 3000 × 3 / 1e6 = 1.009 m² × 350 = 353.15.
        val byMass = Material.Sheet(ST3, dec("3"), SheetShape.ByMass(dec("12"), dec("3000")))
        assertKopecks("353.15", book.priced(productPainting(listOf(Part("Пластина", byMass, emptyList())))).productLines.single().cost)
        // Without the perimeter: 1 m² × 350 = 350.00.
        val noPerimeter = byMass.copy(shape = SheetShape.ByMass(dec("12")))
        assertKopecks("350.00", book.priced(productPainting(listOf(Part("Пластина", noPerimeter, emptyList())))).productLines.single().cost)
    }

    @Test
    fun `a sheet set by weight below the minimum is painted as the minimum`() {
        // 2 mm, 0.1 kg: 2 × 0.1 / (0.002 × 8000) = 0.0125 m², counted as 0.1 → 0.1 × 350 = 35.00.
        val light = Part("Прокладка", Material.Sheet(ST3, dec("2"), SheetShape.ByMass(dec("0.1"))), emptyList())
        assertKopecks("35.00", book.priced(productPainting(listOf(light))).productLines.single().cost)
    }

    @Test
    fun `a sheet set by weight is painted by the density of the grade of the book it is priced on`() {
        val byMass = Part("Пластина", Material.Sheet(ST3, dec("3"), SheetShape.ByMass(dec("12"))), emptyList())
        val lighter = book.copy(grades = book.grades.map { it.copy(densityKgM3 = dec("4000")) })
        // 2 × 12 / (0.003 × 4000) = 2 m² × 350 = 700.00 against 350.00 at 8000 kg/m³.
        assertKopecks("700.00", lighter.priced(productPainting(listOf(byMass))).productLines.single().cost)
    }

    @Test
    fun `a part whose grade is unknown is left out of the area and reported as an unknown grade`() {
        val unknown = Part("Пластина", Material.Sheet("Нет такой", dec("3"), SheetShape.ByMass(dec("12"))), emptyList())
        val known = Part("Лист", sheet, emptyList())
        assertEquals(listOf(CalcError.UnknownGrade("Нет такой")), book.errorsOf(productPainting(listOf(unknown, known))))
        val densityOf = { part: Part -> book.grades.firstOrNull { it.name == part.material.grade }?.densityKgM3 }
        // Only the sheet counts: 1.006 m².
        assertDecimal("1.006", listOf(unknown, known).paintAreaM2(dec("0.1"), densityOf))
    }

    @Test
    fun `painting without a price is reported`() {
        // The painting price is deleted from the default book.
        val noPrice = PriceBook.default().copy(paintingPerM2 = null)
        val errors = noPrice.errorsOf(productPainting(listOf(Part("Лист", sheet, emptyList()))))
        assertEquals(listOf(MissingPrice(PriceTable.Painting, null, null)), errors)
    }

    // Material line (spec 5)

    @Test
    fun `material line is mass times price per kg plus the manual extra`() {
        // 57 × 3, 1000 mm: 4.0715040790523… kg (8000 kg/m³) × 300 ₽/kg = 1221.4512237157…; + 200.50 = 1421.9512237… → 1421.95.
        val extra = ManualExtra(Money(dec("200.50")), "Доставка")
        val part = book.priced(onePart(roundTube, materialExtra = extra)).parts.single()
        assertKopecks("1421.95", part.material.cost)
        assertEquals(extra, part.material.manualExtra)
        assertKopecks("1421.95", part.cost)
    }

    @Test
    fun `material without a price for the thickness is reported`() {
        // A channel with a 4.8 mm web: the default thickness list has no 4.8.
        val channel = Material.Channel(ST3, BeamSize("12П", dec("120"), dec("52"), dec("4.8"), dec("10.4")), dec("1000"))
        val errors = book.errorsOf(onePart(channel))
        assertEquals(listOf(MissingPrice(PriceTable.MaterialPerKg, ST3, "4.8")), errors)
    }

    @Test
    fun `unknown grade is reported`() {
        val errors = book.errorsOf(onePart(sheet.copy(grade = "09Г2С"), PartOperation.LaserSheet(SheetCutMode.ByShape)))
        assertEquals(listOf(CalcError.UnknownGrade("09Г2С")), errors)
    }
}
