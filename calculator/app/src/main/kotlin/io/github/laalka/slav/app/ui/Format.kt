package io.github.laalka.slav.app.ui

import io.github.laalka.slav.core.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// A plain space between thousands and a comma before decimals (spec 10). The locale's own grouping
// character differs between JDKs (no-break or narrow no-break space), so it is set here explicitly;
// money texts are drawn without soft wrapping instead of relying on a no-break separator.
private val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
    groupingSeparator = ' '
    decimalSeparator = ','
}

private fun decimalFormat(pattern: String) = DecimalFormat(pattern, symbols).apply { roundingMode = RoundingMode.HALF_UP }

/** An amount with kopecks: 11280 is "11 280,00". */
fun formatMoney(money: Money): String = decimalFormat("#,##0.00").format(money.amount)

/** A measured value such as a mass or an area, without trailing zeros: "15,7", "2,008", "1 250". */
fun formatMeasure(value: BigDecimal, fractionDigits: Int = 3): String =
    decimalFormat("#,##0." + "#".repeat(fractionDigits)).format(value)
