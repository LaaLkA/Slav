package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.ThemeMode
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.RemovableChips
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.TabContent

private val FIELD_WIDTH = 240.dp

/** Test tags of «Общие». */
object GeneralTags {
    const val MIN_PAINT_AREA = "settings/general/min-paint-area"
    const val PASSWORD_CURRENT = "settings/general/password-current"
    const val PASSWORD_NEW = "settings/general/password-new"
    const val PASSWORD_REPEAT = "settings/general/password-repeat"
    const val PASSWORD_SAVE = "settings/general/password-save"
    const val PASSWORD_SUCCESS = "settings/general/password-success"
}

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
            SectionCard(
                "Покраска",
                subtitle = "Деталь меньше этой площади при покраске изделия считается как эта площадь. 0 — без минимума.",
            ) {
                InputField(
                    state.minPaintAreaPerPart.text, model::onMinPaintAreaChange, Modifier.width(FIELD_WIDTH).testTag(GeneralTags.MIN_PAINT_AREA),
                    label = "Мин. площадь окраски детали, м²", error = state.minPaintAreaPerPart.error, numeric = true,
                )
            }
            SectionCard(
                "Пароль администратора",
                subtitle = "Нужен, чтобы удалить расчёт. Не короче ${AdminPassword.MIN_LENGTH} символов.",
            ) {
                InputField(
                    "", model::onCurrentPasswordChange, Modifier.width(FIELD_WIDTH).testTag(GeneralTags.PASSWORD_CURRENT),
                    label = "Текущий пароль", error = state.password.currentError, password = true,
                )
                InputField(
                    "", model::onNewPasswordChange, Modifier.width(FIELD_WIDTH).testTag(GeneralTags.PASSWORD_NEW),
                    label = "Новый пароль", error = state.password.newError, password = true,
                )
                InputField(
                    "", model::onRepeatPasswordChange, Modifier.width(FIELD_WIDTH).testTag(GeneralTags.PASSWORD_REPEAT),
                    label = "Повторите новый пароль", error = state.password.repeatError, password = true, onEnter = model::changePassword,
                )
                Button(onClick = model::changePassword, modifier = Modifier.testTag(GeneralTags.PASSWORD_SAVE)) { Text("Сменить пароль") }
                state.password.success?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(GeneralTags.PASSWORD_SUCCESS),
                    )
                }
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
