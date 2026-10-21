package io.github.laalka.slav.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal

// Price tables are lists of cells, not maps keyed by BigDecimal: BigDecimal("2") and
// BigDecimal("2.0") are different keys for equals/hashCode, so a thickness typed as "2,0" would
// miss the price stored for 2. Lookups compare with compareTo. A missing cell means "no price";
// a cell holding 0 is a valid free operation.

/** Material price, ₽/kg, for grade × thickness (spec 4.4). */
@Serializable
data class GradePrice(val grade: String, val thicknessMm: Decimal, val pricePerKg: Decimal)

/** One cell of a group × thickness operation table (spec 4.5). */
@Serializable
data class GroupPrice(val group: String, val thicknessMm: Decimal, val price: Decimal)

/** Price of one bend, ₽, for group × thickness × bend range; [upToMm] is the range's upper bound, null for the last range. */
@Serializable
data class BendPrice(val group: String, val thicknessMm: Decimal, val upToMm: Decimal?, val price: Decimal)

/** Welding price, ₽/cm, for weld type × group × thickness. */
@Serializable
data class WeldPrice(val weldType: String, val group: String, val thicknessMm: Decimal, val pricePerCm: Decimal)

// The two lists erase to the same JVM signature, hence the explicit names.
@JvmName("gradePriceFor")
fun List<GradePrice>.priceFor(grade: String, thicknessMm: BigDecimal): BigDecimal? =
    firstOrNull { it.grade == grade && it.thicknessMm.compareTo(thicknessMm) == 0 }?.pricePerKg

@JvmName("groupPriceFor")
fun List<GroupPrice>.priceFor(group: String, thicknessMm: BigDecimal): BigDecimal? =
    firstOrNull { it.group == group && it.thicknessMm.compareTo(thicknessMm) == 0 }?.price

fun List<BendPrice>.priceFor(group: String, thicknessMm: BigDecimal, range: BendRange): BigDecimal? =
    firstOrNull {
        it.group == group && it.thicknessMm.compareTo(thicknessMm) == 0 && sameBound(it.upToMm, range.toMm)
    }?.price

fun List<WeldPrice>.priceFor(weldType: String, group: String, thicknessMm: BigDecimal): BigDecimal? =
    firstOrNull {
        it.weldType == weldType && it.group == group && it.thicknessMm.compareTo(thicknessMm) == 0
    }?.pricePerCm

private fun sameBound(a: BigDecimal?, b: BigDecimal?) =
    if (a == null || b == null) a == b else a.compareTo(b) == 0
