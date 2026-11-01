package io.github.laalka.slav.app.developer

import io.github.laalka.slav.app.settings.GroupTable
import io.github.laalka.slav.app.settings.PriceCell
import io.github.laalka.slav.app.settings.SizeRow
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.contains
import io.github.laalka.slav.app.settings.normalized
import io.github.laalka.slav.app.settings.priceAt
import io.github.laalka.slav.app.settings.priceCells
import io.github.laalka.slav.app.settings.withPrice
import io.github.laalka.slav.app.settings.withRow
import io.github.laalka.slav.core.Grade
import io.github.laalka.slav.core.MaterialGroup
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.Sortament
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/** What a fill put into the settings: new grades, new sizes of the sortament, newly filled price cells. */
data class Added(val grades: Int, val sizes: Int, val prices: Int) {
    companion object {
        fun between(before: PriceBook, after: PriceBook) = Added(
            grades = after.grades.size - before.grades.size,
            sizes = after.sortament.sizeCount() - before.sortament.sizeCount(),
            // A price that was in the book already, shown or not, is not one the fill added.
            prices = after.priceCells().count { before.priceAt(it) == null && after.priceAt(it) != null },
        )

        private fun Sortament.sizeCount() =
            sheetThicknessesMm.size + roundTubes.size + profileTubes.size + angles.size + channels.size + iBeams.size
    }
}

/**
 * Developer mode: fills the settings tables with plausible data so a calculation can be made on an empty
 * book. Each step is a transform of the [PriceBook], applied through `SettingsStore.update` so it is saved
 * and every tab refreshes. Names and sizes are only ever added, a price is put only into an empty cell:
 * nothing typed is renamed, removed or overwritten. Everything drawn comes from [random], so a seed
 * repeats a fill. After [all] on an empty book a [RandomCalculation] calculates Ok.
 */
class RandomSettings(private val random: Random) {
    /** «Материалы», «Сортамент», «Цены материала» and «Цены операций», in this order. */
    fun all(): (PriceBook) -> PriceBook {
        val steps = listOf(materials(), sortament(), materialPrices(), operationPrices())
        return { book -> steps.fold(book) { filled, step -> step(filled) } }
    }

    /**
     * With fewer than [MIN_GROUPS] groups or [MIN_GRADES] grades, the basic groups and grades whose names
     * the book does not have yet; with enough, one more grade of a name it does not have.
     */
    fun materials(): (PriceBook) -> PriceBook = { book ->
        if (book.groups.size < MIN_GROUPS || book.grades.size < MIN_GRADES) withBasicMaterials(book) else withOneMoreGrade(book)
    }

    /** Every size of the catalog the sortament does not have yet, by the rule «Сортамент» itself refuses duplicates by. */
    fun sortament(): (PriceBook) -> PriceBook = { book ->
        book.copy(
            sortament = SortamentKind.entries.fold(book.sortament) { sortament, kind ->
                SORTAMENT.getValue(kind)
                    .filterNot { kind.contains(sortament, it) }
                    .fold(sortament) { filled, row -> kind.withRow(filled, row) }
            },
        )
    }

    /** A price in every empty cell of «Цены материала». */
    fun materialPrices(): (PriceBook) -> PriceBook = { book -> book.withRandomPrices { it is PriceCell.Material } }

    /** A price in every empty cell of «Цены операций»: the tables by thickness, bending, welding, painting and the laser per minute. */
    fun operationPrices(): (PriceBook) -> PriceBook = { book -> book.withRandomPrices { it !is PriceCell.Material } }

    private fun withBasicMaterials(book: PriceBook): PriceBook {
        val groups = book.groups + BASIC_GROUPS.filter { name -> book.groups.none { sameName(it.name, name) } }.map(::MaterialGroup)
        val grades = BASIC_GRADES
            .filter { spec -> book.grades.none { sameName(it.name, spec.name) } }
            .map { spec -> Grade(spec.name, groups.first { sameName(it.name, spec.group) }.name, spec.density) }
        return book.copy(groups = groups, grades = book.grades + grades)
    }

    private fun withOneMoreGrade(book: PriceBook): PriceBook {
        val free = (BASIC_GRADES + MORE_GRADES).filter { spec -> book.grades.none { sameName(it.name, spec.name) } }
        val grade = when (val spec = free.randomOrNull(random)) {
            // The group of the catalog if the book has it, otherwise any group it has.
            null -> Grade(numberedGradeName(book), book.groups.random(random).name, BigDecimal(random.nextInt(MIN_DENSITY, MAX_DENSITY + 1)))
            else -> Grade(spec.name, (book.groups.firstOrNull { sameName(it.name, spec.group) } ?: book.groups.random(random)).name, spec.density)
        }
        return book.copy(grades = book.grades + grade)
    }

    /** «Марка 7»: the catalog of names is used up, so the name is a number that no grade has. */
    private fun numberedGradeName(book: PriceBook): String =
        generateSequence(book.grades.size + 1) { it + 1 }.map { "Марка $it" }.first { name -> book.grades.none { sameName(it.name, name) } }

    private fun PriceBook.withRandomPrices(isFilled: (PriceCell) -> Boolean): PriceBook =
        priceCells().filter { isFilled(it) && priceAt(it) == null }.fold(this) { book, cell -> book.withPrice(cell, price(priceRange(book, cell))) }

    /** A whole number of kopecks of [range], which has at most two decimals, so the price has them too. */
    private fun price(range: ClosedRange<BigDecimal>): BigDecimal {
        val from = range.start.movePointRight(KOPECK_DIGITS).toLong()
        val to = range.endInclusive.movePointRight(KOPECK_DIGITS).toLong()
        return BigDecimal.valueOf(random.nextLong(from, to + 1), KOPECK_DIGITS).normalized()
    }

    companion object {
        const val MIN_GROUPS = 2
        const val MIN_GRADES = 3

        private const val KOPECK_DIGITS = 2
        private const val MIN_DENSITY = 2_600
        private const val MAX_DENSITY = 8_000

        /**
         * The prices a cell may take, at most two decimals, the least one above zero. Material is ₽/kg by the
         * group of its grade; the laser, a pierce, a bend and a weld grow with the thickness (a bend also with
         * its length range); painting is ₽/m² and the laser by the minute ₽/min.
         */
        fun priceRange(book: PriceBook, cell: PriceCell): ClosedRange<BigDecimal> = when (cell) {
            is PriceCell.Material -> materialRange(book.grades.firstOrNull { it.name == cell.grade }?.group.orEmpty())
            is PriceCell.Group -> when (cell.table) {
                GroupTable.LASER_SHEET_CUT -> byThickness(cell.thicknessMm, "0", "0.2", "0", "0.4")
                GroupTable.TUBE_LASER_CUT -> byThickness(cell.thicknessMm, "0", "0.3", "0", "0.6")
                GroupTable.LASER_SHEET_PIERCE, GroupTable.TUBE_LASER_PIERCE -> byThickness(cell.thicknessMm, "1", "0.5", "3", "1.5")
            }
            is PriceCell.Bending -> {
                val index = book.general.bendRanges().indexOfFirst { sameBound(it.toMm, cell.range.toMm) }.coerceAtLeast(0)
                val longer = BigDecimal.ONE + BigDecimal("0.5") * BigDecimal(index)
                val base = byThickness(cell.thicknessMm, "10", "4", "25", "10")
                rangeOf(base.start * longer, base.endInclusive * longer)
            }
            is PriceCell.Welding -> byThickness(cell.thicknessMm, "2", "0.5", "5", "1.5")
            PriceCell.Painting -> rangeOf(BigDecimal(300), BigDecimal(800))
            PriceCell.LaserCutPerMinute -> rangeOf(BigDecimal(50), BigDecimal(150))
        }

        private fun materialRange(group: String): ClosedRange<BigDecimal> {
            val name = group.lowercase()
            return when {
                "нерж" in name -> rangeOf(BigDecimal(250), BigDecimal(450))
                "алюм" in name -> rangeOf(BigDecimal(200), BigDecimal(350))
                "сталь" in name -> rangeOf(BigDecimal(60), BigDecimal(120))
                else -> rangeOf(BigDecimal(60), BigDecimal(450))
            }
        }

        /** [lowBase] + [lowPerMm] × thickness to [highBase] + [highPerMm] × thickness. */
        private fun byThickness(thicknessMm: BigDecimal, lowBase: String, lowPerMm: String, highBase: String, highPerMm: String) =
            rangeOf(BigDecimal(lowBase) + BigDecimal(lowPerMm) * thicknessMm, BigDecimal(highBase) + BigDecimal(highPerMm) * thicknessMm)

        /** Rounded inwards to kopecks, so a price drawn from it is inside the exact range; never empty, never below a kopeck. */
        private fun rangeOf(low: BigDecimal, high: BigDecimal): ClosedRange<BigDecimal> {
            val from = low.setScale(KOPECK_DIGITS, RoundingMode.UP)
            return from..high.setScale(KOPECK_DIGITS, RoundingMode.DOWN).max(from)
        }

        /** The comparison of [io.github.laalka.slav.app.settings.nameError]: trimmed, in any letter case. */
        private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

        private fun sameBound(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else a.compareTo(b) == 0

        private class GradeSpec(val name: String, val group: String, density: Int) {
            val density: BigDecimal = BigDecimal(density)
        }

        private const val BLACK_STEEL = "Сталь чёрная"
        private const val STAINLESS = "Нержавейка"
        private const val ALUMINIUM = "Алюминий"

        private val BASIC_GROUPS = listOf(BLACK_STEEL, STAINLESS, ALUMINIUM)

        private val BASIC_GRADES = listOf(
            GradeSpec("Ст3", BLACK_STEEL, 7850),
            GradeSpec("09Г2С", BLACK_STEEL, 7850),
            GradeSpec("AISI 304", STAINLESS, 7900),
            GradeSpec("АМг5", ALUMINIUM, 2640),
        )

        /** Where the grades come from once the basic ones are in the book. */
        private val MORE_GRADES = listOf(
            GradeSpec("Ст20", BLACK_STEEL, 7850),
            GradeSpec("65Г", BLACK_STEEL, 7850),
            GradeSpec("AISI 316", STAINLESS, 8000),
            GradeSpec("12Х18Н10Т", STAINLESS, 7900),
            GradeSpec("AISI 430", STAINLESS, 7700),
            GradeSpec("АМг2", ALUMINIUM, 2680),
            GradeSpec("Д16", ALUMINIUM, 2780),
            GradeSpec("АД31", ALUMINIUM, 2710),
        )

        private fun size(vararg values: String) = SizeRow(designation = "", sizes = values.map(::BigDecimal))

        /** An angle, channel or I-beam: [values] are the sizes of its columns after the designation. */
        private fun designated(designation: String, vararg values: String) = SizeRow(designation, values.map(::BigDecimal))

        /** Rolled products as the standards list them (tubes, angles of GOST 8509, channels of GOST 8240, I-beams of GOST 8239). */
        private val SORTAMENT: Map<SortamentKind, List<SizeRow>> = mapOf(
            SortamentKind.SHEET to listOf("1", "1.5", "2", "2.5", "3", "4", "5", "6", "8", "10", "12", "14", "16", "18", "20").map { size(it) },
            SortamentKind.ROUND_TUBE to listOf(
                size("20", "2"), size("25", "2.5"), size("32", "2"), size("32", "3"), size("42", "3"), size("48", "3.5"),
                size("57", "3"), size("57", "4"), size("76", "3.5"), size("89", "4"), size("108", "4"), size("133", "4.5"),
                size("159", "5"), size("219", "6"), size("273", "7"),
            ),
            SortamentKind.PROFILE_TUBE to listOf(
                size("20", "20", "2"), size("25", "25", "2"), size("30", "30", "2"), size("40", "20", "2"), size("40", "40", "2"),
                size("40", "40", "3"), size("50", "25", "2"), size("50", "50", "3"), size("60", "30", "2"), size("60", "40", "3"),
                size("60", "60", "3"), size("80", "40", "3"), size("80", "60", "4"), size("80", "80", "4"), size("100", "50", "3"),
                size("100", "100", "4"), size("120", "120", "5"), size("150", "100", "5"), size("200", "200", "6"),
            ),
            SortamentKind.ANGLE to listOf(
                designated("20×20×3", "20", "20", "3", "0.89"), designated("25×25×3", "25", "25", "3", "1.12"),
                designated("25×25×4", "25", "25", "4", "1.46"), designated("32×32×4", "32", "32", "4", "1.91"),
                designated("40×40×4", "40", "40", "4", "2.42"), designated("40×40×5", "40", "40", "5", "2.97"),
                designated("45×45×5", "45", "45", "5", "3.37"), designated("50×50×4", "50", "50", "4", "3.05"),
                designated("50×50×5", "50", "50", "5", "3.77"), designated("63×63×5", "63", "63", "5", "4.81"),
                designated("63×63×6", "63", "63", "6", "5.72"), designated("75×75×6", "75", "75", "6", "6.89"),
                designated("90×90×7", "90", "90", "7", "9.64"), designated("100×100×8", "100", "100", "8", "12.25"),
                designated("125×125×10", "125", "125", "10", "19.1"),
            ),
            SortamentKind.CHANNEL to listOf(
                designated("5П", "50", "32", "4.4", "4.84"), designated("6,5П", "65", "36", "4.4", "5.9"),
                designated("8П", "80", "40", "4.5", "7.05"), designated("10П", "100", "46", "4.5", "8.59"),
                designated("12П", "120", "52", "4.8", "10.4"), designated("14П", "140", "58", "4.9", "12.3"),
                designated("16П", "160", "64", "5", "14.2"), designated("18П", "180", "70", "5.1", "16.3"),
                designated("20П", "200", "76", "5.2", "18.4"), designated("24П", "240", "90", "5.6", "24"),
                designated("30П", "300", "100", "6.5", "31.8"),
            ),
            SortamentKind.I_BEAM to listOf(
                designated("10", "100", "55", "4.5", "9.46"), designated("12", "120", "64", "4.8", "11.5"),
                designated("14", "140", "73", "4.9", "13.7"), designated("16", "160", "81", "5", "15.9"),
                designated("18", "180", "90", "5.1", "18.4"), designated("20", "200", "100", "5.2", "21"),
                designated("22", "220", "110", "5.4", "24"), designated("24", "240", "115", "5.6", "27.3"),
                designated("27", "270", "125", "6", "31.5"), designated("30", "300", "135", "6.5", "36.5"),
            ),
        )
    }
}
