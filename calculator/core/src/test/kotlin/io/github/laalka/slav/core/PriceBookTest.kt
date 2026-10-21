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
        assertEquals(PriceBook.FORMAT_VERSION, book.formatVersion)
        assertEquals("₽", general.currencySymbol)
        assertEquals(listOf("100", "300", "500", "1000"), general.bendRangeBoundsMm.map { it.toPlainString() })
        assertEquals(listOf("полуавтомат", "аргон", "лазер"), general.weldTypes)
        assertDecimal("200", general.tubeLaserMaxSectionMm)
        assertEquals(listOf(MaterialGroup(STEEL)), book.groups)
        assertEquals(listOf(Grade(ST3, STEEL, dec("7850"))), book.grades)
        assertEquals(thicknesses, book.sortament.sheetThicknessesMm.map { it.toPlainString() })
    }

    @Test
    fun `default prices are the spreadsheet example`() {
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
        assertEquals(13, book.bendingPerBend.size)
        assertEquals(13, book.weldingPerCm.size)
        assertTrue(book.tubeLaserCutPerCm.isEmpty())
        assertTrue(book.tubeLaserPiercePerPiece.isEmpty())
        assertNull(book.bendingPerBend.priceFor(STEEL, dec("2"), book.general.bendRangeFor(dec("101"))))
        assertNull(book.weldingPerCm.priceFor("аргон", STEEL, dec("2")))
        assertNull(book.paintingPerM2)
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
        assertEquals(listOf(Grade("Ст3сп", STEEL, dec("7850"))), renamed.grades)
        assertTrue(renamed.materialPricesPerKg.all { it.grade == "Ст3сп" })
        assertDecimal("2000", renamed.materialPricesPerKg.priceFor("Ст3сп", dec("20")))
        assertNull(renamed.materialPricesPerKg.priceFor(ST3, dec("20")))
        assertFailsWith<IllegalArgumentException> { book.renameGrade("09Г2С", "10ХСНД") }
    }
}
