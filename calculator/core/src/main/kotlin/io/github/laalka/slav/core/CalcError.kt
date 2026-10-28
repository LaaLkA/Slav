package io.github.laalka.slav.core

import java.math.BigDecimal

/** Why a calculation cannot be priced (spec 7.3). All of them are reported at once. */
sealed interface CalcError {
    /**
     * An empty cell of [table] (a zero cell is a valid price). [row] and [column] are the headers
     * the user sees in the settings table; both are null for a single price: painting, laser by the minute.
     */
    data class MissingPrice(val table: PriceTable, val row: String?, val column: String?) : CalcError

    /** The part's grade is not in the price book: its density, group and material price are unknown. */
    data class UnknownGrade(val name: String) : CalcError

    /** The operation is not offered for this rolled kind (spec 6.1). */
    data class NotApplicable(val operation: OperationKind, val material: Material) : CalcError

    /** A tube-laser operation on a section larger than [limitMm], the tube-laser limit of the price book used. */
    data class SectionTooLarge(val operation: OperationKind, val material: Material, val limitMm: BigDecimal) : CalcError
}

/** A price table of spec 4.4–4.5. Bending has a table per material group, welding a table per weld type. */
sealed interface PriceTable {
    /** Grade × thickness, ₽/kg. */
    data object MaterialPerKg : PriceTable

    /** «Лазер, лист»: group × thickness. */
    data object LaserSheetCut : PriceTable

    /** «Пробитие, лист»: group × thickness. */
    data object LaserSheetPierce : PriceTable

    /** «Лазер, труборез»: group × thickness. */
    data object TubeLaserCut : PriceTable

    /** «Пробитие, труборез»: group × thickness. */
    data object TubeLaserPierce : PriceTable

    /** «Гибка» of [group]: thickness × bend range. */
    data class Bending(val group: String) : PriceTable

    /** «Сварка» of [weldType]: group × thickness. */
    data class Welding(val weldType: String) : PriceTable

    /** «Покраска порошковая»: a single value. */
    data object Painting : PriceTable

    /** «Лазер, ₽/мин» of the simplified calculation: a single value. */
    data object LaserCutPerMinute : PriceTable
}

/**
 * Collects the errors of one calculation. A step that hits an error records it and carries on
 * with zero, so the remaining lines are still checked and every error surfaces together.
 */
internal class Errors {
    private val found = mutableListOf<CalcError>()

    /** In the order found; the same missing cell needed by several lines is reported once. */
    val all: List<CalcError> get() = found.distinct()

    operator fun plusAssign(error: CalcError) {
        found += error
    }

    /** [price] if the cell is filled; otherwise records it as missing and returns zero. */
    fun price(price: BigDecimal?, table: PriceTable, row: String?, column: String?): BigDecimal =
        price ?: BigDecimal.ZERO.also { found += CalcError.MissingPrice(table, row, column) }
}
