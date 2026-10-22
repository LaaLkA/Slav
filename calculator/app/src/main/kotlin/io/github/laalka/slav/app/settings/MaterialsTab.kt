package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilledTonalButton
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
import io.github.laalka.slav.app.ui.OptionPicker
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.TabContent

private val NAME_WIDTH = 320.dp
private val GROUP_WIDTH = 240.dp
private val DENSITY_WIDTH = 176.dp

@Composable
fun MaterialsTab(model: MaterialsModel) {
    val state by model.state.collectAsState()
    TabContent {
        key(state.epoch) {
            state.notice?.let { NoticeCard(it, isError = true, onDismiss = model::dismissNotice) }
            GroupsCard(state, model)
            GradesCard(state, model)
        }
    }
}

@Composable
private fun GroupsCard(state: MaterialsState, model: MaterialsModel) {
    SectionCard("Группы материалов", subtitle = "Цены операций задаются по группам; марка относится к одной группе.") {
        state.groups.forEach { group ->
            key(group.name) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    val rename = state.rename?.takeIf { it.target == RenameTarget.GROUP && it.oldName == group.name }
                    if (rename != null) {
                        RenameEditor(rename, model, Modifier.width(NAME_WIDTH))
                        Spacer(Modifier.weight(1f))
                    } else {
                        Text(group.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (group.gradeNames.isEmpty()) "нет марок" else "марки: ${group.gradeNames.joinToString()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        RowActions(
                            onRename = { model.startRename(RenameTarget.GROUP, group.name) },
                            onDelete = { model.deleteGroup(group.name) },
                            name = group.name,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
            InputField(
                state.groupForm.text, model::onGroupNameChange, Modifier.width(NAME_WIDTH),
                label = "Новая группа", error = state.groupForm.error, onEnter = model::addGroup,
            )
            AddButton(model::addGroup)
        }
    }
}

@Composable
private fun GradesCard(state: MaterialsState, model: MaterialsModel) {
    val groupNames = state.groups.map { it.name }
    SectionCard("Марки", subtitle = "Плотность нужна для массы детали: сталь ≈ 7850, алюминий ≈ 2700 кг/м³.") {
        if (state.grades.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                HeaderText("Марка", Modifier.width(NAME_WIDTH))
                HeaderText("Группа", Modifier.width(GROUP_WIDTH))
                // The end padding lines the header up with the text inside the field below it.
                HeaderText("Плотность, кг/м³", Modifier.width(DENSITY_WIDTH).padding(end = Spacing.medium), numeric = true)
            }
        }
        state.grades.forEach { grade ->
            key(grade.name) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val rename = state.rename?.takeIf { it.target == RenameTarget.GRADE && it.oldName == grade.name }
                    if (rename != null) {
                        RenameEditor(rename, model, Modifier.width(NAME_WIDTH))
                    } else {
                        Text(grade.name, modifier = Modifier.width(NAME_WIDTH), style = MaterialTheme.typography.bodyLarge)
                    }
                    OptionPicker(grade.group, groupNames, { model.setGradeGroup(grade.name, it) }, Modifier.width(GROUP_WIDTH))
                    InputField(
                        grade.density.text, { model.onDensityChange(grade.name, it) }, Modifier.width(DENSITY_WIDTH),
                        error = grade.density.error, numeric = true,
                    )
                    Spacer(Modifier.weight(1f))
                    if (rename == null) {
                        RowActions(
                            onRename = { model.startRename(RenameTarget.GRADE, grade.name) },
                            onDelete = { model.deleteGrade(grade.name) },
                            name = grade.name,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        if (groupNames.isEmpty()) {
            Text("Сначала добавьте группу материалов.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                InputField(
                    state.gradeForm.name.text, model::onGradeNameChange, Modifier.width(NAME_WIDTH),
                    label = "Новая марка", error = state.gradeForm.name.error, onEnter = model::addGrade,
                )
                OptionPicker(
                    state.gradeForm.group, groupNames, model::onGradeGroupChange,
                    Modifier.width(GROUP_WIDTH).padding(top = Spacing.small),
                )
                InputField(
                    state.gradeForm.density.text, model::onGradeDensityChange, Modifier.width(DENSITY_WIDTH),
                    label = "Плотность, кг/м³", error = state.gradeForm.density.error, numeric = true, onEnter = model::addGrade,
                )
                AddButton(model::addGrade)
            }
        }
    }
}

@Composable
private fun RenameEditor(rename: Rename, model: MaterialsModel, modifier: Modifier) {
    InputField(
        rename.field.text, model::onRenameChange, modifier,
        error = rename.field.error, onEnter = model::confirmRename,
    )
    IconButton(onClick = model::confirmRename) { Icon(Icons.Filled.Check, contentDescription = "Сохранить название") }
    IconButton(onClick = model::cancelRename) { Icon(Icons.Filled.Close, contentDescription = "Отменить переименование") }
}

@Composable
private fun RowActions(onRename: () -> Unit, onDelete: () -> Unit, name: String) {
    IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Переименовать $name") }
    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить $name") }
}

@Composable
fun AddButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = Spacing.small)) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(Spacing.small))
        Text("Добавить")
    }
}
