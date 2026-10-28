package io.github.laalka.slav.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** What the manager enters for one calculation (spec 3, 7.1); [quantity] is the run size. */
@Serializable
data class CalculationInput(
    val name: String,
    val quantity: Int,
    val parts: List<Part>,
    val productOperations: List<ProductOperation>,
)

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
 */
fun List<Part>.paintAreaM2(minPerPartM2: BigDecimal): BigDecimal = sumOf { it.material.paintAreaM2.max(minPerPartM2) }
