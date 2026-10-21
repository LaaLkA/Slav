package io.github.laalka.slav.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount in the settings currency.
 *
 * Arithmetic is exact; rounding happens only where the specification demands it (section 10:
 * every breakdown line is rounded half-up to kopecks). `equals` inherits BigDecimal scale
 * sensitivity (`10.0` != `10.00`), so compare amounts with [compareTo].
 */
@Serializable
@JvmInline
value class Money(val amount: Decimal) : Comparable<Money> {
    operator fun plus(other: Money) = Money(amount + other.amount)

    operator fun times(factor: BigDecimal) = Money(amount * factor)

    operator fun times(count: Int) = Money(amount * count.toBigDecimal())

    /** Rounds half-up to kopecks. */
    fun rounded() = Money(amount.setScale(2, RoundingMode.HALF_UP))

    override fun compareTo(other: Money) = amount.compareTo(other.amount)

    companion object {
        val ZERO = Money(BigDecimal.ZERO)
    }
}

fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)
