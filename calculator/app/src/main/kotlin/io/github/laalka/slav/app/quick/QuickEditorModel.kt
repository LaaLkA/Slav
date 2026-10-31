package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.FileOpener
import io.github.laalka.slav.app.attachments.AttachmentsModel
import io.github.laalka.slav.app.calculations.PriceSummary
import io.github.laalka.slav.app.calculations.priceSummary
import io.github.laalka.slav.app.developer.RandomCalculation
import io.github.laalka.slav.app.editor.CalculationEditor
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.editor.ReadableError
import io.github.laalka.slav.app.editor.readable
import io.github.laalka.slav.app.editor.withNameTaken
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import io.github.laalka.slav.core.QuickCalculator
import io.github.laalka.slav.core.QuickOutcome
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.DuplicateNameException
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.StoredCalculation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration

/**
 * The editor of a simplified calculation («Упрощённый расчёт»): a sheet of one grade and thickness,
 * its area and the laser time. It has no run size: the calculation is one piece. It lives as
 * [EditorModel] does: it edits a draft (a new one, one left unfinished, a copy, or a revision draft
 * left by an earlier version), saves every change as a draft [autosaveDelay] after the last one, and at
 * once when the editor is left. «Рассчитать» prices the input on the current settings for a new
 * calculation or a copy, and on the snapshot of a revision draft's source; [onCalculated] receives the
 * id of the saved calculation.
 *
 * With [inPlace] it is open on a calculated record, after the administrator password was asked, and
 * works as [EditorModel] does then: no autosave, «Рассчитать» writes the record over itself on the
 * current prices, leaving without it loses the changes. A name another calculation has is flagged
 * under the field and keeps «Рассчитать» disabled.
 */
class QuickEditorModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    attachmentRepository: AttachmentRepository,
    fileOpener: FileOpener,
    private val scope: CoroutineScope,
    id: UUID?,
    private val onCalculated: (UUID) -> Unit,
    private val autosaveDelay: Duration = EditorModel.AUTOSAVE_DELAY,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val inPlace: Boolean = false,
) : CalculationEditor {
    /** The draft as it was opened, or the calculation the last «Рассчитать» saved it as; null for a new one. */
    private var original: StoredCalculation.Quick? = id?.let {
        val stored = requireNotNull(calculations.get(it)) { "No calculation $it" }
        requireNotNull(stored as? StoredCalculation.Quick) { "Calculation $it is a ${stored.kind} one, not a simplified one" }
    }
    private var id: UUID? = original?.id
    private val isNew = original == null

    init {
        require(!inPlace || original != null) { "Only a saved calculation is edited in place" }
        original?.let {
            if (inPlace) {
                require(it.status != CalculationStatus.DRAFT) { "A draft is edited as it is, ${it.id} has no calculation to edit in place" }
            } else {
                require(it.status == CalculationStatus.DRAFT) { "Only a draft is edited, ${it.id} is «${it.status.title}»" }
            }
        }
    }

    /** Set for a revision draft: it is calculated on the prices of its source (spec 8.3). An edit in place uses the current ones. */
    private val snapshot: PriceBook? = original?.snapshot.takeIf { !inPlace }
    private var book: PriceBook = snapshot ?: settings.load()

    private var form: QuickForm = original?.input?.toForm() ?: QuickForm()

    /** The form an edit in place started from, or was last calculated as: what the changes are counted against. */
    private var baseForm: QuickForm = form

    /** The name a save refused as taken although the check had not seen it taken, until the next change. */
    private var refusedName: String? = null

    /** What the database holds for this calculation; null while nothing is stored. */
    private var stored: QuickCalculationInput? = original?.input

    /** The form was changed since the last draft save; opening and leaving without a change writes nothing. */
    private var unsaved = false
    private var pendingSave: Job? = null
    private var calcErrors: List<ReadableError> = emptyList()

    /** Counts the times the form was replaced as a whole: the fields keep what was typed, so they are created again. */
    private var epoch = 0

    private val _state = MutableStateFlow(build())
    val state: StateFlow<QuickEditorState> = _state.asStateFlow()

    /** The drawings of the calculation. */
    val attachments = AttachmentsModel(
        attachmentRepository,
        fileOpener,
        // this.id is the property, which follows the saves; a bare id here would be the constructor parameter.
        calculationId = { this.id },
        storedCalculationId = ::storedId,
        scope = scope,
    )

    /**
     * Developer mode: replaces the whole form with a random simplified calculation that is valid on the
     * prices of the editor ([RandomCalculation]), so it can be calculated at once.
     */
    fun fillRandomly(random: Random = Random.Default) {
        epoch++
        edit { RandomCalculation(book, random).quickForm() }
    }

    fun onNameChange(text: String) = edit { it.copy(name = text) }

    fun onCounterpartyChange(text: String) = edit { it.copy(counterparty = text) }

    /** «Скидка, %»; blank means none. */
    fun onDiscountChange(text: String) = edit { it.copy(discount = text) }

    fun onGradeChange(grade: String) = edit { it.copy(grade = grade) }

    fun onThicknessChange(thicknessMm: BigDecimal) = edit { it.copy(thicknessMm = thicknessMm) }

    fun onSheetAreaChange(text: String) = edit { it.copy(sheetArea = text) }

    fun onCutTimeChange(text: String) = edit { it.copy(cutTime = text) }

    fun onExtraAmountChange(line: QuickLine, text: String) = edit { it.withExtra(line, it.extra(line).copy(amount = text)) }

    fun onExtraCommentChange(line: QuickLine, text: String) = edit { it.withExtra(line, it.extra(line).copy(comment = text)) }

    /**
     * Spec 7.1, 7.3, 8.3. Ok: the calculation is saved as «Рассчитан» with the prices it used, and
     * [onCalculated] is called; an edit in place writes it over the record it was opened on. Failed:
     * every error is listed and the input stays as it is. A name another calculation took in the
     * meantime is flagged under the name field, and nothing is saved.
     */
    fun calculate() {
        val read = read()
        if (read.errors.isNotEmpty()) return
        // The settings are read at the press: a price filled in a moment ago counts.
        val prices = snapshot ?: settings.load()
        when (val outcome = QuickCalculator(prices).calculate(read.input)) {
            is QuickOutcome.Failed -> {
                flush()
                calcErrors = outcome.errors.map { it.readable(onSnapshot = snapshot != null, currency = prices.general.currencySymbol) }
                refresh()
            }
            is QuickOutcome.Ok -> {
                pendingSave?.cancel()
                pendingSave = null
                val saved = try {
                    if (inPlace) {
                        checkNotNull(id).also { calculations.overwriteCalculated(it, read.input, prices, outcome.result) }
                    } else {
                        // A revision draft keeps the date of the snapshot it was made with: the prices are the same.
                        calculations.saveCalculated(read.input, prices, outcome.result, id, snapshotAt = original?.snapshotAt.takeIf { snapshot != null })
                    }
                } catch (e: DuplicateNameException) {
                    refusedName = e.name
                    refresh()
                    return
                }
                id = saved
                original = calculations.get(saved) as StoredCalculation.Quick
                stored = read.input
                baseForm = form
                unsaved = false
                calcErrors = emptyList()
                refresh()
                onCalculated(saved)
            }
        }
    }

    /** Writes the pending draft now. An edit in place never has one: it is not autosaved. */
    override fun flush() {
        pendingSave?.cancel()
        pendingSave = null
        saveDraft()
    }

    /**
     * Saves what is pending before the editor is left. A revision draft nothing was changed in
     * is an automatic copy of its source and is deleted silently, without a password. An edit in
     * place has nothing to save and nothing to delete (only a draft is dropped): the record stays as it was.
     */
    override fun leave() {
        flush()
        if (original?.sourceId != null) id?.let { calculations.deleteUntouchedRevisionDraft(it) }
    }

    override val inPlaceId: UUID? get() = id.takeIf { inPlace }

    override val discardsOnLeave: Boolean get() = inPlace && form != baseForm

    /** Back from the settings: a calculation on current prices sees what was changed there. */
    override fun resume() {
        if (snapshot != null) return
        book = settings.load()
        calcErrors = emptyList()
        edit { it }
        refresh()
    }

    private fun edit(transform: (QuickForm) -> QuickForm) {
        val next = transform(form)
        if (next == form) return
        form = next
        refusedName = null
        calcErrors = emptyList()
        refresh()
        // Nothing is written while a record is edited in place: it changes only by «Рассчитать».
        if (inPlace) return
        unsaved = true
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(autosaveDelay)
            pendingSave = null
            saveDraft()
        }
    }

    /**
     * The id drawings are attached under: a calculation never saved yet is saved as a draft first,
     * which throws [DuplicateNameException] while its name is taken.
     */
    private fun storedId(): UUID {
        flush()
        id?.let { return it }
        val draft = form.read().input
        return calculations.saveDraft(draft).also {
            id = it
            stored = draft
        }
    }

    /** A draft under a name another calculation has is not saved: the name field says so, and the next change tries again. */
    private fun saveDraft() {
        if (!unsaved) return
        val draft = form.read().input
        if (draft == stored) {
            unsaved = false
            return
        }
        try {
            id = calculations.saveDraft(draft, id)
            stored = draft
            unsaved = false
        } catch (e: DuplicateNameException) {
            refusedName = e.name
            refresh()
        }
    }

    /** The form as read now, with a name taken by another calculation among the fields that keep «Рассчитать» disabled. */
    private fun read(): QuickRead {
        val read = form.read()
        val errors = read.errors.withNameTaken(QuickTags.NAME, read.input.name) { it == refusedName || calculations.isNameTaken(it, id) }
        return if (errors === read.errors) read else QuickRead(read.input, errors)
    }

    /** The price of the input as it stands; null while a field is missing or a price is not filled in. */
    private fun summary(read: QuickRead): PriceSummary? {
        if (read.errors.isNotEmpty()) return null
        val prices = snapshot ?: book
        val result = (QuickCalculator(prices).calculate(read.input) as? QuickOutcome.Ok)?.result ?: return null
        // A simplified calculation is one piece: its price is its total, so there is no run total to show.
        return priceSummary(result.subtotal, result.discountPercent, result.discount, result.unitPrice, quantity = null, prices.general.currencySymbol)
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): QuickEditorState {
        val snapshotAt = original?.snapshotAt.takeIf { snapshot != null }
        val priceSource = if (snapshotAt != null) {
            "Цены — снимок от ${DATE.format(snapshotAt.atZone(zone))}"
        } else {
            "Цены — текущие настройки"
        }
        val hint = when {
            snapshotAt != null ->
                "Расчёт ведётся по снимку цен от ${DATE.format(snapshotAt.atZone(zone))}: цены из настроек в него не попадают. " +
                    "Чтобы считать по текущим ценам, сделайте копию расчёта. Изменения сохранены как черновик."
            inPlace -> "Заполните эти цены в «Настройках» и нажмите «Рассчитать» ещё раз. Расчёт не изменён."
            else -> "Заполните эти цены в «Настройках» и нажмите «Рассчитать» ещё раз. Расчёт сохранён как черновик."
        }
        val read = read()
        return quickEditorState(form, read, book, isNew, inPlace, priceSource, summary(read), calcErrors, hint, epoch)
    }

    private companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
