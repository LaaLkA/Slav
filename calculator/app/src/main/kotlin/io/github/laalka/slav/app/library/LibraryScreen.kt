package io.github.laalka.slav.app.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.AccentTextButton
import io.github.laalka.slav.app.ui.DestructiveButton
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.PrimaryButton
import io.github.laalka.slav.app.ui.SecondaryButton
import io.github.laalka.slav.app.ui.SlavDialog
import io.github.laalka.slav.app.ui.TableDivider
import io.github.laalka.slav.app.ui.tableRow

private val SEARCH_WIDTH = 320.dp

/**
 * «Детали»: the library of ready parts with search, rename and delete. A part is put into the
 * library from the card of a part in the editor, and added to a product from there too.
 */
@Composable
fun LibraryScreen(model: LibraryModel) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.page, vertical = Spacing.pageTight),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Библиотека деталей", style = MaterialTheme.typography.headlineSmall)
        if (state.isEmpty) {
            EmptyState()
        } else {
            InputField(
                value = state.query,
                onValueChange = model::onQueryChange,
                modifier = Modifier.width(SEARCH_WIDTH).testTag(LibraryTags.SEARCH),
                label = "Поиск по названию",
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            Rows(state, model, Modifier.weight(1f, fill = false))
        }
    }

    state.renameQuestion?.let { question ->
        RenameDialog(question, model::onRenameChange, onConfirm = model::confirmRename, onCancel = model::cancelRename)
    }
    state.deleteQuestion?.let { name -> DeleteDialog(name, onConfirm = model::confirmDelete, onCancel = model::cancelDelete) }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp).testTag(LibraryTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text("В библиотеке пока нет деталей", style = MaterialTheme.typography.titleLarge)
        Text(
            "Заполните деталь в расчёте и нажмите «В библиотеку» на её карточке. Сохранённую деталь можно добавить " +
                "в любое изделие кнопкой «Добавить из библиотеки» над списком деталей.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(560.dp),
        )
    }
}

@Composable
private fun Rows(state: LibraryState, model: LibraryModel, modifier: Modifier) {
    if (state.rows.isEmpty()) {
        Text(
            "Ничего не найдено",
            style = MaterialTheme.typography.titleMedium,
            modifier = modifier.fillMaxWidth().padding(vertical = Spacing.large).testTag(LibraryTags.NO_MATCHES),
            textAlign = TextAlign.Center,
        )
        return
    }
    val listState = rememberLazyListState()
    // Rows are keyed, so the list would hold on to the first visible row across a new search; it starts from the top instead.
    LaunchedEffect(state.query) { listState.scrollToItem(0) }
    // The rows are a table in a card, as in the list of calculations: as high as its rows, up to the rest of the page.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.clip(MaterialTheme.shapes.large)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    TemplateRow(row, LibraryTags.row(index), onRename = { model.requestRename(row.id) }, onDelete = { model.requestDelete(row.id) })
                    if (index < state.rows.lastIndex) TableDivider()
                }
            }
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.fillMaxHeight())
            }
        }
    }
}

@Composable
private fun TemplateRow(row: LibraryRow, tag: String, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).tableRow().padding(horizontal = Spacing.card).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$tag/name"))
            Text(
                row.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("$tag/summary"),
            )
        }
        AccentTextButton(onClick = onRename, modifier = Modifier.testTag("$tag/rename")) { Text("Переименовать") }
        AccentTextButton(onClick = onDelete, modifier = Modifier.testTag("$tag/delete")) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RenameDialog(question: RenameQuestion, onChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SlavDialog(
        title = "Переименовать деталь",
        onDismissRequest = onCancel,
        confirmButton = { PrimaryButton(onClick = onConfirm, modifier = Modifier.testTag(LibraryTags.RENAME_CONFIRM)) { Text("Переименовать") } },
        dismissButton = { SecondaryButton(onClick = onCancel, modifier = Modifier.testTag(LibraryTags.RENAME_CANCEL)) { Text("Отмена") } },
    ) {
        InputField(
            value = question.name,
            onValueChange = onChange,
            modifier = Modifier.width(380.dp).testTag(LibraryTags.RENAME_FIELD),
            label = "Название детали",
            error = question.error,
            onEnter = onConfirm,
        )
    }
}

@Composable
private fun DeleteDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SlavDialog(
        title = "Удалить деталь из библиотеки?",
        onDismissRequest = onCancel,
        confirmButton = { DestructiveButton(onClick = onConfirm, modifier = Modifier.testTag(LibraryTags.DELETE_CONFIRM)) { Text("Удалить") } },
        dismissButton = { SecondaryButton(onClick = onCancel, modifier = Modifier.testTag(LibraryTags.DELETE_CANCEL)) { Text("Отмена") } },
    ) {
        Text(
            "Деталь «$name» будет удалена из библиотеки. Расчёты, в которые она уже добавлена, не изменятся.",
            modifier = Modifier.width(380.dp),
        )
    }
}
