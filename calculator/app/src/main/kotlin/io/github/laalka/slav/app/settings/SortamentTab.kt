package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.HeaderText
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.app.ui.NumberText
import io.github.laalka.slav.app.ui.RemovableChips
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.TabContent

private val DESIGNATION_WIDTH = 176.dp
private val SIZE_WIDTH = 176.dp

@Composable
fun SortamentTab(model: SortamentModel) {
    val state by model.state.collectAsState()
    TabContent {
        key(state.epoch) {
            state.sections.forEach { section ->
                when (section.kind) {
                    SortamentKind.SHEET -> SheetCard(section, model)
                    else -> SizeTableCard(section, model)
                }
            }
        }
    }
}

@Composable
private fun SheetCard(section: SortamentSection, model: SortamentModel) {
    SectionCard(section.kind.title, subtitle = "Толщины листа, мм: столбцы таблиц цен для листа.") {
        if (section.rows.isEmpty()) EmptyList() else RemovableChips(section.rows.map { it.single() }, { model.delete(section.kind, it) }, numeric = true)
        AddRow(section, model)
    }
}

@Composable
private fun SizeTableCard(section: SortamentSection, model: SortamentModel) {
    val fields = section.kind.fields
    SectionCard(section.kind.title, subtitle = "Все размеры в мм.") {
        if (section.rows.isEmpty()) {
            EmptyList()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                fields.forEach { HeaderText(it.label, Modifier.width(it.width), numeric = !it.isDesignation) }
            }
            section.rows.forEachIndexed { index, row ->
                Row(
                    Modifier.heightIn(min = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fields.zip(row).forEach { (field, value) ->
                        if (field.isDesignation) Text(value, Modifier.width(field.width)) else NumberText(value, Modifier.width(field.width))
                    }
                    IconButton(onClick = { model.delete(section.kind, index) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить ${row.joinToString(" × ")}")
                    }
                }
                HorizontalDivider()
            }
        }
        AddRow(section, model)
    }
}

@Composable
private fun AddRow(section: SortamentSection, model: SortamentModel) {
    section.form.error?.let { NoticeCard(it, isError = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
        section.kind.fields.zip(section.form.fields).forEachIndexed { index, (field, state) ->
            InputField(
                state.text, { model.onFieldChange(section.kind, index, it) }, Modifier.width(field.width),
                label = field.label, error = state.error, numeric = !field.isDesignation, onEnter = { model.add(section.kind) },
            )
        }
        AddButton { model.add(section.kind) }
    }
}

@Composable
private fun EmptyList() {
    Text("Список пуст.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val SizeField.width get() = if (isDesignation) DESIGNATION_WIDTH else SIZE_WIDTH
