package io.github.laalka.slav.app.library

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.InputField

private val SEARCH_WIDTH = 320.dp

/**
 * «Детали»: the library of ready parts with search, rename and delete. A part is put into the
 * library from the card of a part in the editor, and added to a product from there too.
 */
@Composable
fun LibraryScreen(model: LibraryModel) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
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
            Rows(state, model, Modifier.weight(1f))
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
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                TemplateRow(row, LibraryTags.row(index), onRename = { model.requestRename(row.id) }, onDelete = { model.requestDelete(row.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun TemplateRow(row: LibraryRow, tag: String, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.small).testTag(tag),
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
        TextButton(onClick = onRename, modifier = Modifier.testTag("$tag/rename")) { Text("Переименовать") }
        TextButton(onClick = onDelete, modifier = Modifier.testTag("$tag/delete")) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RenameDialog(question: RenameQuestion, onChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Переименовать деталь") },
        text = {
            InputField(
                value = question.name,
                onValueChange = onChange,
                modifier = Modifier.width(380.dp).testTag(LibraryTags.RENAME_FIELD),
                label = "Название детали",
                error = question.error,
                onEnter = onConfirm,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(LibraryTags.RENAME_CONFIRM)) { Text("Переименовать") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag(LibraryTags.RENAME_CANCEL)) { Text("Отмена") } },
    )
}

@Composable
private fun DeleteDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Удалить деталь из библиотеки?") },
        text = {
            Text(
                "Деталь «$name» будет удалена из библиотеки. Расчёты, в которые она уже добавлена, не изменятся.",
                modifier = Modifier.width(380.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(LibraryTags.DELETE_CONFIRM)) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag(LibraryTags.DELETE_CANCEL)) { Text("Отмена") } },
    )
}
