package io.github.laalka.slav.app.developer

import io.github.laalka.slav.app.editor.read
import io.github.laalka.slav.app.emptyPriceBook
import io.github.laalka.slav.app.quick.read
import io.github.laalka.slav.app.settings.GroupTable
import io.github.laalka.slav.app.settings.PriceCell
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.allThicknesses
import io.github.laalka.slav.app.settings.priceAt
import io.github.laalka.slav.app.settings.priceCells
import io.github.laalka.slav.app.settings.withPrice
import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BeamSize
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.Grade
import io.github.laalka.slav.core.MaterialGroup
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculator
import io.github.laalka.slav.core.QuickOutcome
import io.github.laalka.slav.core.RoundTubeSize
import io.github.laalka.slav.core.Sortament
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RandomSettingsTest {
    private val empty = emptyPriceBook()
    private val default = PriceBook.default()

    private fun dec(text: String) = BigDecimal(text)

    private fun fill(book: PriceBook, seed: Int) = RandomSettings(Random(seed)).all()(book)

    private fun materials(book: PriceBook, seed: Int = 1) = RandomSettings(Random(seed)).materials()(book)

    private fun sortament(book: PriceBook) = RandomSettings(Random(1)).sortament()(book)

    private fun BigDecimal.sameAs(other: BigDecimal) = compareTo(other) == 0

    private fun PriceBook.priceEntries() = materialPricesPerKg.size + laserSheetCutPerCm.size + laserSheetPiercePerPiece.size +
        tubeLaserCutPerCm.size + tubeLaserPiercePerPiece.size + bendingPerBend.size + weldingPerCm.size +
        listOfNotNull(paintingPerM2, laserCutPerMinute).size

    private fun Sortament.sizeCount() = sheetThicknessesMm.size + roundTubes.size + profileTubes.size + angles.size + channels.size + iBeams.size

    // ---- Материалы ----

    @Test
    fun `an empty book gets the basic groups and grades, every grade in a group the book has`() {
        val book = materials(empty)

        assertEquals(listOf("Сталь чёрная", "Нержавейка", "Алюминий"), book.groups.map { it.name })
        assertEquals(
            listOf(
                Grade("Ст3", "Сталь чёрная", dec("7850")),
                Grade("09Г2С", "Сталь чёрная", dec("7850")),
                Grade("AISI 304", "Нержавейка", dec("7900")),
                Grade("АМг5", "Алюминий", dec("2640")),
            ),
            book.grades,
        )
    }

    @Test
    fun `fewer than two groups or three grades is topped up with the basic ones that are missing, by name in any letter case`() {
        // One group and one grade, spelled in another letter case than the catalog: neither is added again.
        val own = empty.copy(groups = listOf(MaterialGroup(" нержавейка ")), grades = listOf(Grade("ст3", " нержавейка ", dec("7000"))))

        val book = materials(own)

        assertEquals(listOf(" нержавейка ", "Сталь чёрная", "Алюминий"), book.groups.map { it.name })
        assertEquals(Grade("ст3", " нержавейка ", dec("7000")), book.grades.first(), "the grade typed before is as it was")
        // «AISI 304» belongs to «Нержавейка» of the catalog, which the book spells differently: it goes into the book's group.
        assertEquals(" нержавейка ", book.grades.single { it.name == "AISI 304" }.group)
        assertEquals(listOf("ст3", "09Г2С", "AISI 304", "АМг5"), book.grades.map { it.name })

        // Two groups but two grades is still too few grades.
        val twoGroups = empty.copy(
            groups = listOf(MaterialGroup("А"), MaterialGroup("Б")),
            grades = listOf(Grade("Ст3", "А", dec("7850")), Grade("Своя", "Б", dec("5000"))),
        )
        assertEquals(listOf("Ст3", "Своя", "09Г2С", "AISI 304", "АМг5"), materials(twoGroups).grades.map { it.name })
    }

    @Test
    fun `with enough groups and grades exactly one grade is added, never a name the book has, never a rename or a delete`() {
        var book = materials(empty)
        val groups = book.groups
        repeat(40) { press ->
            val next = materials(book, seed = press)

            assertEquals(book.grades.size + 1, next.grades.size, "press $press adds one grade")
            assertEquals(book.grades, next.grades.take(book.grades.size), "press $press leaves the grades as they were")
            assertEquals(groups, next.groups, "press $press adds no group")
            val added = next.grades.last()
            assertTrue(book.grades.none { it.name.trim().equals(added.name.trim(), ignoreCase = true) }, "press $press repeats ${added.name}")
            assertTrue(added.group in groups.map { it.name }, "press $press: '${added.name}' is in a group the book does not have")
            assertTrue(added.densityKgM3.signum() > 0, "press $press: no density")
            book = next
        }
        // The catalog has twelve names; the rest are numbered, and the numbers do not repeat either.
        assertEquals(44, book.grades.size)
        assertEquals(44, book.grades.map { it.name.lowercase() }.toSet().size)
        assertTrue(book.grades.any { it.name.startsWith("Марка ") }, "the numbered names were not reached")
    }

    @Test
    fun `a book of the user's own groups gets the grade into a group of the catalog if it has it, otherwise into one it has`() {
        val own = empty.copy(
            groups = listOf(MaterialGroup("Медь"), MaterialGroup("Алюминий")),
            grades = listOf(Grade("М1", "Медь", dec("8940")), Grade("Д1", "Медь", dec("8900")), Grade("Свой", "Медь", dec("8000"))),
        )

        val groupsSeen = (0 until 60).map { seed -> materials(own, seed).grades.last() }.associate { it.name to it.group }

        assertTrue(groupsSeen.isNotEmpty())
        groupsSeen.forEach { (name, group) ->
            assertTrue(group in listOf("Медь", "Алюминий"), "$name went to '$group'")
            if (name in listOf("АМг5", "АМг2", "Д16", "АД31")) assertEquals("Алюминий", group, name)
        }
    }

    // ---- Сортамент ----

    @Test
    fun `an empty sortament gets every kind, without a size twice`() {
        val s = sortament(empty).sortament

        assertEquals(SortamentKind.entries.size, listOf(s.sheetThicknessesMm, s.roundTubes, s.profileTubes, s.angles, s.channels, s.iBeams).count { it.isNotEmpty() })
        assertEquals(s.sheetThicknessesMm.size, s.sheetThicknessesMm.map { it.stripTrailingZeros() }.toSet().size)
        assertTrue(s.sheetThicknessesMm.all { it >= dec("1") && it <= dec("20") }, "sheets are 1–20 mm: ${s.sheetThicknessesMm}")
        assertEquals(s.roundTubes.size, s.roundTubes.map { it.diameterMm.stripTrailingZeros() to it.wallMm.stripTrailingZeros() }.toSet().size)
        assertEquals(s.profileTubes.size, s.profileTubes.map { Triple(it.sideAMm.stripTrailingZeros(), it.sideBMm.stripTrailingZeros(), it.wallMm.stripTrailingZeros()) }.toSet().size)
        assertEquals(s.angles.size, s.angles.map { it.designation.lowercase() }.toSet().size)
        assertEquals(s.channels.size, s.channels.map { it.designation.lowercase() }.toSet().size)
        assertEquals(s.iBeams.size, s.iBeams.map { it.designation.lowercase() }.toSet().size)
        assertTrue(s.angles.all { it.massPerMeterKg.signum() > 0 && it.flangeAMm.signum() > 0 && it.thicknessMm.signum() > 0 })
        assertTrue((s.channels + s.iBeams).all { it.massPerMeterKg.signum() > 0 && it.heightMm.signum() > 0 && it.flangeWidthMm.signum() > 0 })
    }

    @Test
    fun `sizes the sortament has are kept as they are and not added again, however they are spelled`() {
        val own = default.sortament.copy(
            // 2.0 is the size «2» of the catalog; 17×1.2 is the user's own.
            sheetThicknessesMm = listOf(dec("2.0")),
            roundTubes = listOf(RoundTubeSize(dec("17"), dec("1.2")), RoundTubeSize(dec("20.0"), dec("2.00"))),
            // The designation is matched trimmed and in any letter case.
            angles = listOf(AngleSize(" 25×25×3 ", dec("25"), dec("25"), dec("3"), dec("9.99"))),
            channels = listOf(BeamSize("10п", dec("100"), dec("46"), dec("4.5"), dec("8.59"))),
            iBeams = emptyList(),
        )

        val s = sortament(default.copy(sortament = own)).sortament

        assertEquals(1, s.sheetThicknessesMm.count { it.sameAs(dec("2")) })
        assertEquals(dec("2.0"), s.sheetThicknessesMm.single { it.sameAs(dec("2")) }, "the typed spelling stays")
        assertTrue(RoundTubeSize(dec("17"), dec("1.2")) in s.roundTubes, "the user's own tube is gone")
        assertEquals(1, s.roundTubes.count { it.diameterMm.sameAs(dec("20")) && it.wallMm.sameAs(dec("2")) })
        assertEquals(1, s.angles.count { it.designation.trim().equals("25×25×3", ignoreCase = true) })
        assertEquals(dec("9.99"), s.angles.first().massPerMeterKg, "the typed angle stays first and as typed")
        assertEquals(1, s.channels.count { it.designation.trim().equals("10П", ignoreCase = true) })
        assertEquals("10п", s.channels.first().designation)
        assertTrue(s.iBeams.isNotEmpty(), "the empty kind is filled")
    }

    @Test
    fun `the sortament is only added to, the default one is kept whole and a second press adds nothing`() {
        val once = sortament(default)
        val s = once.sortament

        assertTrue(s.sheetThicknessesMm.containsAll(default.sortament.sheetThicknessesMm))
        assertTrue(s.roundTubes.containsAll(default.sortament.roundTubes))
        assertTrue(s.profileTubes.containsAll(default.sortament.profileTubes))
        // Rows with a designation stay in the order they were entered, the new ones after them.
        assertEquals(default.sortament.angles, s.angles.take(default.sortament.angles.size))
        assertEquals(default.sortament.channels, s.channels.take(default.sortament.channels.size))
        assertEquals(default.sortament.iBeams, s.iBeams.take(default.sortament.iBeams.size))
        assertTrue(s.sheetThicknessesMm.size > default.sortament.sheetThicknessesMm.size, "sheets were added to")
        assertEquals(once, sortament(once), "everything is in already")
    }

    // ---- Цены ----

    private fun withHoles(book: PriceBook): Pair<PriceBook, List<PriceCell>> {
        val cells = book.priceCells()
        val holes = cells.filterIndexed { index, _ -> index % 3 == 0 }
        return holes.fold(book) { acc, cell -> acc.withPrice(cell, null) } to holes
    }

    @Test
    fun `prices go only into empty cells and typed ones, zero included, are never overwritten`() {
        val (holey, holes) = withHoles(fill(default, seed = 0))
        // A typed zero is a price (a free operation), not an empty cell.
        val zero = PriceCell.Group(GroupTable.LASER_SHEET_PIERCE, "Сталь", dec("2"))
        val typed = holey.withPrice(zero, BigDecimal.ZERO)
        val before = typed.priceCells().associateWith { typed.priceAt(it) }

        val after = RandomSettings(Random(9)).materialPrices()(RandomSettings(Random(9)).operationPrices()(typed))

        before.forEach { (cell, price) ->
            val now = after.priceAt(cell)
            if (price != null) assertEquals(price, now, "a typed price of $cell was overwritten")
            else assertNotNull(now, "$cell stayed empty")
        }
        assertEquals(BigDecimal.ZERO, after.priceAt(zero))
        assertTrue(holes.any { before[it] == null && it != zero }, "no hole was made")
    }

    @Test
    fun `every empty cell of every price table is filled with a positive price of at most two decimals inside its range`() {
        val book = fill(empty, seed = 4)

        val cells = book.priceCells()
        assertTrue(cells.size > 500, "only ${cells.size} cells")
        cells.forEach { cell ->
            val price = assertNotNull(book.priceAt(cell), "$cell is empty")
            val range = RandomSettings.priceRange(book, cell)
            assertTrue(price.signum() > 0, "$cell: $price")
            assertTrue(price.stripTrailingZeros().scale() <= 2, "$cell: $price has more than two decimals")
            assertTrue(price in range, "$cell: $price is outside ${range.start}..${range.endInclusive}")
        }
        // The prices are not all one number.
        assertTrue(cells.mapNotNull { book.priceAt(it) }.toSet().size > 200)
    }

    @Test
    fun `a second fill of a book that has everything adds one grade and the prices of that grade only`() {
        val book = fill(empty, seed = 4)

        val next = RandomSettings(Random(5)).all()(book)

        assertEquals(book.priceCells().associateWith { book.priceAt(it) }, book.priceCells().associateWith { next.priceAt(it) })
        assertEquals(book.grades.size + 1, next.grades.size)
        assertEquals(book.sortament, next.sortament)
        val added = Added.between(book, next)
        assertEquals(Added(grades = 1, sizes = 0, prices = book.sortament.allThicknesses().size), added)
    }

    @Test
    fun `the ranges follow the unit of the table`() {
        val b = fill(empty, seed = 1)
        fun range(cell: PriceCell) = RandomSettings.priceRange(b, cell).let { it.start.toPlainString() to it.endInclusive.toPlainString() }

        // Material, ₽/kg, by the group of its grade.
        assertEquals("60.00" to "120.00", range(PriceCell.Material("Ст3", dec("4"))))
        assertEquals("250.00" to "450.00", range(PriceCell.Material("AISI 304", dec("4"))))
        assertEquals("200.00" to "350.00", range(PriceCell.Material("АМг5", dec("4"))))
        assertEquals("60.00" to "450.00", range(PriceCell.Material("нет такой марки", dec("4"))))
        // Laser, ₽/cm, and a pierce, ₽ a piece, grow with the thickness.
        assertEquals("2.00" to "4.00", range(PriceCell.Group(GroupTable.LASER_SHEET_CUT, "Сталь чёрная", dec("10"))))
        assertEquals("3.00" to "6.00", range(PriceCell.Group(GroupTable.TUBE_LASER_CUT, "Сталь чёрная", dec("10"))))
        assertEquals("6.00" to "18.00", range(PriceCell.Group(GroupTable.LASER_SHEET_PIERCE, "Сталь чёрная", dec("10"))))
        assertEquals("6.00" to "18.00", range(PriceCell.Group(GroupTable.TUBE_LASER_PIERCE, "Сталь чёрная", dec("10"))))
        // A bend, ₽ a bend: the longer ranges cost 1.5, 2, 2.5 and 3 times «до 100».
        val ranges = b.general.bendRanges()
        assertEquals("26.00" to "65.00", range(PriceCell.Bending("Сталь чёрная", dec("4"), ranges[0])))
        assertEquals("39.00" to "97.50", range(PriceCell.Bending("Сталь чёрная", dec("4"), ranges[1])))
        assertEquals("78.00" to "195.00", range(PriceCell.Bending("Сталь чёрная", dec("4"), ranges.last())))
        // Welding, ₽/cm; painting, ₽/m²; the laser by the minute, ₽/min.
        assertEquals("4.00" to "11.00", range(PriceCell.Welding("аргон", "Сталь чёрная", dec("4"))))
        assertEquals("300.00" to "800.00", range(PriceCell.Painting))
        assertEquals("50.00" to "150.00", range(PriceCell.LaserCutPerMinute))
    }

    @Test
    fun `a very thin size still has a range of at least a kopeck and a fill does not throw`() {
        val thin = default.copy(sortament = default.sortament.copy(sheetThicknessesMm = listOf(dec("0.01"))))
        val stripped = thin.priceCells().fold(thin) { book, cell -> book.withPrice(cell, null) }

        val filled = RandomSettings(Random(2)).all()(stripped)

        filled.priceCells().forEach { cell ->
            val price = assertNotNull(filled.priceAt(cell), "$cell")
            // A price the default book had for a size it no longer lists is kept as it was, and is not the fill's.
            if (stripped.priceAt(cell) == null) {
                assertTrue(price >= dec("0.01"), "$cell: $price")
                assertTrue(price in RandomSettings.priceRange(filled, cell), "$cell")
            }
        }
        val laser = RandomSettings.priceRange(filled, PriceCell.Group(GroupTable.LASER_SHEET_CUT, "Сталь", dec("0.01")))
        assertEquals(dec("0.01"), laser.start, "0,2 ₽ a mm of a hundredth of a millimetre rounds up to a kopeck")
        assertTrue(laser.endInclusive >= laser.start)
    }

    // ---- the whole fill ----

    @Test
    fun `a seed repeats a fill and another seed gives another one`() {
        assertEquals(fill(empty, 3), fill(empty, 3))
        assertEquals(fill(default, 3), fill(default, 3))
        assertTrue(fill(empty, 3) != fill(empty, 4))
        assertEquals(materials(materials(empty), seed = 8), materials(materials(empty), seed = 8))
        assertTrue((0 until 20).map { materials(materials(empty), seed = it).grades.last().name }.toSet().size > 1, "the extra grade is always the same")
    }

    @Test
    fun `what a fill added is counted by what it put into the tables, not by what the book shows afterwards`() {
        // A price typed for a thickness the sortament does not have yet is shown once the size is added: it is not the fill's.
        val typed = default.withPrice(PriceCell.Material("Ст3", dec("1.5")), dec("123"))
        assertTrue(dec("1.5") !in typed.sortament.allThicknesses(), "1.5 is in the default sortament")

        val filled = RandomSettings(Random(1)).all()(typed)

        assertEquals(dec("123"), filled.priceAt(PriceCell.Material("Ст3", dec("1.5"))), "the typed price is kept when its size appears")
        // A fill only appends prices to the lists, so the prices it added are the growth of the lists.
        val added = Added.between(typed, filled)
        assertEquals(filled.priceEntries() - typed.priceEntries(), added.prices)
        val shown = filled.priceCells().count { typed.priceAt(it) == null }
        assertEquals(shown, added.prices, "a price that was in the book is not the fill's, whether or not it was shown")
        // The default book has one group and one grade, which is too few: the three basic grades it lacks are added.
        assertEquals(3, added.grades)
        assertEquals(filled.sortament.sizeCount() - typed.sortament.sizeCount(), added.sizes)
        assertTrue(added.sizes > 0 && added.prices > 0)
    }

    @Test
    fun `settings with nothing in them, after one fill, calculate 50 seeded random full and quick calculations Ok`() {
        // The same forms on the empty settings are what the fill is for: they cannot be calculated.
        assertTrue(RandomCalculation(empty, Random(0)).calculationForm { 0L }.read().errors.isNotEmpty())
        assertTrue(RandomCalculation(empty, Random(0)).quickForm().read().errors.isNotEmpty())

        val book = RandomSettings(Random(11)).all()(empty)

        val kinds = mutableSetOf<SortamentKind>()
        repeat(50) { seed ->
            var id = 0L
            val form = RandomCalculation(book, Random(seed)).calculationForm { id++ }
            val read = form.read()
            assertEquals(emptyMap(), read.errors, "full seed $seed reads with errors")
            assertIs<CalcOutcome.Ok>(Calculator(book).calculate(read.input), "full seed $seed")
            kinds += form.parts.map { it.material.kind }

            val quick = RandomCalculation(book, Random(seed)).quickForm().read()
            assertEquals(emptyMap(), quick.errors, "quick seed $seed reads with errors")
            assertIs<QuickOutcome.Ok>(QuickCalculator(book).calculate(quick.input), "quick seed $seed")
        }
        assertEquals(SortamentKind.entries.toSet(), kinds, "the calculations did not use every kind of the sortament")
    }
}
