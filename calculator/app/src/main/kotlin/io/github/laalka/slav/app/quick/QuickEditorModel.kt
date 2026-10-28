package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.FileOpener
import io.github.laalka.slav.app.attachments.AttachmentsModel
import io.github.laalka.slav.app.editor.CalculationEditor
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.editor.ReadableError
import io.github.laalka.slav.app.editor.readable
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import io.github.laalka.slav.core.QuickCalculator
import io.github.laalka.slav.core.QuickOutcome
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
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
import kotlin.time.Duration

/**
 * The editor of a simplified calculation («Упрощённый расчёт»): a sheet of one grade and thickness,
 * its area and the laser time. It lives as [EditorModel] does: it edits a draft (a new one, one left
 * unfinished, a copy, or the revision draft that «Изменить» makes of a calculated record), saves every
 * change as a draft [autosaveDelay] after the last one, and at once when the editor is left.
 * «Рассчитать» prices the input on the current settings for a new calculation or a copy, and on the
 * snapshot of the revision draft's source; [onCalculated] receives the id of the saved calculation.
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
) : CalculationEditor {
    /** The draft as it was opened, or the calculation the last «Рассчитать» saved it as; null for a new one. */
    private var original: StoredCalculation.Quick? = id?.let {
        val stored = requireNotNull(calculations.get(it)) { "No calculation $it" }
        requireNotNull(stored as? StoredCalculation.Quick) { "Calculation $it is a ${stored.kind} one, not a simplified one" }
    }
    private var id: UUID? = original?.id
    private val isNew = original == null

    init {
        original?.let { require(it.status == CalculationStatus.DRAFT) { "Only a draft is edited, ${it.id} is «${it.status.title}»" } }
    }

    /** Set for a revision draft: it is calculated on the prices of its source (spec 8.3). */
    private val snapshot: PriceBook? = original?.snapshot
    private var book: PriceBook = snapshot ?: settings.load()

    private var form: QuickForm = original?.input?.toForm() ?: QuickForm()

    /** What the database holds for this calculation; null while nothing is stored. */
    private var stored: QuickCalculationInput? = original?.input

    /** The form was changed since the last draft save; opening and leaving without a change writes nothing. */
    private var unsaved = false
    private var pendingSave: Job? = null
    private var calcErrors: List<ReadableError> = emptyList()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<QuickEditorState> = _state.asStateFlow()

    /** The drawings of the calculation; the editor only edits drafts, so removing one needs no password. */
    val attachments = AttachmentsModel(
        attachmentRepository,
        fileOpener,
        // this.id is the property, which follows the saves; a bare id here would be the constructor parameter.
        calculationId = { this.id },
        storedCalculationId = ::storedId,
        passwordGuard = null,
    )

    fun onNameChange(text: String) = edit { it.copy(name = text) }

    fun onQuantityChange(text: String) = edit { it.copy(quantity = text) }

    fun onGradeChange(grade: String) = edit { it.copy(grade = grade) }

    fun onThicknessChange(thicknessMm: BigDecimal) = edit { it.copy(thicknessMm = thicknessMm) }

    fun onSheetAreaChange(text: String) = edit { it.copy(sheetArea = text) }

    fun onCutTimeChange(text: String) = edit { it.copy(cutTime = text) }

    fun onExtraAmountChange(line: QuickLine, text: String) = edit { it.withExtra(line, it.extra(line).copy(amount = text)) }

    fun onExtraCommentChange(line: QuickLine, text: String) = edit { it.withExtra(line, it.extra(line).copy(comment = text)) }

    /**
     * Spec 7.1, 7.3, 8.3. Ok: the calculation is saved as «Рассчитан» with the prices it used, and
     * [onCalculated] is called. Failed: every error is listed and the input stays a draft.
     */
    fun calculate() {
        val read = form.read()
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
                // A revision draft keeps the date of the snapshot it was made with: the prices are the same.
                val saved = calculations.saveCalculated(read.input, prices, outcome.result, id, snapshotAt = original?.snapshotAt.takeIf { snapshot != null })
                id = saved
                original = calculations.get(saved) as StoredCalculation.Quick
                stored = read.input
                unsaved = false
                calcErrors = emptyList()
                refresh()
                onCalculated(saved)
            }
        }
    }

    /** Writes the pending draft now. */
    override fun flush() {
        pendingSave?.cancel()
        pendingSave = null
        saveDraft()
    }

    /**
     * Saves what is pending before the editor is left. A revision draft nothing was changed in
     * is an automatic copy of its source and is deleted silently, without a password.
     */
    override fun leave() {
        flush()
        if (original?.sourceId != null) id?.let { calculations.deleteUntouchedRevisionDraft(it) }
    }

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
        unsaved = true
        calcErrors = emptyList()
        refresh()
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(autosaveDelay)
            pendingSave = null
            saveDraft()
        }
    }

    /** The id drawings are attached under: a calculation never saved yet is saved as a draft first. */
    private fun storedId(): UUID {
        flush()
        id?.let { return it }
        val draft = form.read().input
        return calculations.saveDraft(draft).also {
            id = it
            stored = draft
        }
    }

    private fun saveDraft() {
        if (!unsaved) return
        unsaved = false
        val draft = form.read().input
        if (draft == stored) return
        id = calculations.saveDraft(draft, id)
        stored = draft
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
        val hint = if (snapshotAt != null) {
            "Расчёт ведётся по снимку цен от ${DATE.format(snapshotAt.atZone(zone))}: цены из настроек в него не попадают. " +
                "Чтобы считать по текущим ценам, сделайте копию расчёта. Изменения сохранены как черновик."
        } else {
            "Заполните эти цены в «Настройках» и нажмите «Рассчитать» ещё раз. Расчёт сохранён как черновик."
        }
        return quickEditorState(form, form.read(), book, isNew, priceSource, calcErrors, hint)
    }

    private companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
