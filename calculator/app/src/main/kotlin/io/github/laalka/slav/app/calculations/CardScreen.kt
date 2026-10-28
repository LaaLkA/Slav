package io.github.laalka.slav.app.calculations

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.editor.CalcErrors
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.ChoiceField
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.SectionCard

private val SUMMARY_WIDTH = 400.dp
private val STATUS_WIDTH = 200.dp

/**
 * The calculation card (spec 8.2, 8.3): the breakdown on the left, the price, run size and actions
 * on the right. [onOpenLink] opens the calculation this one was made from, or a newer version of it.
 */
@Composable
fun CardScreen(model: CardModel, onBack: () -> Unit, onEdit: () -> Unit, onOpenLink: (CardLink) -> Unit) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header(state, model, onBack, onOpenLink)
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = Spacing.medium)) {
                    if (state.isPriced) BreakdownCard(state) else DraftNotice()
                    if (state.revisions.isNotEmpty()) Revisions(state.revisions, onOpenLink)
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Column(
                modifier = Modifier.width(SUMMARY_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Summary(state, model)
                Actions(state, model, onEdit)
                if (state.recalcErrors.isNotEmpty()) {
                    CalcErrors(
                        state.recalcErrors,
                        hint = "Заполните эти цены в «Настройках» и нажмите «Пересчитать по текущим ценам» ещё раз. Расчёт не изменён.",
                        title = "Пересчёт не выполнен — в настройках не хватает данных",
                        modifier = Modifier.testTag(CalculationTags.CARD_RECALC_ERRORS),
                    )
                    TextButton(onClick = model::dismissRecalcErrors) { Text("Скрыть") }
                }
            }
        }
    }

    state.recalc?.let { RecalcDialog(it, onConfirm = model::confirmRecalc, onCancel = model::cancelRecalc) }
    state.quantityDialog?.let {
        QuantityDialogView(it, model::onQuantityChange, onConfirm = model::confirmQuantityChange, onCancel = model::cancelQuantityChange)
    }
    state.deleteQuestion?.let {
        DeleteDialog(it, model::onDeletePasswordChange, onConfirm = model::confirmDelete, onCancel = model::cancelDelete)
    }
}

@Composable
private fun Header(state: CardState, model: CardModel, onBack: () -> Unit, onOpenLink: (CardLink) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.small, end = Spacing.large, top = Spacing.medium, bottom = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(CalculationTags.CARD_BACK)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К расчётам")
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.name.ifBlank { "Без названия" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(CalculationTags.CARD_NAME),
            )
            state.snapshotDate?.let {
                Text(
                    "Цены — снимок от $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(CalculationTags.CARD_SNAPSHOT_DATE),
                )
            }
            state.source?.let { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Создан на основе:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onOpenLink(source) }, modifier = Modifier.testTag(CalculationTags.CARD_SOURCE)) { Text(source.title) }
                }
            }
        }
        // «Черновик» and «Рассчитан» are set by the program and shown as they are; the rest are chosen here (spec 8.1).
        ChoiceField(
            label = "Статус",
            selected = state.status.title,
            options = state.statusOptions.map { it.title },
            onSelect = { model.setStatus(state.statusOptions[it]) },
            modifier = Modifier.width(STATUS_WIDTH),
            testTag = CalculationTags.CARD_STATUS,
        )
    }
}

@Composable
private fun DraftNotice() {
    SectionCard("Черновик", subtitle = "У черновика нет цены. Откройте его в редакторе и нажмите «Рассчитать».") {}
}

/** Spec 8.2: every line of every part, the product lines and the unit price they add up to. */
@Composable
private fun BreakdownCard(state: CardState) {
    SectionCard("Разбивка") {
        state.parts.forEachIndexed { index, part ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.small))
            Text(part.title, style = MaterialTheme.typography.titleSmall)
            Text(part.details, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            part.lines.forEach { LineRow(it) }
            AmountLine("Цена детали", part.cost, strong = true)
        }
        if (state.productLines.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = Spacing.small))
            Text("Операции изделия", style = MaterialTheme.typography.titleSmall)
            state.productLines.forEach { LineRow(it) }
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.small))
        AmountLine("Цена изделия", state.unitPrice.orEmpty(), strong = true)
    }
}

@Composable
private fun LineRow(line: CardLine) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Column(Modifier.weight(1f)) {
            Text(line.title, style = MaterialTheme.typography.bodyMedium)
            line.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(line.cost, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End, softWrap = false)
    }
}

@Composable
private fun AmountLine(label: String, value: String, strong: Boolean = false) {
    val style = if (strong) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = style)
        Text(value, style = style.tabular(), textAlign = TextAlign.End, softWrap = false)
    }
}

@Composable
private fun Summary(state: CardState, model: CardModel) {
    SectionCard("Стоимость") {
        SummaryRow("Цена изделия", state.unitPrice ?: "—", CalculationTags.CARD_UNIT_PRICE, emphasized = true)
        // The run size of a calculated record is not edited in place: a new run size is a new version.
        SummaryRow("Тираж, шт", state.quantity, CalculationTags.CARD_QUANTITY)
        if (state.isPriced) {
            OutlinedButton(onClick = model::requestQuantityChange, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_QUANTITY_CHANGE)) {
                Text("Изменить тираж")
            }
        }
        SummaryRow("Итог", state.total ?: "—", CalculationTags.CARD_TOTAL, emphasized = true)
        if (state.isPriced) {
            HorizontalDivider(Modifier.padding(vertical = Spacing.small))
            SummaryRow("Масса изделия", state.unitMass.orEmpty(), CalculationTags.CARD_UNIT_MASS)
            SummaryRow("Масса тиража", state.runMass.orEmpty(), CalculationTags.CARD_RUN_MASS)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String, emphasized: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        val style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
        Text(value, modifier = Modifier.testTag(tag), style = style.tabular(), textAlign = TextAlign.End, softWrap = false)
    }
}

@Composable
private fun Actions(state: CardState, model: CardModel, onEdit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        // A calculated record is not changed itself: «Изменить» opens a copy of it with the same prices.
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_EDIT)) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Text("Изменить", modifier = Modifier.padding(start = Spacing.small))
        }
        if (state.isPriced) {
            OutlinedButton(onClick = model::prepareRecalc, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_RECALC)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("Пересчитать по текущим ценам", modifier = Modifier.padding(start = Spacing.small))
            }
        }
        OutlinedButton(onClick = model::requestDelete, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_DELETE)) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text("Удалить", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = Spacing.small))
        }
    }
}

/** «Новые версии»: what was made from this calculation, each a link to its card or, for a draft, its editor. */
@Composable
private fun Revisions(revisions: List<CardLink>, onOpenLink: (CardLink) -> Unit) {
    SectionCard("Новые версии", modifier = Modifier.padding(top = Spacing.medium)) {
        revisions.forEachIndexed { index, revision ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextButton(onClick = { onOpenLink(revision) }, modifier = Modifier.testTag(CalculationTags.cardRevision(index))) { Text(revision.title) }
                revision.details?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** «Изменить тираж»: the new run size makes a new version with the same prices and result. */
@Composable
private fun QuantityDialogView(dialog: QuantityDialog, onChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Изменить тираж") },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    "Расчёт не изменится: будет создана его новая версия с тем же снимком цен и тем же результатом. Цена изделия останется прежней.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InputField(
                    value = dialog.text,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_QUANTITY_FIELD),
                    label = "Тираж, шт",
                    error = dialog.error,
                    numeric = true,
                    onEnter = onConfirm,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.CARD_QUANTITY_CONFIRM)) { Text("Создать новую версию") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.CARD_QUANTITY_CANCEL)) { Text("Отмена") }
        },
    )
}

/** Spec 8.3: the old and the new unit price side by side; nothing changes until confirmed. */
@Composable
private fun RecalcDialog(preview: RecalcPreview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Пересчитать по текущим ценам?") },
        text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                AmountLine("Цена изделия по снимку от ${preview.snapshotDate}", preview.oldUnitPrice)
                AmountLine("Цена изделия по текущим ценам", preview.newUnitPrice, strong = true)
                AmountLine("Итог за тираж", "${preview.oldTotal} → ${preview.newTotal}")
                Text(
                    (if (preview.unchanged) "Цена не изменится. " else "") +
                        "Будет создана новая версия расчёта по текущим ценам из настроек, этот расчёт останется как есть.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.CARD_RECALC_CONFIRM)) { Text("Создать новую версию") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.CARD_RECALC_CANCEL)) { Text("Отмена") }
        },
    )
}
