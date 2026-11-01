package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.editor.title
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.QuickResult
import io.github.laalka.slav.core.sum
import java.math.BigDecimal

/** The lines a discount adds to a price: the sum before it, the discount with its percent, taken off. */
data class DiscountLines(val subtotal: String, val label: String, val amount: String)

/** One line of the price: what it is for and what it costs for one piece of the product, the «Доп. операции» included. */
data class SummaryLine(val title: String, val amount: String)

/**
 * The price of a calculation as an editor shows it: [lines] are what the sum before the discount is made of,
 * [discount] is present only when a discount is set, [unitPrice] is the price after it, [total] is the run total of a
 * full calculation and null for a simplified one, which has no run size.
 */
data class PriceSummary(
    val discount: DiscountLines?,
    val unitPrice: String,
    val total: String?,
    val lines: List<SummaryLine> = emptyList(),
)

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
    lines: List<SummaryLine> = emptyList(),
) = PriceSummary(
    discount = discountLines(subtotal, percent, discount, currency),
    unitPrice = formatMoney(unitPrice, currency),
    total = quantity?.let { formatMoney(unitPrice * it, currency) },
    lines = lines,
)

/**
 * The lines of a full calculation, summed over its parts: the materials as one line, then each kind of operation in
 * the order it first appears (a part's laser and another part's laser are one «Лазер»), then the operations of the
 * product itself. They add up to [CalcResult.subtotal].
 */
fun CalcResult.summaryLines(productOperations: List<ProductOperation>, currency: String): List<SummaryLine> {
    val costs = LinkedHashMap<String, Money>()
    fun add(title: String, cost: Money) {
        costs[title] = (costs[title] ?: Money.ZERO) + cost
    }
    if (parts.isNotEmpty()) add(MATERIAL_LINE, parts.map { it.material.cost }.sum())
    parts.forEach { part -> part.operations.forEach { add(it.kind.title, it.cost) } }
    productLines.forEach { add(it.productTitle(productOperations), it.cost) }
    return costs.map { (title, cost) -> SummaryLine(title, formatMoney(cost, currency)) }
}

/** The two lines of a simplified calculation, which add up to [QuickResult.subtotal]. */
fun QuickResult.summaryLines(currency: String): List<SummaryLine> = listOf(
    SummaryLine(QUICK_MATERIAL_TITLE, formatMoney(material.cost, currency)),
    SummaryLine(QUICK_CUT_TITLE, formatMoney(cut.cost, currency)),
)

private const val MATERIAL_LINE = "Материал"
