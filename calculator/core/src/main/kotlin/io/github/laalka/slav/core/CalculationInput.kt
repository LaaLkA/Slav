package io.github.laalka.slav.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * What the manager enters for one calculation (spec 3, 7.1); [quantity] is the run size.
 * [counterparty] is the customer the calculation is for, free text, empty when not given.
 * [discountPercent] is the discount on the price of the product: null or 0 for none, otherwise
 * 0 < p < 100 (the editor checks it; a value outside 0 ≤ p < 100 is refused here). Defaults are always
 * encoded, so an input stored before these two fields existed reads as no counterparty and no discount.
 */
@Serializable
data class CalculationInput(
    val name: String,
    val quantity: Int,
    val parts: List<Part>,
    val productOperations: List<ProductOperation>,
    @EncodeDefault val counterparty: String = "",
    @EncodeDefault val discountPercent: Decimal? = null,
) {
    init {
        requireValidDiscount(discountPercent)
    }
}

/**
 * One piece of the product: identical parts are entered as copies. [materialExtra] is the manual
 * amount on the material line. [massOverrideKg], the mass from the drawing, replaces the mass
 * computed from the dimensions everywhere it is used: the material line, the part and the product mass.
 */
@Serializable
data class Part(
    val name: String,
    val material: Material,
    val operations: List<PartOperation>,
    @EncodeDefault val materialExtra: ManualExtra? = null,
    @EncodeDefault val massOverrideKg: Decimal? = null,
) {
    fun massKg(densityKgM3: BigDecimal): BigDecimal = massOverrideKg ?: material.massKg(densityKgM3)
}

/**
 * The product's painted surface by its parts' dimensions (spec 6.7): Σ max(part area, [minPerPartM2]).
 * A small part still costs a minimum of paint and handling, so it never counts as less than that.
 * [densityOf] gives the density of a part's grade, which a sheet set by weight takes its area from;
 * a part whose grade is unknown (null) is skipped: the calculation reports it as UnknownGrade.
 */
fun List<Part>.paintAreaM2(minPerPartM2: BigDecimal, densityOf: (Part) -> BigDecimal?): BigDecimal =
    sumOf { part -> densityOf(part)?.let { part.material.paintAreaM2(it).max(minPerPartM2) } ?: BigDecimal.ZERO }
