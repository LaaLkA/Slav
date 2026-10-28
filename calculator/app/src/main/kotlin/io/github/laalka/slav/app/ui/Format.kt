package io.github.laalka.slav.app.ui

import io.github.laalka.slav.core.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
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

/** An amount with the currency label of the settings: "11 280,00 ₽". */
fun formatMoney(money: Money, currency: String): String = "${formatMoney(money)} $currency".trimEnd()

/** A measured value such as a mass or an area, without trailing zeros: "15,7", "2,008", "1 250". */
fun formatMeasure(value: BigDecimal, fractionDigits: Int = 3): String =
    decimalFormat("#,##0." + "#".repeat(fractionDigits)).format(value)

/** The size of a file: "812 Б", "345,6 КБ", "12,3 МБ". */
fun formatFileSize(bytes: Long): String = when {
    bytes < KILOBYTE -> "$bytes Б"
    bytes < KILOBYTE * KILOBYTE -> "${formatMeasure(BigDecimal(bytes).divide(BigDecimal(KILOBYTE), 4, RoundingMode.HALF_UP), 1)} КБ"
    else -> "${formatMeasure(BigDecimal(bytes).divide(BigDecimal(KILOBYTE * KILOBYTE), 4, RoundingMode.HALF_UP), 1)} МБ"
}

private const val KILOBYTE = 1024L

// Strict: "31.02.2026" is not a date rather than the 28th.
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** "22.10.2026" */
fun formatDate(date: LocalDate): String = dateFormat.format(date)

/** "22.10.2026 14:05" in [zone]. */
fun formatDateTime(instant: Instant, zone: ZoneId): String = dateTimeFormat.format(instant.atZone(zone))

/** Reads a date typed as "22.10.2026"; null when it is not one. */
fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), dateFormat)
} catch (e: DateTimeParseException) {
    null
}
