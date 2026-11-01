package io.github.laalka.slav.app.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.attachments.AttachmentsBlock
import io.github.laalka.slav.app.editor.Choice
import io.github.laalka.slav.app.editor.EditorBody
import io.github.laalka.slav.app.editor.ExtraRow
import io.github.laalka.slav.app.editor.Header
import io.github.laalka.slav.app.editor.NumberInput
import io.github.laalka.slav.app.editor.ProductCard
import io.github.laalka.slav.app.editor.WIDE_NUMBER_WIDTH
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.FieldGrid
import io.github.laalka.slav.app.ui.FillRandomButton
import io.github.laalka.slav.app.ui.SectionCard

/**
 * The editor of a simplified calculation: the product, its drawings, the sheet, the cut and the discount.
 * It has no run size. «Рассчитать», the way back and the list of missing prices are the full editor's
 * own bar and header. [onFillRandom] is set in developer mode: the button that fills the editor with random data.
 */
@Composable
fun QuickEditorScreen(model: QuickEditorModel, filePicker: FilePicker, onBack: () -> Unit, onFillRandom: (() -> Unit)? = null) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header(
            title = when {
                state.isNew -> "Новый упрощённый расчёт"
                state.inPlace -> "Изменение упрощённого расчёта"
                else -> "Редактирование упрощённого расчёта"
            },
            subtitle = if (state.inPlace) "${state.priceSource}. Расчёт не изменится, пока вы не нажмёте «Рассчитать»" else state.priceSource,
            onBack = onBack,
            actions = { onFillRandom?.let { FillRandomButton(it, Modifier.testTag(QuickTags.FILL_RANDOM)) } },
        )
        EditorBody(
            canCalculate = state.canCalculate,
            missingCount = state.missingCount,
            summary = state.summary,
            calcErrors = state.calcErrors,
            calcErrorsHint = state.calcErrorsHint,
            onCalculate = model::calculate,
            modifier = Modifier.weight(1f),
        ) {
            // The fields keep what was typed; a form replaced as a whole needs them created again.
            key(state.epoch) {
                ProductCard(
                    name = state.name,
                    onNameChange = model::onNameChange,
                    counterparty = state.counterparty,
                    onCounterpartyChange = model::onCounterpartyChange,
                    quantity = null,
                    onQuantityChange = {},
                    discount = state.discount,
                    onDiscountChange = model::onDiscountChange,
                )
                AttachmentsBlock(model.attachments, filePicker)
                SectionCard("Материал", subtitle = "Один лист: масса по площади и толщине, цена за кг по марке и толщине") {
                    FieldGrid(minCell = CELL) {
                        Choice(state.grade, Modifier.fillMaxWidth()) { model.onGradeChange(state.grade.options[it]) }
                        Choice(state.thickness, Modifier.fillMaxWidth()) { model.onThicknessChange(state.thicknessOptions[it]) }
                        NumberInput(state.sheetArea, "Площадь листа, м²", model::onSheetAreaChange, Modifier)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Bottom) {
                        Text("Масса листа", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            state.mass ?: "—",
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            modifier = Modifier.testTag(QuickTags.MASS),
                            softWrap = false,
                        )
                    }
                    ExtraRow(
                        state.materialExtra,
                        state.currency,
                        { model.onExtraAmountChange(QuickLine.MATERIAL, it) },
                        { model.onExtraCommentChange(QuickLine.MATERIAL, it) },
                    )
                }
                SectionCard("Резка", subtitle = "Лазер по времени: время реза × ставка «Лазер, ${state.currency}/мин» из настроек") {
                    NumberInput(state.cutTime, "Время реза, мин", model::onCutTimeChange, WIDE_NUMBER_WIDTH)
                    ExtraRow(
                        state.cutExtra,
                        state.currency,
                        { model.onExtraAmountChange(QuickLine.CUT, it) },
                        { model.onExtraCommentChange(QuickLine.CUT, it) },
                    )
                }
            }
        }
    }
}

// The cell of the grid of the sheet's fields, as in a part of the full editor.
private val CELL = 140.dp
