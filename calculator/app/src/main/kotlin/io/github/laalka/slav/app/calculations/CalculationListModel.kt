package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.export.exportFileName
import io.github.laalka.slav.app.ui.formatDateTime
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.app.ui.parseDate
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

/** A row of the list; a draft has no price, so [unitPrice] and [total] are null for it. */
data class ListRow(
    val id: UUID,
    val name: String,
    val updatedAt: String,
    val status: CalculationStatus,
    val unitPrice: String?,
    val quantity: String,
    val total: String?,
) {
    val isDraft: Boolean get() = status == CalculationStatus.DRAFT
}

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
    /** The row waiting for the delete confirmation. */
    val pendingDelete: ListRow?,
    val message: ListMessage?,
)

/**
 * «Готовые расчёты» (spec 8.1). Search, filters and sorting are the repository's: the name is
 * matched in any letter case, Cyrillic included. Created once per window, so the filters stay
 * while the user opens calculations; [reload] reads the list again when the page is shown.
 */
class CalculationListModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var query = ""
    private var statuses: Set<CalculationStatus> = emptySet()
    private var fromText = ""
    private var toText = ""
    private var sort = CalculationSort.DEFAULT
    private var filterEpoch = 0
    private var pendingDelete: UUID? = null
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

    fun requestDelete(id: UUID) {
        pendingDelete = id
        refresh()
    }

    fun confirmDelete() {
        val id = pendingDelete ?: return
        pendingDelete = null
        calculations.delete(id)
        refresh()
    }

    fun cancelDelete() {
        pendingDelete = null
        refresh()
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
            pendingDelete = pendingDelete?.let { id -> rows.firstOrNull { it.id == id } },
            message = message,
        )
    }

    private fun CalculationSummary.toRow(currency: String) = ListRow(
        id = id,
        name = name.ifBlank { "Без названия" },
        updatedAt = formatDateTime(updatedAt, zone),
        status = status,
        unitPrice = unitPrice?.let { formatMoney(it, currency) },
        quantity = quantity.toString(),
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
