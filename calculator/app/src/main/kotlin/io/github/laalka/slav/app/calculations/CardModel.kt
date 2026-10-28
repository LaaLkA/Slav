package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.FileOpener
import io.github.laalka.slav.app.attachments.AttachmentsModel
import io.github.laalka.slav.app.editor.ReadableError
import io.github.laalka.slav.app.editor.readable
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.app.ui.formatDateTime
import io.github.laalka.slav.app.ui.formatMeasure
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.core.CalcError
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculator
import io.github.laalka.slav.core.QuickOutcome
import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.CalculationSummary
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

/** A link from the card to another calculation: the one it was made from, or a newer version of it. */
data class CardLink(val id: UUID, val title: String, val details: String?, val isDraft: Boolean)

/** «Изменить тираж» before the user's answer: the run size typed so far and what is wrong with it. */
data class QuantityDialog(val text: String, val error: String?)

/**
 * The card of spec 8.2. Money texts carry the currency label of the settings. A draft has no
 * price: its price, masses and breakdown are null or empty and it has no [statusOptions].
 */
data class CardState(
    val name: String,
    val status: CalculationStatus,
    /** The statuses the user sets by hand; empty for a draft. */
    val statusOptions: List<CalculationStatus>,
    val quantity: String,
    val quantityDialog: QuantityDialog?,
    val unitPrice: String?,
    val total: String?,
    val unitMass: String?,
    val runMass: String?,
    val snapshotDate: String?,
    val parts: List<CardPart>,
    val productLines: List<CardLine>,
    val recalc: RecalcPreview?,
    val recalcErrors: List<ReadableError>,
    /** «Создан на основе»: the calculation this one is a revision of; null for an original or when it was deleted. */
    val source: CardLink?,
    /** «Новые версии»: the calculations made from this one, newest first. */
    val revisions: List<CardLink>,
    val deleteQuestion: DeleteQuestion?,
) {
    val isPriced: Boolean get() = unitPrice != null
}

/**
 * The card of one calculation of either kind (spec 8.2, 8.3). A calculated record is never changed in place:
 * «Изменить», «Пересчитать по текущим ценам» and «Изменить тираж» each make a new calculation and
 * leave this one as it was; [onRevision] receives the id of the new one. «Пересчитать по текущим
 * ценам» prices the input on the current settings and creates the new calculation only once
 * confirmed; a new run size keeps the snapshot and the result, so the total follows from the stored
 * unit price. Deleting needs the administrator password; [onDeleted] is called when it is done.
 */
class CardModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    adminPassword: AdminPassword,
    attachmentRepository: AttachmentRepository,
    fileOpener: FileOpener,
    val id: UUID,
    private val onRevision: (UUID) -> Unit,
    private val onDeleted: () -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var calculation: StoredCalculation = read()
    private var currency: String = settings.load().general.currencySymbol
    private var quantityDialog: QuantityDialog? = null
    private var source: StoredCalculation? = null
    private var revisions: List<CalculationSummary> = emptyList()
    private val deletion = DeleteFlow(adminPassword, calculations::delete)

    /**
     * The drawings of the card (spec 8.2). Adding one does not change the price, so it is always
     * allowed; removing one from a calculated record needs the administrator password, from a draft it is free.
     */
    val attachments = AttachmentsModel(
        attachmentRepository,
        fileOpener,
        calculationId = { id },
        storedCalculationId = { id },
        passwordGuard = adminPassword.takeIf { calculation.status != CalculationStatus.DRAFT },
    )

    /** The pending «Пересчитать по текущим ценам»: its new unit price for the preview, and how to save it as previewed. */
    private var pendingRecalc: PendingRecalc? = null
    private var recalcErrors: List<ReadableError> = emptyList()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<CardState> = _state.asStateFlow()

    init {
        readLinks()
        refresh()
    }

    /** Reads the calculation and the currency label again, after either may have changed elsewhere. */
    fun reload() {
        calculation = read()
        currency = settings.load().general.currencySymbol
        quantityDialog = null
        pendingRecalc = null
        recalcErrors = emptyList()
        deletion.cancel()
        readLinks()
        attachments.reload()
        refresh()
    }

    /**
     * «Изменить» (spec 8.3): the id of the draft to open in the editor. A calculated record is
     * not edited itself, a revision draft with its input and prices is; a draft is edited as it is.
     */
    fun edit(): UUID = if (calculation.status == CalculationStatus.DRAFT) id else calculations.createRevisionDraft(id)

    /** «Изменить тираж»: asks for the new run size. */
    fun requestQuantityChange() {
        if (!calculation.isPriced) return
        quantityDialog = QuantityDialog(calculation.quantity.toString(), error = null)
        refresh()
    }

    fun onQuantityChange(text: String) {
        if (quantityDialog == null) return
        quantityDialog = QuantityDialog(text, quantityError(text))
        refresh()
    }

    fun cancelQuantityChange() {
        quantityDialog = null
        refresh()
    }

    /**
     * Creates the calculation with the new run size, the same snapshot and the same result, with
     * no recalculation (spec 8.2), and hands it to [onRevision]. A run size that is not a new
     * positive whole number is flagged and nothing is created.
     */
    fun confirmQuantityChange() {
        val dialog = quantityDialog ?: return
        val error = quantityError(dialog.text)
        val quantity = dialog.text.trim().toIntOrNull()
        if (error != null || quantity == null) {
            quantityDialog = dialog.copy(error = error)
            refresh()
            return
        }
        val snapshot = checkNotNull(calculation.snapshot) { "Calculation $id has no price snapshot" }
        val revision = when (val current = calculation) {
            is StoredCalculation.Full -> calculations.createRevision(
                id, current.input.copy(quantity = quantity), snapshot, checkNotNull(current.result) { "Calculation $id has no result" }, current.snapshotAt,
            )
            is StoredCalculation.Quick -> calculations.createRevision(
                id, current.input.copy(quantity = quantity), snapshot, checkNotNull(current.result) { "Calculation $id has no result" }, current.snapshotAt,
            )
        }
        quantityDialog = null
        refresh()
        onRevision(revision)
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
        if (!calculation.isPriced) return
        val current = settings.load()
        currency = current.general.currencySymbol
        pendingRecalc = null
        recalcErrors = emptyList()
        when (val priced = calculation) {
            is StoredCalculation.Full -> when (val outcome = Calculator(current).calculate(priced.input)) {
                is CalcOutcome.Ok ->
                    pendingRecalc = PendingRecalc(outcome.result.unitPrice) { calculations.createRevision(id, priced.input, current, outcome.result) }
                is CalcOutcome.Failed -> failRecalc(outcome.errors)
            }
            is StoredCalculation.Quick -> when (val outcome = QuickCalculator(current).calculate(priced.input)) {
                is QuickOutcome.Ok ->
                    pendingRecalc = PendingRecalc(outcome.result.unitPrice) { calculations.createRevision(id, priced.input, current, outcome.result) }
                is QuickOutcome.Failed -> failRecalc(outcome.errors)
            }
        }
        refresh()
    }

    private fun failRecalc(errors: List<CalcError>) {
        recalcErrors = errors.map { it.readable(onSnapshot = false, currency = currency) }
    }

    /**
     * Creates the calculation with the previewed result and the current prices as its snapshot and
     * hands it to [onRevision]; this one stays as it was.
     */
    fun confirmRecalc() {
        val recalc = checkNotNull(pendingRecalc) { "No recalculation to confirm" }
        pendingRecalc = null
        val revision = recalc.save()
        refresh()
        onRevision(revision)
    }

    fun cancelRecalc() {
        pendingRecalc = null
        refresh()
    }

    fun dismissRecalcErrors() {
        recalcErrors = emptyList()
        refresh()
    }

    fun requestDelete() {
        deletion.request(id)
        refresh()
    }

    fun onDeletePasswordChange(text: String) {
        deletion.onPasswordChange(text)
        refresh()
    }

    fun cancelDelete() {
        deletion.cancel()
        refresh()
    }

    /** Deletes this calculation when the password is the administrator's, then calls [onDeleted]; a wrong one is flagged. */
    fun confirmDelete() {
        val deleted = deletion.confirm()
        refresh()
        if (deleted) onDeleted()
    }

    private fun read(): StoredCalculation = requireNotNull(calculations.get(id)) { "No calculation $id" }

    private fun readLinks() {
        source = calculation.sourceId?.let(calculations::get)
        revisions = calculations.revisionsOf(id)
    }

    private fun quantityError(text: String): String? {
        val quantity = text.trim().toIntOrNull()?.takeIf { it > 0 }
        return when {
            text.isBlank() -> "Заполните"
            quantity == null -> "Целое число больше нуля"
            quantity == calculation.quantity -> "Тираж не изменился"
            else -> null
        }
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): CardState {
        val breakdown = calculation.breakdown(zone)
        return CardState(
            name = calculation.name,
            status = calculation.status,
            statusOptions = if (breakdown == null) emptyList() else CalculationStatus.MANUAL.sortedBy { it.ordinal },
            quantity = calculation.quantity.toString(),
            quantityDialog = quantityDialog,
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
            recalc = pendingRecalc?.let { preview(it, breakdown) },
            recalcErrors = recalcErrors,
            source = source?.let { CardLink(it.id, "${it.name.ifBlank { UNNAMED }} от ${formatDate(it.createdAt.atZone(zone).toLocalDate())}", null, it.status == CalculationStatus.DRAFT) },
            revisions = revisions.map { it.toLink() },
            deleteQuestion = deletion.pending?.let { DeleteQuestion(calculation.name.ifBlank { UNNAMED }, deletion.error) },
        )
    }

    /** The date, the status and the price tell one version from another. */
    private fun CalculationSummary.toLink() = CardLink(
        id = id,
        title = "${name.ifBlank { UNNAMED }} от ${formatDateTime(createdAt, zone)}",
        details = "${status.title}, ${unitPrice?.let(::money) ?: "без цены"} × $quantity",
        isDraft = status == CalculationStatus.DRAFT,
    )

    private fun preview(recalc: PendingRecalc, breakdown: Breakdown?): RecalcPreview? {
        breakdown ?: return null
        return RecalcPreview(
            snapshotDate = formatDate(breakdown.snapshotDate),
            oldUnitPrice = money(breakdown.unitPrice),
            newUnitPrice = money(recalc.unitPrice),
            oldTotal = money(breakdown.total),
            newTotal = money(recalc.unitPrice * breakdown.quantity),
            unchanged = recalc.unitPrice.compareTo(breakdown.unitPrice) == 0,
        )
    }

    /** A recalculation priced on the current settings: [unitPrice] is shown, [save] makes the new calculation and returns its id. */
    private class PendingRecalc(val unitPrice: Money, val save: () -> UUID)

    private fun line(line: BreakdownLine) = CardLine(line.title, line.extra?.let(::extra), money(line.cost))

    private fun extra(extra: ManualExtra): String {
        val amount = "в т. ч. доп. операции ${money(extra.amount)}"
        return if (extra.comment.isBlank()) amount else "$amount — ${extra.comment.trim()}"
    }

    private fun money(amount: Money) = formatMoney(amount, currency)

    private fun kg(value: BigDecimal) = "${formatMeasure(value)} кг"
}

private const val UNNAMED = "Без названия"
