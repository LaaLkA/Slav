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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.attachments.AttachmentsBlock
import io.github.laalka.slav.app.editor.CalcErrors
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.AccentTextButton
import io.github.laalka.slav.app.ui.ArchiveIcon
import io.github.laalka.slav.app.ui.ButtonIcon
import io.github.laalka.slav.app.ui.ChoiceField
import io.github.laalka.slav.app.ui.DestructiveButton
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.app.ui.PasswordDialog
import io.github.laalka.slav.app.ui.PasswordDialogTags
import io.github.laalka.slav.app.ui.SecondaryButton
import io.github.laalka.slav.app.ui.SectionCard

private val SUMMARY_WIDTH = 400.dp
private val STATUS_WIDTH = 200.dp

private val EDIT_TAGS = PasswordDialogTags(CalculationTags.EDIT_PASSWORD, CalculationTags.EDIT_CONFIRM, CalculationTags.EDIT_CANCEL)

/**
 * The calculation card (spec 8.2, 8.3): the breakdown on the left, the price, run size and actions
 * on the right. [onOpenLink] opens the calculation this one was made from, or a newer version of it.
 */
@Composable
fun CardScreen(model: CardModel, filePicker: FilePicker, onBack: () -> Unit, onOpenLink: (CardLink) -> Unit) {
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
                state.message?.let { message ->
                    Box(Modifier.testTag(CalculationTags.CARD_MESSAGE)) { NoticeCard(message.text, message.isError, onDismiss = model::dismissMessage) }
                }
                Summary(state, model)
                Actions(state, model, filePicker)
                AttachmentsBlock(model.attachments, filePicker)
                if (state.recalcErrors.isNotEmpty()) {
                    CalcErrors(
                        state.recalcErrors,
                        hint = "Заполните эти цены в «Настройках» и нажмите «Пересчитать по текущим ценам» ещё раз. Расчёт не изменён.",
                        title = "Пересчёт не выполнен — в настройках не хватает данных",
                        modifier = Modifier.testTag(CalculationTags.CARD_RECALC_ERRORS),
                    )
                    AccentTextButton(onClick = model::dismissRecalcErrors) { Text("Скрыть") }
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
    state.editQuestion?.let { EditDialog(it, model::onEditPasswordChange, onConfirm = model::confirmEdit, onCancel = model::cancelEdit) }
    state.archiveQuestion?.let { ArchiveDialog(it, model::onArchivePasswordChange, onConfirm = model::confirmArchive, onCancel = model::cancelArchive) }
}

/** «Изменить расчёт?»: editing a calculated record in place needs the administrator password; the error of a wrong one is shown under the field. */
@Composable
private fun EditDialog(question: EditQuestion, onPasswordChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    PasswordDialog(
        title = "Изменить расчёт?",
        message = "Расчёт «${question.name}» будет изменён на месте, без создания нового: после «Рассчитать» прежние данные пропадут. " +
            "Чтобы сохранить его как есть, отмените и выберите «Сделать копию и изменить». Нужен пароль администратора.",
        confirmLabel = "Изменить",
        error = question.error,
        tags = EDIT_TAGS,
        onPasswordChange = onPasswordChange,
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
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
            if (state.counterparty.isNotBlank()) {
                Text(
                    "Контрагент: ${state.counterparty}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(CalculationTags.CARD_COUNTERPARTY),
                )
            }
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
                    AccentTextButton(onClick = { onOpenLink(source) }, modifier = Modifier.testTag(CalculationTags.CARD_SOURCE)) { Text(source.title) }
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

/**
 * Spec 8.2: every line of every part, the product lines and the unit price they add up to. A
 * simplified calculation has no parts: its material and cut are the product lines.
 */
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
            if (state.parts.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.small))
                Text("Операции изделия", style = MaterialTheme.typography.titleSmall)
            }
            state.productLines.forEach { LineRow(it) }
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.small))
        // The lines add up to the sum before the discount; without one that sum is the price.
        val discount = state.discount
        if (discount != null) {
            AmountLine("Сумма без скидки", discount.subtotal, strong = true)
        } else {
            AmountLine("Цена за единицу", state.unitPrice.orEmpty(), strong = true)
        }
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
        state.discount?.let { discount ->
            SummaryRow("Сумма без скидки", discount.subtotal, CalculationTags.CARD_SUBTOTAL)
            SummaryRow(discount.label, discount.amount, CalculationTags.CARD_DISCOUNT)
        }
        SummaryRow("Цена за единицу", state.unitPrice ?: "—", CalculationTags.CARD_UNIT_PRICE, emphasized = true)
        // A simplified calculation is one piece: it has no run size, so nothing here shows one.
        state.quantity?.let { quantity ->
            // The run size of a calculated record is not edited in place: a new run size is a new version.
            SummaryRow("Тираж, шт", quantity, CalculationTags.CARD_QUANTITY)
            if (state.isPriced) {
                SecondaryButton(onClick = model::requestQuantityChange, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_QUANTITY_CHANGE)) {
                    Text("Изменить тираж")
                }
            }
            SummaryRow("Итого за тираж", state.total ?: "—", CalculationTags.CARD_TOTAL, emphasized = true)
        }
        if (state.isPriced) {
            HorizontalDivider(Modifier.padding(vertical = Spacing.small))
            SummaryRow("Масса изделия", state.unitMass.orEmpty(), CalculationTags.CARD_UNIT_MASS)
            state.runMass?.let { SummaryRow("Масса тиража", it, CalculationTags.CARD_RUN_MASS) }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String, emphasized: Boolean = false) {
    // An emphasized value is bigger than its label: both stand on one baseline, not with their middles level.
    Row {
        Text(label, modifier = Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodyLarge)
        val style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
        Text(value, modifier = Modifier.alignByBaseline().testTag(tag), style = style.tabular(), textAlign = TextAlign.End, softWrap = false)
    }
}

@Composable
private fun Actions(state: CardState, model: CardModel, filePicker: FilePicker) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        // A draft is edited as it is; a calculated record is changed in place only under the administrator password.
        SecondaryButton(onClick = model::requestEdit, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_EDIT)) {
            ButtonIcon(Icons.Filled.Edit)
            Text("Изменить", modifier = Modifier.padding(start = Spacing.small))
        }
        if (state.isPriced) {
            SecondaryButton(onClick = model::copyAndEdit, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_COPY_EDIT)) {
                ButtonIcon(Icons.Filled.Add)
                Text("Сделать копию и изменить", modifier = Modifier.padding(start = Spacing.small))
            }
            SecondaryButton(onClick = model::prepareRecalc, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_RECALC)) {
                ButtonIcon(Icons.Filled.Refresh)
                Text("Пересчитать по текущим ценам", modifier = Modifier.padding(start = Spacing.small))
            }
        }
        // The file is chosen first; the password is asked after it, and the calculation is deleted once the file is written and checked.
        SecondaryButton(
            onClick = {
                filePicker.chooseSaveTarget(ARCHIVE_DIALOG_TITLE, model.suggestedArchiveName(), ARCHIVE_EXTENSION)?.let(model::requestArchive)
            },
            modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_ARCHIVE),
        ) {
            ButtonIcon(ArchiveIcon, Modifier.testTag(CalculationTags.CARD_ARCHIVE_ICON))
            Text("В архив", modifier = Modifier.padding(start = Spacing.small))
        }
        DestructiveButton(onClick = model::requestDelete, modifier = Modifier.fillMaxWidth().testTag(CalculationTags.CARD_DELETE)) {
            ButtonIcon(Icons.Filled.Delete)
            Text("Удалить", modifier = Modifier.padding(start = Spacing.small))
        }
    }
}

/** «Новые версии»: what was made from this calculation, each a link to its card or, for a draft, its editor. */
@Composable
private fun Revisions(revisions: List<CardLink>, onOpenLink: (CardLink) -> Unit) {
    SectionCard("Новые версии", modifier = Modifier.padding(top = Spacing.medium)) {
        revisions.forEachIndexed { index, revision ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                AccentTextButton(onClick = { onOpenLink(revision) }, modifier = Modifier.testTag(CalculationTags.cardRevision(index))) { Text(revision.title) }
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
            AccentTextButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.CARD_QUANTITY_CONFIRM)) { Text("Создать новую версию") }
        },
        dismissButton = {
            AccentTextButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.CARD_QUANTITY_CANCEL)) { Text("Отмена") }
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
                AmountLine("Цена за единицу по снимку от ${preview.snapshotDate}", preview.oldUnitPrice)
                AmountLine("Цена за единицу по текущим ценам", preview.newUnitPrice, strong = true)
                if (preview.oldTotal != null && preview.newTotal != null) {
                    AmountLine("Итого за тираж", "${preview.oldTotal} → ${preview.newTotal}")
                }
                Text(
                    (if (preview.unchanged) "Цена не изменится. " else "") +
                        "Будет создана новая версия расчёта по текущим ценам из настроек, этот расчёт останется как есть.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            AccentTextButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.CARD_RECALC_CONFIRM)) { Text("Создать новую версию") }
        },
        dismissButton = {
            AccentTextButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.CARD_RECALC_CANCEL)) { Text("Отмена") }
        },
    )
}
