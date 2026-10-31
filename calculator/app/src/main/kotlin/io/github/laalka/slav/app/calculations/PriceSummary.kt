package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.core.Money
import java.math.BigDecimal

/** The lines a discount adds to a price: the sum before it, the discount with its percent, taken off. */
data class DiscountLines(val subtotal: String, val label: String, val amount: String)

/**
 * The price of a calculation as the bottom bar of an editor shows it: [discount] is present only when
 * a discount is set, [unitPrice] is the price after it, [total] is the run total of a full calculation
 * and null for a simplified one, which has no run size.
 */
data class PriceSummary(val discount: DiscountLines?, val unitPrice: String, val total: String?)

/** «Скидка (10 %)»: [percent] as the editors show it. */
fun discountLabel(percent: BigDecimal): String = "Скидка (${percent.toInputText()} %)"

/** A discount is taken off the price, so it is written with a minus: «−1 134,00 ₽». */
fun formatDiscount(amount: Money, currency: String): String = "−${formatMoney(amount, currency)}"

/** The discount lines of a price; null without a discount, which has nothing to say besides the price. */
fun discountLines(subtotal: Money, percent: BigDecimal?, discount: Money, currency: String): DiscountLines? =
    percent?.takeIf { it.signum() > 0 }?.let {
        DiscountLines(formatMoney(subtotal, currency), discountLabel(it), formatDiscount(discount, currency))
    }

/** [quantity] is null for a simplified calculation, which has no run size and so no run total. */
fun priceSummary(
    subtotal: Money,
    percent: BigDecimal?,
    discount: Money,
    unitPrice: Money,
    quantity: Int?,
    currency: String,
) = PriceSummary(
    discount = discountLines(subtotal, percent, discount, currency),
    unitPrice = formatMoney(unitPrice, currency),
    total = quantity?.let { formatMoney(unitPrice * it, currency) },
)
