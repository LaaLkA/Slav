package io.github.laalka.slav.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** General settings (spec 4.6). */
@Serializable
data class GeneralSettings(
    val currencySymbol: String,
    /** Bend-length range bounds; a bound belongs to the lower range: a 100 mm bend is «до 100». */
    val bendRangeBoundsMm: List<Decimal>,
    val weldTypes: List<String>,
    /** Largest section the tube laser takes (spec 6.1). */
    val tubeLaserMaxSectionMm: Decimal,
) {
    /** Ranges in ascending order: «до b1», «b1–b2», …, «свыше bN». */
    fun bendRanges(): List<BendRange> {
        val bounds = bendRangeBoundsMm.sorted()
        return (listOf<BigDecimal?>(null) + bounds).zip(bounds + listOf<BigDecimal?>(null), ::BendRange)
    }

    fun bendRangeFor(lengthMm: BigDecimal): BendRange =
        bendRanges().first { it.toMm == null || lengthMm <= it.toMm }

    companion object {
        fun default() = GeneralSettings(
            currencySymbol = "₽",
            bendRangeBoundsMm = listOf(100, 300, 500, 1000).map(Int::toBigDecimal),
            weldTypes = listOf("полуавтомат", "аргон", "лазер"),
            tubeLaserMaxSectionMm = BigDecimal(200),
        )
    }
}

/**
 * Bend lengths in (fromMm, toMm]; null ends are open. A range is identified in price tables by
 * [toMm], so inserting a new bound leaves existing cells in the range they were entered for.
 */
data class BendRange(val fromMm: BigDecimal?, val toMm: BigDecimal?) {
    /** The column header of the bending table (spec 4.6): «до 100», «100–300», «свыше 1000». */
    val label: String
        get() = when {
            fromMm != null && toMm != null -> "${fromMm.toHeader()}–${toMm.toHeader()}"
            toMm != null -> "до ${toMm.toHeader()}"
            fromMm != null -> "свыше ${fromMm.toHeader()}"
            // No bounds configured: one range for every length.
            else -> "любая длина"
        }
}
