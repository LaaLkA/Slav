package io.github.laalka.slav.app.settings

import io.github.laalka.slav.core.BendPrice
import io.github.laalka.slav.core.BendRange
import io.github.laalka.slav.core.GradePrice
import io.github.laalka.slav.core.GroupPrice
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.Sortament
import io.github.laalka.slav.core.WeldPrice
import io.github.laalka.slav.core.priceFor
import java.math.BigDecimal

/** The group × thickness operation tables of spec 4.5; [unit] follows the currency label. */
enum class GroupTable(val title: String, val unit: String) {
    LASER_SHEET_CUT("Лазер, лист", "см"),
    LASER_SHEET_PIERCE("Пробитие, лист", "шт"),
    TUBE_LASER_CUT("Лазер, труборез", "см"),
    TUBE_LASER_PIERCE("Пробитие, труборез", "шт"),
    ;

    fun cells(book: PriceBook): List<GroupPrice> = when (this) {
        LASER_SHEET_CUT -> book.laserSheetCutPerCm
        LASER_SHEET_PIERCE -> book.laserSheetPiercePerPiece
        TUBE_LASER_CUT -> book.tubeLaserCutPerCm
        TUBE_LASER_PIERCE -> book.tubeLaserPiercePerPiece
    }

    fun withCells(book: PriceBook, cells: List<GroupPrice>): PriceBook = when (this) {
        LASER_SHEET_CUT -> book.copy(laserSheetCutPerCm = cells)
        LASER_SHEET_PIERCE -> book.copy(laserSheetPiercePerPiece = cells)
        TUBE_LASER_CUT -> book.copy(tubeLaserCutPerCm = cells)
        TUBE_LASER_PIERCE -> book.copy(tubeLaserPiercePerPiece = cells)
    }

    /** Sheet tables price sheets; the tube-laser tables price tubes and sections (spec 6.2–6.4). */
    val isSheetTable: Boolean get() = this == LASER_SHEET_CUT || this == LASER_SHEET_PIERCE
}

/** The address of one price in the settings. Thicknesses are [normalized], so a cell is one key under equals. */
sealed interface PriceCell {
    data class Material(val grade: String, val thicknessMm: BigDecimal) : PriceCell

    data class Group(val table: GroupTable, val group: String, val thicknessMm: BigDecimal) : PriceCell

    data class Bending(val group: String, val thicknessMm: BigDecimal, val range: BendRange) : PriceCell

    data class Welding(val weldType: String, val group: String, val thicknessMm: BigDecimal) : PriceCell

    data object Painting : PriceCell

    /** «Лазер, ₽/мин», the one operation price of the simplified calculation. */
    data object LaserCutPerMinute : PriceCell
}

/** The title of the laser price by the minute: the field in the settings, and the missing price of a calculation. */
fun laserCutPerMinuteTitle(currency: String): String = "Лазер, $currency/мин"

/** Read with the lookups the calculator uses, so the editor shows exactly the price a calculation takes. */
fun PriceBook.priceAt(cell: PriceCell): BigDecimal? = when (cell) {
    is PriceCell.Material -> materialPricesPerKg.priceFor(cell.grade, cell.thicknessMm)
    is PriceCell.Group -> cell.table.cells(this).priceFor(cell.group, cell.thicknessMm)
    is PriceCell.Bending -> bendingPerBend.priceFor(cell.group, cell.thicknessMm, cell.range)
    is PriceCell.Welding -> weldingPerCm.priceFor(cell.weldType, cell.group, cell.thicknessMm)
    PriceCell.Painting -> paintingPerM2
    PriceCell.LaserCutPerMinute -> laserCutPerMinute
}

/**
 * This book with [price] in [cell]; null empties the cell. The cell is matched the way the
 * calculator's lookups match it, and is left in the list once, in the place of the first match.
 */
fun PriceBook.withPrice(cell: PriceCell, price: BigDecimal?): PriceBook = when (cell) {
    is PriceCell.Material -> copy(
        materialPricesPerKg = materialPricesPerKg.withCell(price?.let { GradePrice(cell.grade, cell.thicknessMm, it) }) {
            it.grade == cell.grade && sameSize(it.thicknessMm, cell.thicknessMm)
        },
    )
    is PriceCell.Group -> cell.table.withCells(
        this,
        cell.table.cells(this).withCell(price?.let { GroupPrice(cell.group, cell.thicknessMm, it) }) {
            it.group == cell.group && sameSize(it.thicknessMm, cell.thicknessMm)
        },
    )
    is PriceCell.Bending -> copy(
        bendingPerBend = bendingPerBend.withCell(price?.let { BendPrice(cell.group, cell.thicknessMm, cell.range.toMm, it) }) {
            it.group == cell.group && sameSize(it.thicknessMm, cell.thicknessMm) && sameBound(it.upToMm, cell.range.toMm)
        },
    )
    is PriceCell.Welding -> copy(
        weldingPerCm = weldingPerCm.withCell(price?.let { WeldPrice(cell.weldType, cell.group, cell.thicknessMm, it) }) {
            it.weldType == cell.weldType && it.group == cell.group && sameSize(it.thicknessMm, cell.thicknessMm)
        },
    )
    PriceCell.Painting -> copy(paintingPerM2 = price)
    PriceCell.LaserCutPerMinute -> copy(laserCutPerMinute = price)
}

/** Without the rows of [group] in the operation tables: a deleted group leaves no prices behind. */
fun PriceBook.withoutGroupPrices(group: String): PriceBook {
    val tables = GroupTable.entries.fold(this) { book, table -> table.withCells(book, table.cells(book).filterNot { it.group == group }) }
    return tables.copy(
        bendingPerBend = bendingPerBend.filterNot { it.group == group },
        weldingPerCm = weldingPerCm.filterNot { it.group == group },
    )
}

fun PriceBook.withoutGradePrices(grade: String): PriceBook =
    copy(materialPricesPerKg = materialPricesPerKg.filterNot { it.grade == grade })

fun PriceBook.withoutWeldTypePrices(weldType: String): PriceBook =
    copy(weldingPerCm = weldingPerCm.filterNot { it.weldType == weldType })

/** Columns of the sheet tables: laser and pierce on a sheet, bending. */
fun Sortament.sheetThicknesses(): List<BigDecimal> = sheetThicknessesMm.distinctSizes()

/** Columns of the tube-laser tables: tube walls and section thicknesses, what the tube laser cuts (spec 6.3, 6.4). */
fun Sortament.longProductThicknesses(): List<BigDecimal> = (
    roundTubes.map { it.wallMm } + profileTubes.map { it.wallMm } +
        angles.map { it.thicknessMm } + channels.map { it.thicknessMm } + iBeams.map { it.thicknessMm }
    ).distinctSizes()

/** Every thickness of the sortament (spec 4.4): material prices and welding apply to every rolled kind. */
fun Sortament.allThicknesses(): List<BigDecimal> = (sheetThicknessesMm + longProductThicknesses()).distinctSizes()

private fun List<BigDecimal>.distinctSizes(): List<BigDecimal> = map { it.normalized() }.distinct().sorted()

private fun sameSize(a: BigDecimal, b: BigDecimal) = a.compareTo(b) == 0

private fun sameBound(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else sameSize(a, b)

private inline fun <T> List<T>.withCell(replacement: T?, matches: (T) -> Boolean): List<T> {
    val first = indexOfFirst(matches)
    if (first < 0) return if (replacement == null) this else this + replacement
    val result = ArrayList<T>(size)
    forEachIndexed { index, element ->
        when {
            index == first -> if (replacement != null) result += replacement
            !matches(element) -> result += element
        }
    }
    return result
}
