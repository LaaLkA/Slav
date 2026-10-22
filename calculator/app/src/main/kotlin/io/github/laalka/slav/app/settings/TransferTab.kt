package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.HeaderText
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.app.ui.NumberText
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.TabContent

private val SECTION_WIDTH = 280.dp
private val COUNT_WIDTH = 112.dp

@Composable
fun TransferTab(model: TransferModel, filePicker: FilePicker) {
    val state by model.state.collectAsState()
    TabContent {
        SectionCard("Экспорт настроек", subtitle = "Справочники, сортамент и все цены — в один файл .json. Расчёты в файл не входят.") {
            Button(onClick = { filePicker.chooseSaveTarget("Экспорт настроек", model.suggestedFileName(), ".json")?.let(model::export) }) {
                Text("Экспортировать в файл…")
            }
        }
        SectionCard("Импорт настроек", subtitle = "Перед применением будет показано, что изменится.") {
            Text(
                "Полная замена — текущие настройки заменяются файлом; прежние сохраняются в резервную копию.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Слияние — совпавшие по названию элементы берут значения из файла, новые добавляются, остальные остаются.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                ImportMode.entries.forEach { mode ->
                    OutlinedButton(onClick = { filePicker.chooseImportSource()?.let { model.prepareImport(it, mode) } }) {
                        Text(mode.title)
                    }
                }
            }
        }
        state.message?.let { NoticeCard(it.text, it.isError, onDismiss = model::dismissMessage) }
        state.backupPath?.let { path ->
            SectionCard("Резервная копия прежних настроек", subtitle = "Её можно импортировать обратно полной заменой.") {
                SelectionContainer { Text(path, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        state.pending?.let { PendingImportCard(it, model) }
    }
}

@Composable
private fun PendingImportCard(pending: PendingImport, model: TransferModel) {
    SectionCard("${pending.mode.title}: «${pending.fileName}»", subtitle = pending.fileInfo) {
        if (pending.changes.isEmpty()) {
            Text("Файл не меняет текущие настройки.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                HeaderText("Раздел", Modifier.width(SECTION_WIDTH))
                HeaderText("Добавится", Modifier.width(COUNT_WIDTH), numeric = true)
                HeaderText("Изменится", Modifier.width(COUNT_WIDTH), numeric = true)
                HeaderText("Удалится", Modifier.width(COUNT_WIDTH), numeric = true)
            }
            pending.changes.forEach { ChangeRow(it, bold = false) }
            HorizontalDivider()
            ChangeRow(pending.total, bold = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Button(onClick = model::confirmImport) { Text("Применить") }
            TextButton(onClick = model::cancelImport) { Text("Отмена") }
        }
    }
}

@Composable
private fun ChangeRow(row: SectionChangeRow, bold: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(
            row.title,
            Modifier.width(SECTION_WIDTH),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else null,
        )
        listOf(row.added, row.changed, row.removed).forEach { NumberText(it.toString(), Modifier.width(COUNT_WIDTH)) }
    }
}
