package io.github.laalka.slav.app.calculations

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.data.CalculationSortColumn
import io.github.laalka.slav.data.CalculationStatus
import java.util.UUID

private val SEARCH_WIDTH = 320.dp
private val DATE_WIDTH = 168.dp

/** A table column: its width (null takes the rest of the row) and whether it holds numbers. */
private enum class ListColumn(val title: String, val sort: CalculationSortColumn, val width: Dp?, val numeric: Boolean) {
    NAME("Название", CalculationSortColumn.NAME, null, numeric = false),
    UPDATED("Изменён", CalculationSortColumn.UPDATED_AT, 144.dp, numeric = false),
    STATUS("Статус", CalculationSortColumn.STATUS, 120.dp, numeric = false),
    UNIT_PRICE("Цена изделия", CalculationSortColumn.UNIT_PRICE, 136.dp, numeric = true),
    QUANTITY("Тираж", CalculationSortColumn.QUANTITY, 64.dp, numeric = true),
    TOTAL("Итог", CalculationSortColumn.TOTAL, 152.dp, numeric = true),
}

private val ACTIONS_WIDTH = 48.dp

/**
 * The start page of «Расчёты» (spec 8.1): the stored calculations with search, filters and
 * sorting. A row opens its card, or the editor for a draft, which has no price yet.
 */
@Composable
fun CalculationsScreen(
    model: CalculationListModel,
    filePicker: FilePicker,
    onNew: () -> Unit,
    onOpen: (ListRow) -> Unit,
    onCopy: (UUID) -> Unit,
) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Готовые расчёты", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onNew, modifier = Modifier.testTag(CalculationTags.NEW)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Новый расчёт", modifier = Modifier.padding(start = Spacing.small))
            }
        }
        state.message?.let { message ->
            Box(Modifier.testTag(CalculationTags.MESSAGE)) { NoticeCard(message.text, message.isError, onDismiss = model::dismissMessage) }
        }
        if (state.isEmpty) {
            EmptyState()
        } else {
            // Clearing the filters re-creates their fields, which keep what is typed in their own state.
            key(state.filterEpoch) { Filters(state, model) }
            Table(state, model, filePicker, onOpen, onCopy, Modifier.weight(1f))
        }
    }

    state.deleteQuestion?.let { question ->
        DeleteDialog(question, model::onDeletePasswordChange, onConfirm = model::confirmDelete, onCancel = model::cancelDelete)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp).testTag(CalculationTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text("Расчётов пока нет", style = MaterialTheme.typography.titleLarge)
        Text(
            "Нажмите «Новый расчёт»: название и тираж, детали с материалом и операциями, затем «Рассчитать». " +
                "Ввод сохраняется черновиком автоматически.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(560.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(state: ListState, model: CalculationListModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
        InputField(
            value = state.query,
            onValueChange = model::onQueryChange,
            modifier = Modifier.width(SEARCH_WIDTH).testTag(CalculationTags.SEARCH),
            label = "Поиск по названию",
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )
        InputField(
            value = state.fromText,
            onValueChange = model::onFromChange,
            modifier = Modifier.width(DATE_WIDTH).testTag(CalculationTags.PERIOD_FROM),
            label = "Изменён с",
            placeholder = "дд.мм.гггг",
            error = state.fromError,
        )
        InputField(
            value = state.toText,
            onValueChange = model::onToChange,
            modifier = Modifier.width(DATE_WIDTH).testTag(CalculationTags.PERIOD_TO),
            label = "по",
            placeholder = "дд.мм.гггг",
            error = state.toError,
        )
        if (state.hasFilters) {
            TextButton(
                onClick = model::clearFilters,
                modifier = Modifier.padding(top = Spacing.small).testTag(CalculationTags.CLEAR_FILTERS),
            ) { Text("Сбросить фильтры") }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Статус", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CalculationStatus.entries.forEach { status ->
            val selected = status in state.statuses
            FilterChip(
                selected = selected,
                onClick = { model.toggleStatus(status) },
                label = { Text(status.title) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                modifier = Modifier.testTag(CalculationTags.status(status)),
            )
        }
    }
}

@Composable
private fun Table(
    state: ListState,
    model: CalculationListModel,
    filePicker: FilePicker,
    onOpen: (ListRow) -> Unit,
    onCopy: (UUID) -> Unit,
    modifier: Modifier,
) {
    // The rest of the page below the filters; the rows scroll inside it.
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            ListColumn.entries.forEach { column -> SortHeader(column, state, model) }
            Box(Modifier.width(ACTIONS_WIDTH))
        }
        HorizontalDivider()
        if (state.rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.large).testTag(CalculationTags.NO_MATCHES),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = model::clearFilters) { Text("Сбросить фильтры") }
            }
        } else {
            val listState = rememberLazyListState()
            // Rows are keyed, so the list would hold on to the first visible row across a new search;
            // a new search, filter or order starts from the top instead.
            LaunchedEffect(state.query, state.statuses, state.fromText, state.toText, state.sort) { listState.scrollToItem(0) }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                        TableRow(
                            row = row,
                            tag = CalculationTags.row(index),
                            onOpen = { onOpen(row) },
                            onCopy = { onCopy(row.id) },
                            onDelete = { model.requestDelete(row.id) },
                            onExport = { format ->
                                filePicker.chooseSaveTarget(format.dialogTitle, model.suggestedFileName(row.id, format), format.extension)
                                    ?.let { model.export(row.id, format, it) }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/** A column title; a click sorts by the column, a second click reverses it. */
@Composable
private fun RowScope.SortHeader(column: ListColumn, state: ListState, model: CalculationListModel) {
    val active = state.sort.column == column.sort
    Row(
        modifier = cell(column)
            .clickable { model.sortBy(column.sort) }
            .padding(vertical = Spacing.small)
            .testTag(CalculationTags.sort(column.sort)),
        horizontalArrangement = if (column.numeric) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            column.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (active) {
            Icon(
                if (state.sort.descending) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (state.sort.descending) "по убыванию" else "по возрастанию",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TableRow(
    row: ListRow,
    tag: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExport: (ExportFormat) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen).padding(horizontal = Spacing.small).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            row.name,
            modifier = cell(ListColumn.NAME).testTag("$tag/name"),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(row.updatedAt, modifier = cell(ListColumn.UPDATED), style = MaterialTheme.typography.bodyMedium.tabular(), maxLines = 1)
        Box(cell(ListColumn.STATUS)) { StatusChip(row.status) }
        MoneyCell(row.unitPrice ?: "—", cell(ListColumn.UNIT_PRICE))
        MoneyCell(row.quantity, cell(ListColumn.QUANTITY))
        MoneyCell(row.total ?: "—", cell(ListColumn.TOTAL).testTag("$tag/total"))
        RowMenu(row, tag, onOpen, onCopy, onDelete, onExport)
    }
}

@Composable
private fun MoneyCell(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End, maxLines = 1, softWrap = false)
}

@Composable
private fun RowMenu(
    row: ListRow,
    tag: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExport: (ExportFormat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.width(ACTIONS_WIDTH)) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("$tag/menu")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Действия с расчётом")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (row.isDraft) "Открыть в редакторе" else "Открыть") },
                onClick = {
                    expanded = false
                    onOpen()
                },
                modifier = Modifier.testTag("$tag/open"),
            )
            DropdownMenuItem(
                text = { Text("Копировать") },
                onClick = {
                    expanded = false
                    onCopy()
                },
                modifier = Modifier.testTag("$tag/copy"),
            )
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text("Экспорт: ${format.title}") },
                    onClick = {
                        expanded = false
                        onExport(format)
                    },
                    // A draft has no price to export (spec 7.2).
                    enabled = !row.isDraft,
                    modifier = Modifier.testTag("$tag/export/${format.name}"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                modifier = Modifier.testTag("$tag/delete"),
            )
        }
    }
}

private fun RowScope.cell(column: ListColumn): Modifier =
    column.width?.let { Modifier.width(it) } ?: Modifier.weight(1f)
