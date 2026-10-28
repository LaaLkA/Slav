package io.github.laalka.slav.app.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.NoticeCard

private val DIALOG_WIDTH = 480.dp
private val LIST_MAX_HEIGHT = 320.dp

/** «Добавить из библиотеки» with what the last save or insert did; it goes above the parts of the editor. */
@Composable
fun AddFromLibraryRow(model: EditorLibraryModel) {
    val state by model.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        state.notice?.let { notice ->
            Box(Modifier.testTag(LibraryTags.NOTICE)) { NoticeCard(notice, isError = false, onDismiss = model::dismissNotice) }
        }
        OutlinedButton(onClick = model::openPick, modifier = Modifier.testTag(LibraryTags.ADD_FROM_LIBRARY)) {
            Text("Добавить из библиотеки")
        }
    }
}

/** The dialogs the editor opens on the library: saving a part and picking one. */
@Composable
fun EditorLibraryDialogs(model: EditorLibraryModel) {
    val state by model.state.collectAsState()
    state.save?.let { SaveDialog(it, model) }
    state.pick?.let { PickDialog(it, model) }
}

@Composable
private fun SaveDialog(dialog: SavePartDialog, model: EditorLibraryModel) {
    val existing = dialog.replacing
    AlertDialog(
        onDismissRequest = model::cancelSave,
        title = { Text(if (existing == null) "Сохранить деталь в библиотеку" else "Заменить деталь в библиотеке?") },
        text = {
            if (existing == null) {
                InputField(
                    value = dialog.name,
                    onValueChange = model::onSaveNameChange,
                    modifier = Modifier.width(DIALOG_WIDTH).testTag(LibraryTags.SAVE_NAME),
                    label = "Название в библиотеке",
                    error = dialog.error,
                    onEnter = model::confirmSave,
                )
            } else {
                Text(
                    "Деталь «$existing» уже есть в библиотеке. Заменить её этой деталью? " +
                        "Расчёты, в которые прежняя деталь уже добавлена, не изменятся.",
                    modifier = Modifier.width(DIALOG_WIDTH).testTag(LibraryTags.REPLACE_QUESTION),
                )
            }
        },
        confirmButton = {
            if (existing == null) {
                TextButton(onClick = model::confirmSave, modifier = Modifier.testTag(LibraryTags.SAVE_CONFIRM)) { Text("Сохранить") }
            } else {
                TextButton(onClick = model::confirmReplace, modifier = Modifier.testTag(LibraryTags.REPLACE_CONFIRM)) {
                    Text("Заменить", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (existing == null) {
                TextButton(onClick = model::cancelSave, modifier = Modifier.testTag(LibraryTags.SAVE_CANCEL)) { Text("Отмена") }
            } else {
                TextButton(onClick = model::backToName, modifier = Modifier.testTag(LibraryTags.REPLACE_BACK)) { Text("Назад") }
            }
        },
    )
}

@Composable
private fun PickDialog(dialog: PickPartDialog, model: EditorLibraryModel) {
    AlertDialog(
        onDismissRequest = model::closePick,
        title = { Text("Добавить из библиотеки") },
        text = {
            Column(Modifier.width(DIALOG_WIDTH), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                InputField(
                    value = dialog.query,
                    onValueChange = model::onPickQueryChange,
                    modifier = Modifier.fillMaxWidth().testTag(LibraryTags.PICK_SEARCH),
                    label = "Поиск по названию",
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
                when {
                    dialog.libraryIsEmpty -> Text(
                        "В библиотеке пока нет деталей. Сохранить деталь можно кнопкой «В библиотеку» на её карточке.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(LibraryTags.PICK_EMPTY),
                    )
                    dialog.rows.isEmpty() -> Text(
                        "Ничего не найдено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(LibraryTags.PICK_NO_MATCHES),
                    )
                    else -> Column(Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                        dialog.rows.forEachIndexed { index, row ->
                            PickRow(row, LibraryTags.pickRow(index), onPick = { model.pick(row.id) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = model::closePick, modifier = Modifier.testTag(LibraryTags.PICK_CLOSE)) { Text("Закрыть") } },
    )
}

@Composable
private fun PickRow(row: LibraryRow, tag: String, onPick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = Spacing.small, horizontal = Spacing.small).testTag(tag)) {
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
}
