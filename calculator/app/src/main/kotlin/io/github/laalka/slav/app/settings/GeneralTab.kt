package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import io.github.laalka.slav.app.ui.ButtonIcon
import io.github.laalka.slav.app.ui.DestructiveButton
import io.github.laalka.slav.app.ui.FillRandomButton
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.LineIcons
import io.github.laalka.slav.app.ui.PasswordDialog
import io.github.laalka.slav.app.ui.PasswordDialogTags
import io.github.laalka.slav.app.ui.RemovableChips
import io.github.laalka.slav.app.ui.SecondaryButton
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.SegmentedChoice
import io.github.laalka.slav.app.ui.SuccessStrip
import io.github.laalka.slav.app.ui.TabContent
import io.github.laalka.slav.data.AdminPassword

// The width of the tab from which its cards stand in two columns, each at least this wide.
private val TWO_COLUMNS = 920.dp

/** Test tags of «Общие». */
object GeneralTags {
    const val MIN_PAINT_AREA = "settings/general/min-paint-area"
    const val PASSWORD_CURRENT = "settings/general/password-current"
    const val PASSWORD_NEW = "settings/general/password-new"
    const val PASSWORD_REPEAT = "settings/general/password-repeat"
    const val PASSWORD_SAVE = "settings/general/password-save"
    const val PASSWORD_SUCCESS = "settings/general/password-success"
    const val DEVELOPER_SWITCH = "settings/general/developer-switch"
    const val DEVELOPER_PASSWORD = "settings/general/developer-password"
    const val DEVELOPER_CONFIRM = "settings/general/developer-confirm"
    const val DEVELOPER_CANCEL = "settings/general/developer-cancel"
    const val FILL_TABLES = "settings/general/fill-tables"
    const val RESET_TABLES = "settings/general/reset-tables"
    const val DEVELOPER_NOTICE = "settings/general/developer-notice"
    const val RESET_PASSWORD = "settings/general/reset-password"
    const val RESET_CONFIRM = "settings/general/reset-confirm"
    const val RESET_CANCEL = "settings/general/reset-cancel"
}

private val DEVELOPER_TAGS = PasswordDialogTags(
    GeneralTags.DEVELOPER_PASSWORD, GeneralTags.DEVELOPER_CONFIRM, GeneralTags.DEVELOPER_CANCEL,
)

private val RESET_TAGS = PasswordDialogTags(GeneralTags.RESET_PASSWORD, GeneralTags.RESET_CONFIRM, GeneralTags.RESET_CANCEL)

private const val RESET_MESSAGE = "Материалы, сортамент, цены материала и цены операций вернутся к состоянию после установки " +
    "программы. Вместе с ними вернутся настройки вкладки «Общие», кроме пароля, темы и режима разработчика. " +
    "Сохранённые расчёты, чертежи и детали библиотеки не изменятся: у каждого расчёта остаются его собственные цены."

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneralTab(model: GeneralModel) {
    val state by model.state.collectAsState()
    TabContent {
        key(state.epoch) {
            CardColumns(
                left = {
                    SectionCard("Производство", subtitle = "Ограничения оборудования и минимумы, которые учитывает расчёт.") {
                        InputField(
                            state.tubeLaserMaxSection.text, model::onMaxSectionChange, Modifier.fillMaxWidth(),
                            label = "Труборез: максимальное сечение, мм", error = state.tubeLaserMaxSection.error, numeric = true,
                        )
                        Hint("Трубы и профиль с сечением больше этого размера труборез не берёт.")
                        InputField(
                            state.minPaintAreaPerPart.text, model::onMinPaintAreaChange, Modifier.fillMaxWidth().testTag(GeneralTags.MIN_PAINT_AREA),
                            label = "Мин. площадь окраски детали, м²", error = state.minPaintAreaPerPart.error, numeric = true,
                        )
                        Hint("Деталь меньше этой площади при покраске изделия считается как эта площадь. 0 — без минимума.")
                    }
                    SectionCard("Валюта", subtitle = "Обозначение в ценах и итогах.") {
                        InputField(
                            state.currency.text, model::onCurrencyChange, Modifier.fillMaxWidth(),
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
                                state.boundForm.text, model::onBoundChange, Modifier.weight(1f),
                                label = "Новая граница, мм", error = state.boundForm.error, numeric = true, onEnter = model::addBound,
                            )
                            AddButton(model::addBound)
                        }
                    }
                    SectionCard("Типы сварки", subtitle = "Для каждого типа — своя таблица цен сварки.") {
                        RemovableChips(state.weldTypes, { model.deleteWeldType(state.weldTypes[it]) })
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                            InputField(
                                state.weldTypeForm.text, model::onWeldTypeChange, Modifier.weight(1f),
                                label = "Новый тип сварки", error = state.weldTypeForm.error, onEnter = model::addWeldType,
                            )
                            AddButton(model::addWeldType)
                        }
                    }
                },
                right = {
                    SectionCard("Тема оформления", subtitle = "Хранится на этом компьютере.") {
                        SegmentedChoice(
                            options = ThemeMode.entries.map { it.title },
                            selectedIndex = ThemeMode.entries.indexOf(state.theme),
                            onSelect = { model.setTheme(ThemeMode.entries[it]) },
                        )
                    }
                    SectionCard(
                        "Пароль администратора",
                        subtitle = "Нужен, чтобы удалить расчёт. Не короче ${AdminPassword.MIN_LENGTH} символов.",
                    ) {
                        InputField(
                            "", model::onCurrentPasswordChange, Modifier.fillMaxWidth().testTag(GeneralTags.PASSWORD_CURRENT),
                            label = "Текущий пароль", error = state.password.currentError, password = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap), verticalAlignment = Alignment.Top) {
                            InputField(
                                "", model::onNewPasswordChange, Modifier.weight(1f).testTag(GeneralTags.PASSWORD_NEW),
                                label = "Новый пароль", error = state.password.newError, password = true,
                            )
                            InputField(
                                "", model::onRepeatPasswordChange, Modifier.weight(1f).testTag(GeneralTags.PASSWORD_REPEAT),
                                label = "Повторите новый пароль", error = state.password.repeatError, password = true, onEnter = model::changePassword,
                            )
                        }
                        SecondaryButton(onClick = model::changePassword, modifier = Modifier.testTag(GeneralTags.PASSWORD_SAVE)) { Text("Сменить пароль") }
                        state.password.success?.let { SuccessStrip(it, Modifier.testTag(GeneralTags.PASSWORD_SUCCESS)) }
                    }
                    SectionCard(
                        "Режим разработчика",
                        subtitle = "В редакторах расчётов появляется кнопка «Заполнить случайными данными», а здесь — кнопка " +
                            "«Заполнить таблицы случайными данными»: она добавляет марки, размеры и цены во все таблицы настроек, " +
                            "введённые цены не перезаписываются. Режим хранится на этом компьютере, в настройки и экспорт не входит. " +
                            "Включить можно, зная пароль администратора.",
                        dashed = MaterialTheme.colorScheme.tertiary,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = state.developerMode,
                                    onCheckedChange = model::onDeveloperModeChange,
                                    modifier = Modifier.testTag(GeneralTags.DEVELOPER_SWITCH),
                                )
                                Text(if (state.developerMode) "Вкл." else "Выкл.", style = MaterialTheme.typography.titleSmall)
                            }
                        },
                    ) {
                        if (state.developerMode) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                FillRandomButton(
                                    model::fillTablesRandomly,
                                    Modifier.testTag(GeneralTags.FILL_TABLES),
                                    label = "Заполнить таблицы случайными данными",
                                )
                                DestructiveButton(onClick = model::requestResetTables, modifier = Modifier.testTag(GeneralTags.RESET_TABLES)) {
                                    ButtonIcon(LineIcons.Reset)
                                    Text("Очистить таблицы (исходное состояние)", modifier = Modifier.padding(start = Spacing.small))
                                }
                            }
                            state.developerNotice?.let { SuccessStrip(it, Modifier.testTag(GeneralTags.DEVELOPER_NOTICE)) }
                        }
                    }
                },
            )
        }
    }
    state.developerQuestion?.let { question ->
        PasswordDialog(
            title = "Режим разработчика",
            message = "Введите пароль администратора, чтобы включить режим разработчика.",
            confirmLabel = "Включить",
            error = question.error,
            tags = DEVELOPER_TAGS,
            onPasswordChange = model::onDeveloperPasswordChange,
            onConfirm = model::confirmDeveloperMode,
            onCancel = model::cancelDeveloperMode,
            destructive = false,
        )
    }
    state.resetQuestion?.let { question ->
        PasswordDialog(
            title = "Очистить таблицы?",
            message = "$RESET_MESSAGE Введите пароль администратора.",
            confirmLabel = "Очистить",
            error = question.error,
            tags = RESET_TAGS,
            onPasswordChange = model::onResetPasswordChange,
            onConfirm = model::confirmResetTables,
            onCancel = model::cancelResetTables,
        )
    }
}

/** A small explanation under a field, in the secondary colour. */
@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The cards of the tab in two columns when the tab is wide enough (the `repeat(auto-fit, minmax(…))` of the mockup),
 * [left] and [right] each a column of cards; narrower, the one under the other. The columns are separate so that
 * cards of different height do not leave holes between rows.
 */
@Composable
private fun CardColumns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints {
        if (maxWidth >= TWO_COLUMNS) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.medium), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.medium), content = right)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                left()
                right()
            }
        }
    }
}
