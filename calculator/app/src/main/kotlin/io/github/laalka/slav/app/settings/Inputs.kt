package io.github.laalka.slav.app.settings

import java.math.BigDecimal

/** A text field's content and the error shown under it. */
data class FieldState(val text: String = "", val error: String? = null)

/** What a number field holds, as the settings editors read it. */
sealed interface NumberInput {
    /** Nothing typed. In a price cell this is «нет цены», which is not zero (spec 4.5). */
    data object Empty : NumberInput

    data class Valid(val value: BigDecimal) : NumberInput

    data class Invalid(val message: String) : NumberInput
}

const val NOT_A_NUMBER = "Не число"
const val NOT_POSITIVE = "Больше нуля"
const val REQUIRED = "Заполните"

// Digits with an optional fraction after a comma or a dot. A trailing separator is accepted, so
// "2," on the way to "2,5" is not flagged while the user is still typing.
private val decimalPattern = Regex("""\d+([.,]\d*)?|[.,]\d+""")

/** Reads a decimal typed with a comma or a dot: "2,5" and "2.5" are 2.5. Spaces between digit groups are ignored. */
fun parseDecimal(text: String): BigDecimal? {
    val compact = text.filterNot(Char::isWhitespace)
    return if (decimalPattern.matches(compact)) BigDecimal(compact.replace(',', '.')) else null
}

/** A price: empty means no price, zero is a price. */
fun readPrice(text: String): NumberInput =
    if (text.isBlank()) NumberInput.Empty else parseDecimal(text)?.let(NumberInput::Valid) ?: NumberInput.Invalid(NOT_A_NUMBER)

/** A size, a density or a limit: a number above zero; whether empty is allowed is the caller's decision. */
fun readPositive(text: String): NumberInput = when (val input = readPrice(text)) {
    is NumberInput.Valid -> if (input.value.signum() > 0) input else NumberInput.Invalid(NOT_POSITIVE)
    else -> input
}

/** The error of a required positive number, null when [input] is valid. */
fun requiredPositiveError(input: NumberInput): String? = when (input) {
    NumberInput.Empty -> REQUIRED
    is NumberInput.Invalid -> input.message
    is NumberInput.Valid -> null
}

/** A number as the editors show it: no trailing zeros, comma decimals ("2", "1,5"). */
fun BigDecimal.toInputText(): String = stripTrailingZeros().toPlainString().replace('.', ',')

/** One representation per value, so 2, 2.0 and 2.00 are one key under equals. */
fun BigDecimal.normalized(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

/**
 * Why [name] cannot be added next to [existing]; null when it can. Names are compared trimmed and
 * in any letter case, the way an import matches them (spec 9): «ст3» would merge into «Ст3».
 */
fun nameError(name: String, existing: List<String>, what: String): String? = when {
    name.isBlank() -> "Введите название"
    existing.any { it.trim().equals(name.trim(), ignoreCase = true) } -> "$what «${name.trim()}» уже есть"
    else -> null
}
