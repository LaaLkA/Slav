package io.github.laalka.slav.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** What the manager enters for one calculation (spec 3, 7.1); [quantity] is the run size. */
@Serializable
data class CalculationInput(
    val name: String,
    val quantity: Int,
    val parts: List<Part>,
    val productOperations: List<ProductOperation>,
)

/** [materialExtra] is the manual amount on the material line. */
@Serializable
data class Part(
    val name: String,
    val qtyPerProduct: Int,
    val material: Material,
    val operations: List<PartOperation>,
    @EncodeDefault val materialExtra: ManualExtra? = null,
)
