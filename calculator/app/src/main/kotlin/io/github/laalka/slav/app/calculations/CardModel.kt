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
import io.github.laalka.slav.data.CalculationArchive
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.CalculationSummary
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.StoredCalculation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

/** A breakdown line as the card draws it; [extra] names the manual amount and its comment. */
data class CardLine(val title: String, val extra: String?, val cost: String)

/** One part, one piece of the product; [details] says its mass. */
data class CardPart(val title: String, val details: String, val lines: List<CardLine>, val cost: String)

/** «Пересчитать по текущим ценам» before the user's answer (spec 8.3); the totals are null for a calculation without a run size. */
data class RecalcPreview(
    val snapshotDate: String,
    val oldUnitPrice: String,
    val newUnitPrice: String,
    val oldTotal: String?,
    val newTotal: String?,
    val unchanged: Boolean,
)

/** A link from the card to another calculation: the one it was made from, or a newer version of it. */
data class CardLink(val id: UUID, val title: String, val details: String?, val isDraft: Boolean)

/** «Изменить тираж» before the user's answer: the run size typed so far and what is wrong with it. */
data class QuantityDialog(val text: String, val error: String?)

/**
 * The card of spec 8.2. Money texts carry the currency label of the settings. A draft has no
 * price: its price, masses and breakdown are null or empty and it has no [statusOptions]. A
 * simplified calculation has no run size: [quantity], [total] and [runMass] are null for it, and
 * it is one piece whose price is [unitPrice].
 */
data class CardState(
    val name: String,
    /** The customer the calculation is for; empty when not given. */
    val counterparty: String,
    val kind: CalculationKind,
    val status: CalculationStatus,
    /** The statuses the user sets by hand; empty for a draft. */
    val statusOptions: List<CalculationStatus>,
    val quantity: String?,
    val quantityDialog: QuantityDialog?,
    /** The price of one piece, the discount already taken off. */
    val unitPrice: String?,
    /** The sum before the discount and the discount with its percent; null without a discount. */
    val discount: DiscountLines?,
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
    /** The administrator password asked before a calculated record is edited in place. */
    val editQuestion: EditQuestion?,
    /** The administrator password asked before this calculation is sent to an archive file and deleted. */
    val archiveQuestion: ArchiveQuestion?,
    /** Why «В архив» did not go through; the card is still here, and nothing was deleted. */
    val message: ListMessage?,
) {
    val isPriced: Boolean get() = unitPrice != null

    val isDraft: Boolean get() = status == CalculationStatus.DRAFT
}

/**
 * The card of one calculation of either kind (spec 8.2, 8.3). A calculated record is changed in
 * place only by «Изменить», after the administrator password: [onOpenEditor] then opens the editor
 * on this very record. «Сделать копию и изменить», «Пересчитать по текущим ценам» and «Изменить
 * тираж» (a full calculation only; a simplified one has no run size) leave this one as it was:
 * the copy is a draft named «… (N)», the others make a new calculation, and [onRevision] receives
 * the id of it. «Пересчитать по текущим ценам» prices the input on the current settings and creates
 * the new calculation only once confirmed, under the name «… №N»; a new run size keeps the snapshot
 * and the result, so the total follows from the stored unit price. A draft is edited as it is,
 * with no password. Deleting needs the administrator password; [onDeleted] is called when it is done.
 * «В архив» writes the calculation to a zip file the user has chosen and then deletes it ([ArchiveFlow]),
 * after the administrator password; [onArchived] is called once it is done, the calculation being gone
 * with the card, and a failure is shown on the card ([CardState.message]) with nothing deleted.
 *
 * [onOpenEditor] gets the id to edit and whether it is edited in place.
 */
class CardModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    adminPassword: AdminPassword,
    attachmentRepository: AttachmentRepository,
    fileOpener: FileOpener,
    scope: CoroutineScope,
    val id: UUID,
    private val onRevision: (UUID) -> Unit,
    private val onDeleted: () -> Unit,
    private val onOpenEditor: (id: UUID, inPlace: Boolean) -> Unit,
    archive: CalculationArchive,
    private val onArchived: (ArchiveOutcome.Sent) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var calculation: StoredCalculation = read()
    private var currency: String = settings.load().general.currencySymbol
    private var quantityDialog: QuantityDialog? = null
    private var source: StoredCalculation? = null
    private var revisions: List<CalculationSummary> = emptyList()
    private val deletion = PasswordFlow(adminPassword, calculations::delete)
    private val editing = PasswordFlow<UUID>(adminPassword) { onOpenEditor(it, true) }
    private val archiving = ArchiveFlow(archive, calculations, adminPassword, onFinished = ::archived)
    private var message: ListMessage? = null

    /**
     * The drawings of the card (spec 8.2). Adding or removing one changes only the drawings: it asks
     * for no password, even on a calculated record, and makes no new version, so the id, status and
     * price stay as they were. Only deleting the whole calculation needs the administrator password.
     */
    val attachments = AttachmentsModel(
        attachmentRepository,
        fileOpener,
        calculationId = { id },
        storedCalculationId = { id },
        scope = scope,
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
        editing.cancel()
        archiving.cancel()
        message = null
        readLinks()
        attachments.reload()
        refresh()
    }

    /**
     * «Изменить»: a draft is opened in the editor as it is; a calculated record asks the
     * administrator password first, and the editor then opens on this same record.
     */
    fun requestEdit() {
        if (calculation.status == CalculationStatus.DRAFT) {
            onOpenEditor(id, false)
        } else {
            editing.request(id)
            refresh()
        }
    }

    fun onEditPasswordChange(text: String) {
        editing.onPasswordChange(text)
        refresh()
    }

    fun cancelEdit() {
        editing.cancel()
        refresh()
    }

    /** Opens the editor on this record when the password is the administrator's; a wrong one is flagged and nothing opens. */
    fun confirmEdit() {
        editing.confirm()
        refresh()
    }

    /**
     * «Сделать копию и изменить»: a draft with the input of this calculation, named «<base> (N)» and
     * priced on the current settings, opened in the editor. This one stays as it was. A draft has
     * no copy to make here: it is edited as it is.
     */
    fun copyAndEdit() {
        if (!calculation.isPriced) return
        onOpenEditor(calculations.copy(id), false)
    }

    /** «Изменить тираж»: asks for the new run size. A simplified calculation has none. */
    fun requestQuantityChange() {
        if (!calculation.isPriced || calculation !is StoredCalculation.Full) return
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
        val current = calculation as? StoredCalculation.Full
        if (error != null || quantity == null || current == null) {
            quantityDialog = dialog.copy(error = error)
            refresh()
            return
        }
        val snapshot = checkNotNull(calculation.snapshot) { "Calculation $id has no price snapshot" }
        val revision = calculations.createRevision(
            id, current.input.copy(quantity = quantity), snapshot, checkNotNull(current.result) { "Calculation $id has no result" }, current.snapshotAt,
        )
        quantityDialog = null
        refresh()
        onRevision(revision)
    }

    /** Отправлен, В работе or Завершён, set by hand (spec 8.1). */
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

    /** What the save dialog of «В архив» offers: the file name of today. */
    fun suggestedArchiveName(): String = suggestedArchiveName(zone)

    /** «В архив» after the user has chosen the file [target]: asks the administrator password, then writes the file and deletes this calculation. */
    fun requestArchive(target: Path) {
        archiving.request(listOf(id), target)
        refresh()
    }

    fun onArchivePasswordChange(text: String) {
        archiving.onPasswordChange(text)
        refresh()
    }

    /** A wrong password is flagged and nothing is written; a failure after it is shown on the card. */
    fun confirmArchive() {
        archiving.confirm()
        refresh()
    }

    fun cancelArchive() {
        archiving.cancel()
        refresh()
    }

    fun dismissMessage() {
        message = null
        refresh()
    }

    private fun archived(outcome: ArchiveOutcome) {
        when (outcome) {
            is ArchiveOutcome.Sent -> onArchived(outcome)
            is ArchiveOutcome.Failed -> message = outcome.toMessage()
        }
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
        val hasRunSize = calculation.kind == CalculationKind.FULL
        return CardState(
            name = calculation.name,
            counterparty = calculation.counterparty,
            kind = calculation.kind,
            status = calculation.status,
            statusOptions = if (breakdown == null) emptyList() else CalculationStatus.MANUAL.sortedBy { it.ordinal },
            quantity = calculation.quantity.toString().takeIf { hasRunSize },
            quantityDialog = quantityDialog,
            unitPrice = breakdown?.let { money(it.unitPrice) },
            discount = breakdown?.let { discountLines(it.subtotal, it.discountPercent, it.discount, currency) },
            total = breakdown?.takeIf { hasRunSize }?.let { money(it.total) },
            unitMass = breakdown?.let { kg(it.unitMassKg) },
            runMass = breakdown?.takeIf { hasRunSize }?.let { kg(it.runMassKg) },
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
            editQuestion = editing.pending?.let { EditQuestion(calculation.name.ifBlank { UNNAMED }, editing.error) },
            archiveQuestion = archiving.question,
            message = message,
        )
    }

    /** The date, the status and the price tell one version from another. */
    private fun CalculationSummary.toLink() = CardLink(
        id = id,
        title = "${name.ifBlank { UNNAMED }} от ${formatDateTime(createdAt, zone)}",
        // A simplified calculation is one piece: there is no run size to multiply by.
        details = "${status.title}, ${unitPrice?.let(::money) ?: "без цены"}" + if (kind == CalculationKind.FULL) " × $quantity" else "",
        isDraft = status == CalculationStatus.DRAFT,
    )

    private fun preview(recalc: PendingRecalc, breakdown: Breakdown?): RecalcPreview? {
        breakdown ?: return null
        return RecalcPreview(
            snapshotDate = formatDate(breakdown.snapshotDate),
            oldUnitPrice = money(breakdown.unitPrice),
            newUnitPrice = money(recalc.unitPrice),
            oldTotal = breakdown.takeIf { it.hasRunSize }?.let { money(it.total) },
            newTotal = breakdown.takeIf { it.hasRunSize }?.let { money(recalc.unitPrice * it.quantity) },
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
