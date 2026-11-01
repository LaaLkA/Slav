package io.github.laalka.slav.app.editor

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.laalka.slav.app.calculations.PriceSummary
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.extendedColors
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.CardSurface
import io.github.laalka.slav.app.ui.PrimaryButton
import io.github.laalka.slav.app.ui.RailWidth
import io.github.laalka.slav.app.ui.WarningPanel

/** From this window width an editor has two columns: the form and the cost panel; narrower, the cost is a bar under the form. */
internal val TWO_COLUMN_WINDOW = 1100.dp

/** The width of the cost panel of the wide layout. */
internal val COST_PANEL_WIDTH = 340.dp

private val CALCULATE_HEIGHT = 48.dp

/**
 * What both editors are made of under their title bar: the scrolling [form] and the price with «Рассчитать». In a
 * window of [TWO_COLUMN_WINDOW] or more the price is the [CostPanel] to the right of the form; in a narrower one it is
 * the [CalculateBar] under it. The page the layout is on lies right of the rail, so the rail is part of the window.
 *
 * The form scrolls on its own and the panel stays where it is, but the scrollbar of the form is not part of the form's
 * column: in the wide layout it stands at the right edge of the whole body, as the scrollbar of the page does in the
 * mockup, and the panel leaves room for it.
 */
@Composable
internal fun EditorBody(
    canCalculate: Boolean,
    missingCount: Int,
    summary: PriceSummary?,
    calcErrors: List<ReadableError>,
    calcErrorsHint: String?,
    onCalculate: () -> Unit,
    modifier: Modifier = Modifier,
    form: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        if (maxWidth + RailWidth >= TWO_COLUMN_WINDOW) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    FormScroll(scroll, Modifier.weight(1f).fillMaxHeight(), end = Spacing.large, content = form)
                    // The scrollbar stands in the margin of the page, at the edge of the window; a thicker one would push the panel in.
                    CostPanel(
                        canCalculate, missingCount, summary, calcErrors, calcErrorsHint, onCalculate,
                        end = maxOf(Spacing.pageTight, LocalScrollbarStyle.current.thickness),
                    )
                }
                FormScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    FormScroll(scroll, Modifier.fillMaxSize(), end = Spacing.pageTight, content = form)
                    FormScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
                }
                HorizontalDivider()
                CalculateBar(canCalculate, missingCount, summary, calcErrors, calcErrorsHint, onCalculate)
            }
        }
    }
}

/** The form of an editor: its cards one under another, scrolling by [scroll], with [end] of room on the right for what stands beside it. */
@Composable
private fun FormScroll(scroll: ScrollState, modifier: Modifier, end: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.verticalScroll(scroll)
            .padding(start = Spacing.pageTight, end = end, top = Spacing.large, bottom = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        content = content,
    )
}

/** The scrollbar of the [FormScroll] of [scroll]; the box it is in decides where it stands, [modifier] says on which side. */
@Composable
private fun FormScrollbar(scroll: ScrollState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(scroll), modifier.fillMaxHeight().testTag(EditorTags.FORM_SCROLLBAR))
}

/**
 * «Стоимость»: the price of the input as it stands, live, in the right column of the wide layout. The lines it is
 * made of, the discount, the price of a piece, the run total, then why «Рассчитать» is shut or what stopped the last
 * attempt, and the button. While a field is missing there is no price: the two sums stand as dashes. The tags are the
 * ones of the bar, so a test finds the price in either layout. [end] is the room on its right, where the scrollbar of the
 * form stands at the edge of the window: the margin of the page, or the scrollbar if that is wider.
 */
@Composable
private fun CostPanel(
    canCalculate: Boolean,
    missingCount: Int,
    summary: PriceSummary?,
    calcErrors: List<ReadableError>,
    calcErrorsHint: String?,
    onCalculate: () -> Unit,
    end: Dp,
) {
    Column(
        modifier = Modifier.width(COST_PANEL_WIDTH + end)
            .padding(end = end, top = Spacing.large, bottom = Spacing.large)
            .verticalScroll(rememberScrollState()),
    ) {
        CardSurface(Modifier.testTag(EditorTags.COST_PANEL)) {
            Text("Стоимость", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                summary?.lines?.forEachIndexed { index, line -> PanelLine(line.title, line.amount, EditorTags.summaryLine(index)) }
                summary?.discount?.let { discount ->
                    PanelLine("Сумма без скидки", discount.subtotal, EditorTags.SUMMARY_SUBTOTAL)
                    PanelLine(discount.label, discount.amount, EditorTags.SUMMARY_DISCOUNT)
                }
            }
            if (summary != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PanelTotal("Цена за единицу", summary?.unitPrice, EditorTags.SUMMARY_UNIT_PRICE, UNIT_PRICE_STYLE, MaterialTheme.colorScheme.onSurface)
            // A simplified calculation is one piece: it has no run, and so no total of one.
            if (summary == null || summary.total != null) {
                PanelTotal("Итого за тираж", summary?.total, EditorTags.SUMMARY_TOTAL, TOTAL_STYLE, MaterialTheme.extendedColors.accentText)
            }
            if (calcErrors.isNotEmpty()) {
                CalcErrors(calcErrors, calcErrorsHint, modifier = Modifier.testTag(EditorTags.ERRORS))
            }
            if (canCalculate) {
                Text("Все обязательные поля заполнены", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                WarningPanel("Заполните выделенные поля: $missingCount", Modifier.testTag(EditorTags.MISSING))
            }
            PrimaryButton(
                onClick = onCalculate,
                enabled = canCalculate,
                modifier = Modifier.fillMaxWidth().height(CALCULATE_HEIGHT).testTag(EditorTags.CALCULATE),
            ) {
                Text("Рассчитать", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

private val UNIT_PRICE_STYLE: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)

private val TOTAL_STYLE: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 26.sp)

/** One line of the sum: what it is and its amount, the amount in digits of equal width at the right edge. [tag] is the amount's. */
@Composable
private fun PanelLine(label: String, amount: String, tag: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            amount,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            textAlign = TextAlign.End,
            softWrap = false,
        )
    }
}

/** A sum that stands out, its label small and its figure large on one baseline; a dash while there is no price. */
@Composable
private fun PanelTotal(
    label: String,
    amount: String?,
    tag: String,
    style: TextStyle,
    color: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(label, modifier = Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            amount ?: "—",
            modifier = Modifier.alignByBaseline().then(if (amount == null) Modifier else Modifier.testTag(tag)),
            style = style.tabular(),
            color = color,
            textAlign = TextAlign.End,
            softWrap = false,
        )
    }
}
