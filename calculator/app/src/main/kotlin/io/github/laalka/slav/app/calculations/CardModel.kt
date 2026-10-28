package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.editor.ReadableError
import io.github.laalka.slav.app.editor.readable
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.app.ui.formatMeasure
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.StoredCalculation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.ZoneId
import java.util.UUID

/** A breakdown line as the card draws it; [extra] names the manual amount and its comment. */
data class CardLine(val title: String, val extra: String?, val cost: String)

/** One part, one piece of the product; [details] says its mass. */
data class CardPart(val title: String, val details: String, val lines: List<CardLine>, val cost: String)

/** «Пересчитать по текущим ценам» before the user's answer (spec 8.3). */
data class RecalcPreview(
    val snapshotDate: String,
    val oldUnitPrice: String,
    val newUnitPrice: String,
    val oldTotal: String,
    val newTotal: String,
    val unchanged: Boolean,
)

/**
 * The card of spec 8.2. Money texts carry the currency label of the settings. A draft has no
 * price: its price, masses and breakdown are null or empty and it has no [statusOptions].
 */
data class CardState(
    val name: String,
    val status: CalculationStatus,
    /** The statuses the user sets by hand; empty for a draft. */
    val statusOptions: List<CalculationStatus>,
    /** What the run size field shows when it is (re)created. */
    val quantityText: String,
    val quantityError: String?,
    val unitPrice: String?,
    val total: String?,
    val unitMass: String?,
    val runMass: String?,
    val snapshotDate: String?,
    val parts: List<CardPart>,
    val productLines: List<CardLine>,
    val recalc: RecalcPreview?,
    val recalcErrors: List<ReadableError>,
) {
    val isPriced: Boolean get() = unitPrice != null
}

/**
 * The card of one calculation (spec 8.2, 8.3). The run size is saved as it is typed and the total
 * follows from the stored unit price, without recalculating. «Пересчитать по текущим ценам»
 * prices the input on the current settings and replaces the snapshot only once confirmed.
 */
class CardModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    val id: UUID,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var calculation: StoredCalculation = read()
    private var currency: String = settings.load().general.currencySymbol
    private var quantityText: String = calculation.quantity.toString()
    private var quantityError: String? = null

    /** The prices and result of the pending «Пересчитать по текущим ценам», applied as previewed. */
    private var pendingRecalc: Pair<PriceBook, CalcResult>? = null
    private var recalcErrors: List<ReadableError> = emptyList()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<CardState> = _state.asStateFlow()

    /** Reads the calculation and the currency label again, after either may have changed elsewhere. */
    fun reload() {
        calculation = read()
        currency = settings.load().general.currencySymbol
        quantityText = calculation.quantity.toString()
        quantityError = null
        pendingRecalc = null
        recalcErrors = emptyList()
        refresh()
    }

    /** Spec 8.2: a valid run size is saved at once and the total follows; the unit price and snapshot stay. */
    fun onQuantityChange(text: String) {
        quantityText = text
        val quantity = text.trim().toIntOrNull()?.takeIf { it > 0 }
        quantityError = when {
            text.isBlank() -> "Заполните"
            quantity == null -> "Целое число больше нуля"
            else -> null
        }
        if (quantity != null && quantity != calculation.quantity) {
            calculations.setQuantity(id, quantity)
            calculation = read()
        }
        refresh()
    }

    /** Отправлен, В работе or Архив, set by hand (spec 8.1). */
    fun setStatus(status: CalculationStatus) {
        if (status == calculation.status) return
        calculations.setStatus(id, status)
        calculation = read()
        refresh()
    }

    /**
     * Spec 8.3: prices the input on the current settings. Ok opens the old-versus-new question and
     * changes nothing yet; Failed lists every missing price (spec 7.3) and changes nothing either.
     */
    fun prepareRecalc() {
        if (calculation.result == null) return
        val current = settings.load()
        currency = current.general.currencySymbol
        when (val outcome = Calculator(current).calculate(calculation.input)) {
            is CalcOutcome.Ok -> {
                pendingRecalc = current to outcome.result
                recalcErrors = emptyList()
            }
            is CalcOutcome.Failed -> {
                pendingRecalc = null
                recalcErrors = outcome.errors.map { it.readable(onSnapshot = false) }
            }
        }
        refresh()
    }

    /** Saves the previewed result with the current prices as the new snapshot; the status becomes «Рассчитан». */
    fun confirmRecalc() {
        val (prices, result) = checkNotNull(pendingRecalc) { "No recalculation to confirm" }
        pendingRecalc = null
        calculations.saveCalculated(calculation.input, prices, result, id)
        calculation = read()
        refresh()
    }

    fun cancelRecalc() {
        pendingRecalc = null
        refresh()
    }

    fun dismissRecalcErrors() {
        recalcErrors = emptyList()
        refresh()
    }

    private fun read(): StoredCalculation = requireNotNull(calculations.get(id)) { "No calculation $id" }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): CardState {
        val breakdown = calculation.breakdown(zone)
        return CardState(
            name = calculation.name,
            status = calculation.status,
            statusOptions = if (breakdown == null) emptyList() else CalculationStatus.MANUAL.sortedBy { it.ordinal },
            quantityText = quantityText,
            quantityError = quantityError,
            unitPrice = breakdown?.let { money(it.unitPrice) },
            total = breakdown?.let { money(it.total) },
            unitMass = breakdown?.let { kg(it.unitMassKg) },
            runMass = breakdown?.let { kg(it.runMassKg) },
            snapshotDate = breakdown?.let { formatDate(it.snapshotDate) },
            parts = breakdown?.parts.orEmpty().map { part ->
                CardPart(
                    title = part.name,
                    details = "Масса: ${kg(part.massKg)}",
                    lines = part.lines.map(::line),
                    cost = money(part.cost),
                )
            },
            productLines = breakdown?.productLines.orEmpty().map(::line),
            recalc = pendingRecalc?.let { (_, result) -> preview(result, breakdown) },
            recalcErrors = recalcErrors,
        )
    }

    private fun preview(result: CalcResult, breakdown: Breakdown?): RecalcPreview? {
        breakdown ?: return null
        return RecalcPreview(
            snapshotDate = formatDate(breakdown.snapshotDate),
            oldUnitPrice = money(breakdown.unitPrice),
            newUnitPrice = money(result.unitPrice),
            oldTotal = money(breakdown.total),
            newTotal = money(result.total(breakdown.quantity)),
            unchanged = result.unitPrice.compareTo(breakdown.unitPrice) == 0,
        )
    }

    private fun line(line: BreakdownLine) = CardLine(line.title, line.extra?.let(::extra), money(line.cost))

    private fun extra(extra: ManualExtra): String {
        val amount = "в т. ч. доп. операции ${money(extra.amount)}"
        return if (extra.comment.isBlank()) amount else "$amount — ${extra.comment.trim()}"
    }

    private fun money(amount: Money) = formatMoney(amount, currency)

    private fun kg(value: BigDecimal) = "${formatMeasure(value)} кг"
}
