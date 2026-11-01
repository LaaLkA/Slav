package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.export.exportFileName
import io.github.laalka.slav.app.ui.formatDateTime
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.app.ui.parseDate
import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.data.CalculationArchive
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationSort
import io.github.laalka.slav.data.CalculationSortColumn
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.CalculationSummary
import io.github.laalka.slav.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * A row of the list; a draft has no price, so [unitPrice] and [total] are null for it. A simplified
 * calculation has no run size: its [quantity] is a dash and its [total] is its [unitPrice].
 */
data class ListRow(
    val id: UUID,
    val name: String,
    /** Empty when not given. */
    val counterparty: String,
    val kind: CalculationKind,
    val updatedAt: String,
    val status: CalculationStatus,
    val unitPrice: String?,
    val quantity: String,
    val total: String?,
) {
    val isDraft: Boolean get() = status == CalculationStatus.DRAFT
}

/** The quantity column of a simplified calculation, which has no run size. */
const val NO_RUN_SIZE = "—"

data class ListMessage(val text: String, val isError: Boolean)

data class ListState(
    val query: String,
    val statuses: Set<CalculationStatus>,
    val fromText: String,
    val fromError: String?,
    val toText: String,
    val toError: String?,
    val sort: CalculationSort,
    val rows: List<ListRow>,
    /** Nothing is stored at all: the screen invites a first calculation instead of showing filters. */
    val isEmpty: Boolean,
    val hasFilters: Boolean,
    /** Bumped when the filters are cleared: the filter fields are re-created with the cleared texts. */
    val filterEpoch: Int,
    /** The calculation waiting for the administrator password to be deleted. */
    val deleteQuestion: DeleteQuestion?,
    /** Every row has a check box and «В архив» is offered for the checked ones. */
    val selecting: Boolean,
    /** The checked rows; always some of [rows], so what is checked is what is shown. */
    val selected: Set<UUID>,
    /** The calculations waiting for the administrator password to be sent to the archive file. */
    val archiveQuestion: ArchiveQuestion?,
    val message: ListMessage?,
)

/**
 * «Готовые расчёты» (spec 8.1). Search, filters and sorting are the repository's: the name is
 * matched in any letter case, Cyrillic included. Created once per window, so the filters stay
 * while the user opens calculations; [reload] reads the list again when the page is shown.
 *
 * «Выбрать» turns the rows into a selection: the checked rows are sent to an archive file with
 * [requestArchive], which deletes them once the file is written and verified ([ArchiveFlow]). The
 * selection is cut to the rows shown whenever the list is read, so a filter never leaves a calculation
 * checked that the user cannot see.
 */
class CalculationListModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    adminPassword: AdminPassword,
    archive: CalculationArchive,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var query = ""
    private var statuses: Set<CalculationStatus> = emptySet()
    private var fromText = ""
    private var toText = ""
    private var sort = CalculationSort.DEFAULT
    private var filterEpoch = 0
    private val deletion = PasswordFlow(adminPassword, calculations::delete)
    private val archiving = ArchiveFlow(archive, calculations, adminPassword, onFinished = ::archived)
    private var selecting = false
    private var selected: Set<UUID> = emptySet()
    private var message: ListMessage? = null

    private val _state = MutableStateFlow(build())
    val state: StateFlow<ListState> = _state.asStateFlow()

    fun reload() = refresh()

    fun onQueryChange(text: String) {
        query = text
        refresh()
    }

    fun toggleStatus(status: CalculationStatus) {
        statuses = if (status in statuses) statuses - status else statuses + status
        refresh()
    }

    /** «Все»: no status is chosen, which shows every status. */
    fun showAllStatuses() {
        statuses = emptySet()
        refresh()
    }

    /** The first day of the period, «дд.мм.гггг»; empty leaves the period open. */
    fun onFromChange(text: String) {
        fromText = text
        refresh()
    }

    /** The last day of the period, inclusive. */
    fun onToChange(text: String) {
        toText = text
        refresh()
    }

    fun clearFilters() {
        query = ""
        statuses = emptySet()
        fromText = ""
        toText = ""
        filterEpoch++
        refresh()
    }

    /** The sorted column is reversed; another column is sorted ascending. */
    fun sortBy(column: CalculationSortColumn) {
        sort = if (sort.column == column) sort.copy(descending = !sort.descending) else CalculationSort(column, descending = false)
        refresh()
    }

    /** A draft with the input of [id], priced on the current settings when calculated (spec 8.3). */
    fun copy(id: UUID): UUID {
        val copy = calculations.copy(id)
        refresh()
        return copy
    }

    /** Deleting any calculation, whatever its status, asks for the administrator password. */
    fun requestDelete(id: UUID) {
        deletion.request(id)
        refresh()
    }

    fun onDeletePasswordChange(text: String) {
        deletion.onPasswordChange(text)
        refresh()
    }

    /** Deletes the calculation when the password is the administrator's; a wrong one is flagged and nothing is deleted. */
    fun confirmDelete() {
        deletion.confirm()
        refresh()
    }

    fun cancelDelete() {
        deletion.cancel()
        refresh()
    }

    /** «Выбрать»: the rows get check boxes, none checked. */
    fun startSelecting() {
        selecting = true
        selected = emptySet()
        refresh()
    }

    /** «Отмена»: back to the plain list; an open question about the archive is dropped with the selection. */
    fun stopSelecting() {
        selecting = false
        selected = emptySet()
        archiving.cancel()
        refresh()
    }

    /** Checks or unchecks the row [id]; a row that is not shown cannot be checked. */
    fun toggleSelected(id: UUID) {
        if (!selecting) return
        val shown = _state.value.rows.any { it.id == id }
        selected = if (id in selected) selected - id else if (shown) selected + id else selected
        refresh()
    }

    /** «Выбрать все»: checks every row shown, or unchecks them all when they are all checked already. */
    fun toggleSelectAll() {
        if (!selecting) return
        val shown = _state.value.rows.map { it.id }.toSet()
        selected = if (shown.isNotEmpty() && selected == shown) emptySet() else shown
        refresh()
    }

    /** What the save dialog of «В архив» offers: the file name of today. */
    fun suggestedArchiveName(): String = suggestedArchiveName(zone)

    /**
     * «В архив (N)» after the user has chosen the file [target]: asks the administrator password, then
     * writes the checked rows there and deletes them. Does nothing when no row is checked.
     */
    fun requestArchive(target: Path) {
        val ids = _state.value.rows.map { it.id }.filter { it in selected }
        if (ids.isEmpty()) return
        archiving.request(ids, target)
        refresh()
    }

    fun onArchivePasswordChange(text: String) {
        archiving.onPasswordChange(text)
        refresh()
    }

    /** Sends the checked rows to the archive file when the password is the administrator's; a wrong one is flagged and nothing is written. */
    fun confirmArchive() {
        archiving.confirm()
        refresh()
    }

    fun cancelArchive() {
        archiving.cancel()
        refresh()
    }

    /** Shows [outcome] above the list: the archive was made from a card, which has gone with its calculation. */
    fun announce(outcome: ArchiveOutcome) {
        message = outcome.toMessage()
        refresh()
    }

    private fun archived(outcome: ArchiveOutcome) {
        message = outcome.toMessage()
        if (outcome is ArchiveOutcome.Sent) {
            selecting = false
            selected = emptySet()
        }
    }

    /** What the save dialog offers: the calculation name with the extension of [format]. */
    fun suggestedFileName(id: UUID, format: ExportFormat): String =
        exportFileName(requireNotNull(calculations.get(id)) { "No calculation $id" }.name, format)

    /** Spec 8.5: a priced calculation written to [target]; the outcome is shown above the list. */
    fun export(id: UUID, format: ExportFormat, target: Path) {
        val calculation = requireNotNull(calculations.get(id)) { "No calculation $id" }
        val breakdown = checkNotNull(calculation.breakdown(zone)) { "Draft $id has no price to export" }
        message = try {
            format.write(breakdown, settings.load().general.currencySymbol, target)
            ListMessage("Расчёт «${calculation.name}» сохранён в файл $target", isError = false)
        } catch (e: IOException) {
            ListMessage("Не удалось сохранить файл $target: ${e.message}", isError = true)
        }
        refresh()
    }

    fun dismissMessage() {
        message = null
        refresh()
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): ListState {
        val currency = settings.load().general.currencySymbol
        val from = readDate(fromText)
        val to = readDate(toText)
        val toBeforeFrom = from is DateInput.Valid && to is DateInput.Valid && to.date < from.date
        val summaries = calculations.list(
            query = query,
            statuses = statuses,
            from = (from as? DateInput.Valid)?.date,
            to = (to as? DateInput.Valid)?.date?.takeUnless { toBeforeFrom },
            sort = sort,
        )
        val hasFilters = query.isNotBlank() || statuses.isNotEmpty() || fromText.isNotBlank() || toText.isNotBlank()
        val rows = summaries.map { it.toRow(currency) }
        val shown = rows.map { it.id }.toSet()
        selected = selected.filterTo(HashSet()) { it in shown }
        return ListState(
            query = query,
            statuses = statuses,
            fromText = fromText,
            fromError = (from as? DateInput.Invalid)?.message,
            toText = toText,
            toError = (to as? DateInput.Invalid)?.message ?: if (toBeforeFrom) "Раньше начала периода" else null,
            sort = sort,
            rows = rows,
            isEmpty = rows.isEmpty() && (!hasFilters || calculations.list().isEmpty()),
            hasFilters = hasFilters,
            filterEpoch = filterEpoch,
            // The row is asked about from the list itself, whose filters the open question leaves as they are.
            deleteQuestion = deletion.pending?.let { id -> rows.firstOrNull { it.id == id } }?.let { DeleteQuestion(it.name, deletion.error) },
            selecting = selecting,
            selected = selected,
            archiveQuestion = archiving.question,
            message = message,
        )
    }

    private fun CalculationSummary.toRow(currency: String) = ListRow(
        id = id,
        name = name.ifBlank { "Без названия" },
        counterparty = counterparty,
        kind = kind,
        updatedAt = formatDateTime(updatedAt, zone),
        status = status,
        unitPrice = unitPrice?.let { formatMoney(it, currency) },
        quantity = if (kind == CalculationKind.QUICK) NO_RUN_SIZE else quantity.toString(),
        total = total?.let { formatMoney(it, currency) },
    )

    private sealed interface DateInput {
        data object Empty : DateInput

        data class Valid(val date: LocalDate) : DateInput

        data class Invalid(val message: String) : DateInput
    }

    private fun readDate(text: String): DateInput = when {
        text.isBlank() -> DateInput.Empty
        else -> parseDate(text)?.let(DateInput::Valid) ?: DateInput.Invalid("Формат дд.мм.гггг")
    }
}
