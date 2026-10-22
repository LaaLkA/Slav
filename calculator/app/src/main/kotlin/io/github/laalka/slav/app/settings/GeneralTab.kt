package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.ThemeMode
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.RemovableChips
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.TabContent

private val FIELD_WIDTH = 240.dp

@Composable
fun GeneralTab(model: GeneralModel) {
    val state by model.state.collectAsState()
    TabContent {
        key(state.epoch) {
            SectionCard("Валюта", subtitle = "Обозначение в ценах и итогах.") {
                InputField(
                    state.currency.text, model::onCurrencyChange, Modifier.width(FIELD_WIDTH),
                    label = "Обозначение валюты", error = state.currency.error,
                )
            }
            SectionCard(
                "Диапазоны длины гиба",
                subtitle = "Границы, мм. Граница входит в нижний диапазон: гиб ровно 100 мм — «до 100».",
            ) {
                RemovableChips(state.bendBounds, model::deleteBound, numeric = true)
                Text(
                    "Диапазоны: ${state.bendRanges.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                    InputField(
                        state.boundForm.text, model::onBoundChange, Modifier.width(FIELD_WIDTH),
                        label = "Новая граница, мм", error = state.boundForm.error, numeric = true, onEnter = model::addBound,
                    )
                    AddButton(model::addBound)
                }
            }
            SectionCard("Типы сварки", subtitle = "Для каждого типа — своя таблица цен сварки.") {
                RemovableChips(state.weldTypes, { model.deleteWeldType(state.weldTypes[it]) })
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                    InputField(
                        state.weldTypeForm.text, model::onWeldTypeChange, Modifier.width(FIELD_WIDTH),
                        label = "Новый тип сварки", error = state.weldTypeForm.error, onEnter = model::addWeldType,
                    )
                    AddButton(model::addWeldType)
                }
            }
            SectionCard("Труборез", subtitle = "Трубы и профиль с сечением больше этого размера труборез не берёт.") {
                InputField(
                    state.tubeLaserMaxSection.text, model::onMaxSectionChange, Modifier.width(FIELD_WIDTH),
                    label = "Максимальное сечение, мм", error = state.tubeLaserMaxSection.error, numeric = true,
                )
            }
            SectionCard("Тема оформления") {
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.theme == mode,
                            onClick = { model.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        ) {
                            Text(mode.title)
                        }
                    }
                }
            }
        }
    }
}
