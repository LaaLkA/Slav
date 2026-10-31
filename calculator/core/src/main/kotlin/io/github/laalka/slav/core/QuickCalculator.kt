package io.github.laalka.slav.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * What the manager enters for a simplified calculation («Упрощённый расчёт»): one sheet of one grade
 * and thickness, its area and the time the laser takes, with no parts and no product operations.
 * [materialExtra] and [cutExtra] are the manual amounts on the two lines. [counterparty] and
 * [discountPercent] are those of [CalculationInput]. Defaults are always encoded, so stored inputs
 * keep their meaning if a default changes later.
 *
 * A simplified calculation has no run size: its total is its unit price. [quantity] stays for the
 * inputs already stored and is always 1 in what the repository writes; the repository ignores any
 * other value, which an old row may hold.
 */
@Serializable
data class QuickCalculationInput(
    val name: String,
    val quantity: Int,
    val grade: String,
    val thicknessMm: Decimal,
    val sheetAreaM2: Decimal,
    val cutTimeMin: Decimal,
    @EncodeDefault val materialExtra: ManualExtra? = null,
    @EncodeDefault val cutExtra: ManualExtra? = null,
    @EncodeDefault val counterparty: String = "",
    @EncodeDefault val discountPercent: Decimal? = null,
) {
    init {
        requireValidDiscount(discountPercent)
    }

    /** Area × thickness × density: the mass of the sheet, which the material line is priced by. */
    fun massKg(densityKgM3: BigDecimal): BigDecimal = sheetAreaM2 * thicknessMm.mmToM() * densityKgM3
}

/**
 * Prices a [QuickCalculationInput] against one [PriceBook], as [Calculator] does a full one: the
 * material is its mass × the price per kg of the grade at the thickness, the cut is the laser time ×
 * the laser rate per minute. Each line takes its manual extra and is rounded half-up to kopecks.
 */
class QuickCalculator(private val priceBook: PriceBook) {
    fun calculate(input: QuickCalculationInput): QuickOutcome {
        val errors = Errors()
        val grade = priceBook.grades.firstOrNull { it.name == input.grade }
        if (grade == null) errors += CalcError.UnknownGrade(input.grade)
        val perMinute = errors.price(priceBook.laserCutPerMinute, PriceTable.LaserCutPerMinute, row = null, column = null)
        val cut = CutLine(lineCost(Money(input.cutTimeMin * perMinute), input.cutExtra), input.cutExtra)
        // Without the grade there is no density for the mass, nor a row to look the price per kg up by.
        if (grade == null) return QuickOutcome.Failed(errors.all)
        val massKg = input.massKg(grade.densityKgM3)
        val material = MaterialLine(
            lineCost(priceMaterial(input.thicknessMm, massKg, grade, priceBook, errors), input.materialExtra),
            input.materialExtra,
        )
        val found = errors.all
        return if (found.isEmpty()) QuickOutcome.Ok(QuickResult(massKg, material, cut, input.discountPercent)) else QuickOutcome.Failed(found)
    }
}

sealed interface QuickOutcome {
    data class Ok(val result: QuickResult) : QuickOutcome

    /** Every error found, so the user can fill all missing prices at once (spec 7.3). */
    data class Failed(val errors: List<CalcError>) : QuickOutcome
}

/**
 * The two lines of a simplified calculation. Both are already rounded, so the subtotal is their sum
 * and the unit price its difference with the discount, to the kopeck, as the breakdown of a full
 * calculation is. [discountPercent] is that of the input, null for none.
 */
data class QuickResult(
    val massKg: BigDecimal,
    val material: MaterialLine,
    val cut: CutLine,
    val discountPercent: BigDecimal? = null,
) {
    init {
        requireValidDiscount(discountPercent)
    }

    /** Material + cut: the price before the discount. */
    val subtotal: Money = material.cost + cut.cost

    /** [subtotal] × [discountPercent] / 100, rounded half-up to kopecks; zero without a discount. */
    val discount: Money = discountOf(subtotal, discountPercent)

    /** The price quoted: [subtotal] − [discount]. A simplified calculation is one piece, so this is also its total. */
    val unitPrice: Money = subtotal - discount

    /** [unitPrice] × [quantity]. */
    fun total(quantity: Int): Money = unitPrice * quantity
}

/** The cut line of a simplified calculation: the laser time at the rate per minute; [cost] includes [manualExtra]. */
data class CutLine(val cost: Money, val manualExtra: ManualExtra?)
