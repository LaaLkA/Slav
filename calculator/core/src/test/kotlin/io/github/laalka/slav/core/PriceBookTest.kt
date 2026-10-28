package io.github.laalka.slav.core

import io.github.laalka.slav.core.Fixtures.ST3
import io.github.laalka.slav.core.Fixtures.STEEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceBookTest {
    private val book = PriceBook.default()
    private val thicknesses = listOf("1", "2", "3", "4", "5", "6", "8", "10", "12", "14", "16", "18", "20")

    @Test
    fun `default settings follow the specification`() {
        val general = book.general
        assertEquals(2, PriceBook.FORMAT_VERSION)
        assertEquals(PriceBook.FORMAT_VERSION, book.formatVersion)
        assertEquals("₽", general.currencySymbol)
        assertEquals(listOf("100", "300", "500", "1000"), general.bendRangeBoundsMm.map { it.toPlainString() })
        assertEquals(listOf("полуавтомат", "аргон", "лазер"), general.weldTypes)
        assertDecimal("200", general.tubeLaserMaxSectionMm)
        assertDecimal("0.1", general.minPaintAreaPerPartM2)
        assertEquals(listOf(MaterialGroup(STEEL)), book.groups)
        assertEquals(listOf(Grade(ST3, STEEL, dec("8000"))), book.grades)
        assertEquals(thicknesses, book.sortament.sheetThicknessesMm.map { it.toPlainString() })
    }

    @Test
    fun `default sortament holds the sample sizes`() {
        val sortament = book.sortament
        assertEquals(
            listOf("20×2", "32×2", "57×3", "108×4", "219×6"),
            sortament.roundTubes.map { "${it.diameterMm.toPlainString()}×${it.wallMm.toPlainString()}" },
        )
        assertEquals(
            listOf("20×20×2", "40×20×2", "60×40×3", "100×100×4", "200×200×6"),
            sortament.profileTubes.map { "${it.sideAMm.toPlainString()}×${it.sideBMm.toPlainString()}×${it.wallMm.toPlainString()}" },
        )
        assertEquals(
            listOf("25×25×3" to "1.12", "50×50×5" to "3.77", "75×75×6" to "6.89", "100×100×8" to "12.25"),
            sortament.angles.map { it.designation to it.massPerMeterKg.toPlainString() },
        )
        assertEquals(
            listOf(listOf("10П", "100", "46", "4.5", "8.59"), listOf("16П", "160", "64", "5.0", "14.2"), listOf("20П", "200", "76", "5.2", "18.4")),
            sortament.channels.map { listOf(it.designation, it.heightMm, it.flangeWidthMm, it.thicknessMm, it.massPerMeterKg).map { v -> v.toString() } },
        )
        assertEquals(
            listOf(listOf("10Б1", "100", "55", "4.1", "8.1"), listOf("20Б1", "200", "100", "5.6", "22.4"), listOf("30Б1", "296", "140", "5.8", "32.9")),
            sortament.iBeams.map { listOf(it.designation, it.heightMm, it.flangeWidthMm, it.thicknessMm, it.massPerMeterKg).map { v -> v.toString() } },
        )
    }

    @Test
    fun `default prices keep the spreadsheet example`() {
        fun column(lookup: (java.math.BigDecimal) -> java.math.BigDecimal?) =
            thicknesses.map { lookup(dec(it))?.toPlainString() }

        assertEquals(
            listOf("100", "200", "300", "400", "500", "600", "800", "1000", "1200", "1400", "1600", "1800", "2000"),
            column { book.materialPricesPerKg.priceFor(ST3, it) },
        )
        assertEquals(
            listOf("10", "20", "30", "40", "50", "60", "80", "100", "120", "140", "160", "180", "200"),
            column { book.laserSheetCutPerCm.priceFor(STEEL, it) },
        )
        assertEquals(
            listOf("0", "0", "0", "10", "10", "15", "15", "20", "20", "25", "25", "30", "35"),
            column { book.laserSheetPiercePerPiece.priceFor(STEEL, it) },
        )
        val upTo100 = book.general.bendRangeFor(dec("100"))
        assertEquals(
            listOf("20", "25", "30", "35", "40", "60", "70", "100", "120", "140", "160", "180", "200"),
            column { book.bendingPerBend.priceFor(STEEL, it, upTo100) },
        )
        assertEquals(
            listOf("10", "10", "10", "10", "15", "15", "15", "15", "20", "20", "25", "25", "30"),
            column { book.weldingPerCm.priceFor("полуавтомат", STEEL, it) },
        )
    }

    @Test
    fun `default tube laser, bending, welding and painting prices are filled with test values`() {
        fun column(lookup: (java.math.BigDecimal) -> java.math.BigDecimal?) =
            thicknesses.map { lookup(dec(it))?.toPlainString() }

        assertEquals(
            listOf("15", "30", "45", "60", "75", "90", "120", "150", "180", "210", "240", "270", "300"),
            column { book.tubeLaserCutPerCm.priceFor(STEEL, it) },
        )
        assertEquals(
            listOf("5", "5", "5", "10", "10", "15", "15", "20", "20", "25", "25", "30", "35"),
            column { book.tubeLaserPiercePerPiece.priceFor(STEEL, it) },
        )
        // Range k costs «до 100» × (1 + 0.5 × k): a 2 mm bend is 25, 37.5, 50, 62.5, 75.
        val ranges = book.general.bendRanges()
        assertEquals(
            listOf("25", "37.5", "50", "62.5", "75"),
            ranges.map { book.bendingPerBend.priceFor(STEEL, dec("2"), it)?.toPlainString() },
        )
        assertEquals(
            listOf("200", "300", "400", "500", "600"),
            ranges.map { book.bendingPerBend.priceFor(STEEL, dec("20"), it)?.toPlainString() },
        )
        assertEquals(5 * 13, book.bendingPerBend.size)
        // Argon is twice and laser three times the semi-automatic price.
        for (t in thicknesses.map(::dec) + listOf(dec("4.5"), dec("5.8"))) {
            val semiAutomatic = checkNotNull(book.weldingPerCm.priceFor("полуавтомат", STEEL, t)) { "no price at $t" }
            assertDecimal((semiAutomatic * dec("2")).toPlainString(), book.weldingPerCm.priceFor("аргон", STEEL, t))
            assertDecimal((semiAutomatic * dec("3")).toPlainString(), book.weldingPerCm.priceFor("лазер", STEEL, t))
        }
        assertDecimal("450", book.paintingPerM2)
    }

    @Test
    fun `a section thickness between sheet thicknesses takes the lower sheet price`() {
        // Channel 10П has a 4.5 mm web and I-beam 20Б1 a 5.6 mm web: priced as 4 and 5 mm.
        assertDecimal("400", book.materialPricesPerKg.priceFor(ST3, dec("4.5")))
        assertDecimal("500", book.materialPricesPerKg.priceFor(ST3, dec("5.6")))
        assertDecimal("60", book.tubeLaserCutPerCm.priceFor(STEEL, dec("4.1")))
        assertDecimal("15", book.weldingPerCm.priceFor("полуавтомат", STEEL, dec("5.2")))
    }

    @Test
    fun `every sample size can be priced with every operation applicable to it`() {
        val sheets = book.sortament.sheetThicknessesMm.map { Material.Sheet(ST3, it, SheetShape.Rectangle(dec("300"), dec("200"))) }
        val tubes = book.sortament.roundTubes.map { Material.RoundTube(ST3, it.diameterMm, it.wallMm, dec("1000")) } +
            book.sortament.profileTubes.map { Material.ProfileTube(ST3, it.sideAMm, it.sideBMm, it.wallMm, dec("1000")) } +
            book.sortament.angles.map { Material.Angle(ST3, it, dec("1000")) } +
            book.sortament.channels.map { Material.Channel(ST3, it, dec("1000")) } +
            book.sortament.iBeams.map { Material.IBeam(ST3, it, dec("1000")) }
        fun operationsOf(material: Material): List<PartOperation> = buildList {
            if (isApplicable(OperationKind.LASER_SHEET, material, book)) add(PartOperation.LaserSheet(SheetCutMode.ByShape))
            if (isApplicable(OperationKind.TUBE_LASER, material, book)) add(PartOperation.TubeLaser())
            if (isApplicable(OperationKind.HOLES, material, book)) add(PartOperation.Holes(listOf(HoleRow(dec("10")))))
            // 50 mm and 2000 mm: the first and the last bend range.
            if (isApplicable(OperationKind.BENDING, material, book)) add(PartOperation.Bending(listOf(BendRow(dec("50"), 1), BendRow(dec("2000"), 1))))
        }
        for (material in sheets + tubes) {
            val input = CalculationInput(
                name = "Образец",
                quantity = 1,
                parts = listOf(Part("Образец", material, operationsOf(material))),
                productOperations = listOf(
                    ProductOperation.AssemblyWelding("лазер", STEEL, material.thicknessMm, dec("100")),
                    ProductOperation.Painting(),
                ),
            )
            assertTrue(Calculator(book).calculate(input) is CalcOutcome.Ok, "cannot price $material: ${Calculator(book).calculate(input)}")
        }
    }

    @Test
    fun `lookup matches thickness by value, not by scale`() {
        val cells = listOf(GroupPrice(STEEL, dec("2.0"), dec("20")), GroupPrice(STEEL, dec("2.5"), dec("25")))
        assertDecimal("20", cells.priceFor(STEEL, dec("2")))
        assertDecimal("20", cells.priceFor(STEEL, dec("2.00")))
        assertDecimal("25", cells.priceFor(STEEL, dec("2.50")))
        assertNull(cells.priceFor(STEEL, dec("3")))
        assertNull(cells.priceFor("Нержавеющая сталь", dec("2")))

        val grades = listOf(GradePrice(ST3, dec("2.0"), dec("200")))
        assertDecimal("200", grades.priceFor(ST3, dec("2")))
        assertNull(grades.priceFor("09Г2С", dec("2")))

        val welds = listOf(WeldPrice("аргон", STEEL, dec("2.0"), dec("12")))
        assertDecimal("12", welds.priceFor("аргон", STEEL, dec("2")))
        assertNull(welds.priceFor("полуавтомат", STEEL, dec("2")))

        val bends = listOf(BendPrice(STEEL, dec("2.0"), dec("100.0"), dec("25")), BendPrice(STEEL, dec("2"), null, dec("90")))
        assertDecimal("25", bends.priceFor(STEEL, dec("2"), BendRange(null, dec("100"))))
        assertDecimal("90", bends.priceFor(STEEL, dec("2"), BendRange(dec("1000"), null)))
        assertNull(bends.priceFor(STEEL, dec("2"), BendRange(dec("100"), dec("300"))))
    }

    @Test
    fun `zero price is a price, an absent cell is not`() {
        assertDecimal("0", book.laserSheetPiercePerPiece.priceFor(STEEL, dec("1")))
        assertNull(book.laserSheetPiercePerPiece.priceFor(STEEL, dec("7")))
    }

    @Test
    fun `bend range bound belongs to the lower range`() {
        val general = book.general
        assertEquals(
            listOf(
                BendRange(null, dec("100")),
                BendRange(dec("100"), dec("300")),
                BendRange(dec("300"), dec("500")),
                BendRange(dec("500"), dec("1000")),
                BendRange(dec("1000"), null),
            ),
            general.bendRanges(),
        )
        assertEquals(BendRange(null, dec("100")), general.bendRangeFor(dec("1")))
        assertEquals(BendRange(null, dec("100")), general.bendRangeFor(dec("100")))
        assertEquals(BendRange(null, dec("100")), general.bendRangeFor(dec("100.000")))
        assertEquals(BendRange(dec("100"), dec("300")), general.bendRangeFor(dec("100.1")))
        assertEquals(BendRange(dec("500"), dec("1000")), general.bendRangeFor(dec("1000")))
        assertEquals(BendRange(dec("1000"), null), general.bendRangeFor(dec("1000.01")))
    }

    @Test
    fun `bend ranges are ordered even when bounds are not`() {
        val general = book.general.copy(bendRangeBoundsMm = listOf(dec("500"), dec("100")))
        assertEquals(
            listOf(BendRange(null, dec("100")), BendRange(dec("100"), dec("500")), BendRange(dec("500"), null)),
            general.bendRanges(),
        )
        assertEquals(BendRange(dec("100"), dec("500")), general.bendRangeFor(dec("300")))
    }

    @Test
    fun `renameGroup updates grades and every group table`() {
        val stainless = "Нержавеющая сталь"
        val t = dec("2")
        val withEveryTable = book.copy(
            groups = book.groups + MaterialGroup(stainless),
            grades = book.grades + Grade("AISI 304", stainless, dec("7900")),
            tubeLaserCutPerCm = listOf(GroupPrice(STEEL, t, dec("15")), GroupPrice(stainless, t, dec("30"))),
            tubeLaserPiercePerPiece = listOf(GroupPrice(STEEL, t, dec("5"))),
            weldingPerCm = book.weldingPerCm + WeldPrice("аргон", stainless, t, dec("40")),
        )

        val renamed = withEveryTable.renameGroup(STEEL, "Чёрная сталь")

        assertEquals(listOf("Чёрная сталь", stainless), renamed.groups.map { it.name })
        assertEquals(listOf("Чёрная сталь", stainless), renamed.grades.map { it.group })
        val groupTables = listOf(
            renamed.laserSheetCutPerCm.map { it.group },
            renamed.laserSheetPiercePerPiece.map { it.group },
            renamed.tubeLaserCutPerCm.map { it.group },
            renamed.tubeLaserPiercePerPiece.map { it.group },
            renamed.bendingPerBend.map { it.group },
            renamed.weldingPerCm.map { it.group },
        )
        for (table in groupTables) {
            assertTrue(table.isNotEmpty())
            assertTrue(STEEL !in table, "old group name left in $table")
            assertTrue("Чёрная сталь" in table)
        }
        assertDecimal("30", renamed.tubeLaserCutPerCm.priceFor(stainless, t))
        assertDecimal("40", renamed.weldingPerCm.priceFor("аргон", stainless, t))
        assertDecimal("15", renamed.tubeLaserCutPerCm.priceFor("Чёрная сталь", t))
        assertDecimal("60", renamed.bendingPerBend.priceFor("Чёрная сталь", dec("6"), renamed.general.bendRangeFor(dec("50"))))
        // Grade-keyed material prices are not group references.
        assertEquals(withEveryTable.materialPricesPerKg, renamed.materialPricesPerKg)
    }

    @Test
    fun `renameGroup rejects unknown and taken names`() {
        val two = book.copy(groups = book.groups + MaterialGroup("Алюминий"))
        assertFailsWith<IllegalArgumentException> { two.renameGroup("Медь", "Латунь") }
        assertFailsWith<IllegalArgumentException> { two.renameGroup(STEEL, "Алюминий") }
        assertEquals(two, two.renameGroup(STEEL, STEEL))
    }

    @Test
    fun `renameGrade updates grades and material prices`() {
        val renamed = book.renameGrade(ST3, "Ст3сп")
        assertEquals(listOf(Grade("Ст3сп", STEEL, dec("8000"))), renamed.grades)
        assertTrue(renamed.materialPricesPerKg.all { it.grade == "Ст3сп" })
        assertDecimal("2000", renamed.materialPricesPerKg.priceFor("Ст3сп", dec("20")))
        assertNull(renamed.materialPricesPerKg.priceFor(ST3, dec("20")))
        assertFailsWith<IllegalArgumentException> { book.renameGrade("09Г2С", "10ХСНД") }
    }
}
