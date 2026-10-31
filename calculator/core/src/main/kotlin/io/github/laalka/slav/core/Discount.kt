package io.github.laalka.slav.core

import java.math.BigDecimal

private val HUNDRED = BigDecimal(100)

/**
 * The discount percent of a calculation: null or 0 is no discount, anything else is below 100, so a
 * price never reaches zero. The editor accepts 0 < p < 100 only; this is the guard under it, which
 * also holds for a value read back from storage.
 */
internal fun requireValidDiscount(percent: BigDecimal?) {
    require(percent == null || (percent.signum() >= 0 && percent < HUNDRED)) { "A discount is 0 ≤ p < 100 percent, got $percent" }
}

/** [subtotal] × [percent] / 100 rounded half-up to kopecks (spec 10); zero when there is no discount. */
internal fun discountOf(subtotal: Money, percent: BigDecimal?): Money =
    Money((subtotal.amount * (percent ?: BigDecimal.ZERO)).movePointLeft(2)).rounded()
