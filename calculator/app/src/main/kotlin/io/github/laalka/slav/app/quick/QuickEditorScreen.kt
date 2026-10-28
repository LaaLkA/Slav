package io.github.laalka.slav.app.quick

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.attachments.AttachmentsBlock
import io.github.laalka.slav.app.editor.CHOICE_WIDTH
import io.github.laalka.slav.app.editor.CalculateBar
import io.github.laalka.slav.app.editor.Choice
import io.github.laalka.slav.app.editor.ExtraRow
import io.github.laalka.slav.app.editor.Header
import io.github.laalka.slav.app.editor.NumberInput
import io.github.laalka.slav.app.editor.TextInput
import io.github.laalka.slav.app.editor.WIDE_NUMBER_WIDTH
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.SectionCard

/**
 * The editor of a simplified calculation: the product, its drawings, the sheet and the cut. «Рассчитать»,
 * the way back and the list of missing prices are the full editor's own bar and header.
 */
@Composable
fun QuickEditorScreen(model: QuickEditorModel, filePicker: FilePicker, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header(
            title = if (state.isNew) "Новый упрощённый расчёт" else "Редактирование упрощённого расчёта",
            subtitle = state.priceSource,
            onBack = onBack,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                    .padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                SectionCard("Изделие") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                        TextInput(state.name, "Название расчёта", model::onNameChange, Modifier.weight(1f))
                        NumberInput(state.quantity, "Тираж, шт", model::onQuantityChange)
                    }
                }
                AttachmentsBlock(model.attachments, filePicker)
                SectionCard("Материал", subtitle = "Один лист: масса по площади и толщине, цена за кг по марке и толщине") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                        Choice(state.grade, Modifier.width(CHOICE_WIDTH)) { model.onGradeChange(state.grade.options[it]) }
                        Choice(state.thickness, Modifier.width(CHOICE_WIDTH)) { model.onThicknessChange(state.thicknessOptions[it]) }
                        NumberInput(state.sheetArea, "Площадь листа, м²", model::onSheetAreaChange, WIDE_NUMBER_WIDTH)
                        Column(Modifier.padding(top = Spacing.small)) {
                            Text("Масса листа", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                state.mass ?: "—",
                                style = MaterialTheme.typography.titleMedium.tabular(),
                                modifier = Modifier.testTag(QuickTags.MASS),
                                softWrap = false,
                            )
                        }
                    }
                    ExtraRow(
                        state.materialExtra,
                        state.currency,
                        { model.onExtraAmountChange(QuickLine.MATERIAL, it) },
                        { model.onExtraCommentChange(QuickLine.MATERIAL, it) },
                    )
                }
                SectionCard("Резка", subtitle = "Лазер по времени: время реза × ставка «Лазер, ${state.currency}/мин» из настроек") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
                        NumberInput(state.cutTime, "Время реза, мин", model::onCutTimeChange, WIDE_NUMBER_WIDTH)
                    }
                    ExtraRow(
                        state.cutExtra,
                        state.currency,
                        { model.onExtraAmountChange(QuickLine.CUT, it) },
                        { model.onExtraCommentChange(QuickLine.CUT, it) },
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        HorizontalDivider()
        CalculateBar(state.canCalculate, state.missingCount, state.calcErrors, state.calcErrorsHint, model::calculate)
    }
}
