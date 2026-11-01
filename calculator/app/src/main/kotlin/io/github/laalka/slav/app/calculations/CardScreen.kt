package io.github.laalka.slav.app.calculations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.attachments.AttachmentsBlock
import io.github.laalka.slav.app.editor.CalcErrors
import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.extendedColors
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.AccentTextButton
import io.github.laalka.slav.app.ui.BarTitleStyle
import io.github.laalka.slav.app.ui.ButtonIcon
import io.github.laalka.slav.app.ui.CardSurface
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.LineIcons
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.app.ui.PasswordDialog
import io.github.laalka.slav.app.ui.PasswordDialogTags
import io.github.laalka.slav.app.ui.PrimaryButton
import io.github.laalka.slav.app.ui.SecondaryButton
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.SlavDialog
import io.github.laalka.slav.app.ui.StatusChip
import io.github.laalka.slav.app.ui.TitleBar

private val SUMMARY_WIDTH = 380.dp

// The room at the sides of a line of the breakdown, which is a row of a table inside the card.
private val LINE_INSET = 20.dp

private val EDIT_TAGS = PasswordDialogTags(CalculationTags.EDIT_PASSWORD, CalculationTags.EDIT_CONFIRM, CalculationTags.EDIT_CANCEL)

/**
 * The calculation card (spec 8.2, 8.3): the title bar with the status, the exports and «Изменить», the breakdown on
 * the left, the price, run size and actions on the right. [onOpenLink] opens the calculation this one was made from,
 * or a newer version of it.
 */
@Composable
fun CardScreen(model: CardModel, filePicker: FilePicker, onBack: () -> Unit, onOpenLink: (CardLink) -> Unit) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header(state, model, filePicker, onBack, onOpenLink)
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.pageTight, end = Spacing.pageTight, top = Spacing.large, bottom = Spacing.large),
            horizontalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
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

/**
 * The title bar: the name with its status on one line, the counterparty and the date of the prices under it, then
 * the exports (PDF and Excel, which a draft has no price for) and «Изменить» at the right end.
 */
@Composable
private fun Header(state: CardState, model: CardModel, filePicker: FilePicker, onBack: () -> Unit, onOpenLink: (CardLink) -> Unit) {
    TitleBar(
        onBack = onBack,
        backTag = CalculationTags.CARD_BACK,
        actions = {
            ExportFormat.entries.forEach { format ->
                SecondaryButton(
                    onClick = {
                        filePicker.chooseSaveTarget(format.dialogTitle, model.suggestedFileName(format), format.extension)?.let { model.export(format, it) }
                    },
                    enabled = state.isPriced,
                    modifier = Modifier.testTag(CalculationTags.cardExport(format)),
                ) { Text(if (format == ExportFormat.PDF) "PDF" else "Excel") }
            }
            // A draft is edited as it is; a calculated record is changed in place only under the administrator password.
            PrimaryButton(onClick = model::requestEdit, modifier = Modifier.testTag(CalculationTags.CARD_EDIT)) {
                ButtonIcon(LineIcons.Pencil)
                Text("Изменить", modifier = Modifier.padding(start = Spacing.small))
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                state.name.ifBlank { "Без названия" },
                style = BarTitleStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag(CalculationTags.CARD_NAME),
            )
            StatusControl(state, model)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            if (state.counterparty.isNotBlank()) {
                Text(
                    "Контрагент: ${state.counterparty}",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag(CalculationTags.CARD_COUNTERPARTY),
                )
            }
            state.snapshotDate?.let {
                if (state.counterparty.isNotBlank()) Text(" · ", style = MaterialTheme.typography.bodySmall, color = secondary)
                Text(
                    "Цены — снимок от $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    modifier = Modifier.testTag(CalculationTags.CARD_SNAPSHOT_DATE),
                )
            }
        }
        state.source?.let { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Создан на основе:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AccentTextButton(onClick = { onOpenLink(source) }, modifier = Modifier.testTag(CalculationTags.CARD_SOURCE), compact = true) { Text(source.title) }
            }
        }
    }
}

/**
 * The status: the chip. «Черновик» and «Рассчитан» are set by the program and shown as they are; the rest are
 * chosen here (spec 8.1), so on a priced calculation the chip is the menu that offers them.
 */
@Composable
private fun StatusControl(state: CardState, model: CardModel) {
    if (state.statusOptions.isEmpty()) {
        StatusChip(state.status, Modifier.testTag(CalculationTags.CARD_STATUS))
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        StatusChip(
            state.status,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.DropdownList) { expanded = true }.testTag(CalculationTags.CARD_STATUS),
            trailing = { Icon(LineIcons.ChevronDown, contentDescription = "Сменить статус", modifier = Modifier.size(14.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.statusOptions.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.title) },
                    onClick = {
                        expanded = false
                        model.setStatus(status)
                    },
                    modifier = Modifier.testTag("${CalculationTags.CARD_STATUS}/${status.title}"),
                )
            }
        }
    }
}

@Composable
private fun DraftNotice() {
    SectionCard("Черновик", subtitle = "У черновика нет цены. Откройте его в редакторе и нажмите «Рассчитать».") {}
}

/** A card the way the cost and the breakdown are drawn: [CardSurface] without its padding, so a row of a table runs edge to edge. */
@Composable
private fun TableCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.clip(MaterialTheme.shapes.large), content = content)
    }
}

/**
 * Spec 8.2: every line of every part, the product lines and the unit price they add up to. A
 * simplified calculation has no parts: its material and cut are the product lines.
 */
@Composable
private fun BreakdownCard(state: CardState) {
    TableCard {
        Text(
            "Разбивка",
            modifier = Modifier.fillMaxWidth().padding(horizontal = LINE_INSET, vertical = Spacing.medium),
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        state.parts.forEachIndexed { index, part ->
            SectionCaption(part.title, part.details, first = index == 0)
            part.lines.forEach { LineRow(it) }
            ShadedRow("Цена детали", part.cost)
        }
        if (state.productLines.isNotEmpty()) {
            if (state.parts.isNotEmpty()) SectionCaption("Операции изделия", null, first = false)
            state.productLines.forEach { LineRow(it) }
        }
        HorizontalDivider(Modifier.padding(top = Spacing.small), color = MaterialTheme.colorScheme.outlineVariant)
        // The lines add up to the sum before the discount; without one that sum is the price.
        val discount = state.discount
        if (discount != null) {
            TotalRow("Сумма без скидки", discount.subtotal)
        } else {
            TotalRow("Цена за единицу", state.unitPrice.orEmpty())
        }
    }
}

/** The title of a block of lines: 12 sp semibold in capitals, opened by 0.6 sp, the [detail] of it at the right end. */
@Composable
private fun SectionCaption(text: String, detail: String?, first: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LINE_INSET, end = LINE_INSET, top = if (first) Spacing.small else Spacing.medium, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun LineRow(line: CardLine) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LINE_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.title, style = MaterialTheme.typography.bodyMedium)
            line.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(line.cost, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End, softWrap = false)
    }
}

/** The subtotal of a part: a line of its own weight on the shade of the track. */
@Composable
private fun ShadedRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SHADE_ALPHA)).padding(horizontal = LINE_INSET, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.titleSmall.tabular(), textAlign = TextAlign.End, softWrap = false)
    }
}

private const val SHADE_ALPHA = 0.6f

/** The closing line of the breakdown: bold, with room above it. */
@Composable
private fun TotalRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LINE_INSET, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold).tabular(), textAlign = TextAlign.End, softWrap = false)
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

/** «Стоимость»: the sums, the run size with the button that changes it, the run total and the masses. */
@Composable
private fun Summary(state: CardState, model: CardModel) {
    CardSurface {
        Text("Стоимость", style = MaterialTheme.typography.titleMedium)
        state.discount?.let { discount ->
            SummaryRow("Сумма без скидки", discount.subtotal, CalculationTags.CARD_SUBTOTAL)
            SummaryRow(discount.label, discount.amount, CalculationTags.CARD_DISCOUNT)
        }
        SummaryRow("Цена за единицу", state.unitPrice ?: "—", CalculationTags.CARD_UNIT_PRICE, emphasis = Emphasis.Price)
        // A simplified calculation is one piece: it has no run size, so nothing here shows one.
        state.quantity?.let { quantity ->
            // The run size of a calculated record is not edited in place: a new run size is a new version.
            SummaryRow(
                "Тираж, шт",
                quantity,
                CalculationTags.CARD_QUANTITY,
                emphasis = Emphasis.Quiet,
                after = {
                    if (state.isPriced) {
                        SecondaryButton(
                            onClick = model::requestQuantityChange,
                            modifier = Modifier.height(32.dp).testTag(CalculationTags.CARD_QUANTITY_CHANGE),
                        ) { Text("Изменить", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)) }
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SummaryRow("Итого за тираж", state.total ?: "—", CalculationTags.CARD_TOTAL, emphasis = Emphasis.Total)
        }
        if (state.isPriced) {
            if (state.quantity == null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SummaryRow("Масса изделия", state.unitMass.orEmpty(), CalculationTags.CARD_UNIT_MASS, emphasis = Emphasis.Small)
            state.runMass?.let { SummaryRow("Масса тиража", it, CalculationTags.CARD_RUN_MASS, emphasis = Emphasis.Small) }
        }
    }
}

/** How loud a line of the cost is: the sums of the price are plain, the price and the total large, the run size and the masses quiet. */
private enum class Emphasis { Plain, Price, Total, Quiet, Small }

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    tag: String,
    emphasis: Emphasis = Emphasis.Plain,
    after: (@Composable () -> Unit)? = null,
) {
    val typography = MaterialTheme.typography
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = if (emphasis == Emphasis.Small) typography.bodySmall else typography.bodyMedium
    val labelColor = if (emphasis == Emphasis.Plain) MaterialTheme.colorScheme.onSurface else secondary
    val valueStyle = when (emphasis) {
        Emphasis.Price -> typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        Emphasis.Total -> typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        Emphasis.Small -> typography.bodySmall
        else -> typography.bodyMedium
    }
    val valueColor = when (emphasis) {
        Emphasis.Total -> MaterialTheme.extendedColors.accentText
        Emphasis.Small -> secondary
        else -> Color.Unspecified
    }
    if (after == null) {
        // A large value is bigger than its label: both stand on one baseline, not with their middles level.
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(label, modifier = Modifier.weight(1f).alignByBaseline(), style = labelStyle, color = labelColor)
            Text(value, modifier = Modifier.alignByBaseline().testTag(tag), style = valueStyle.tabular(), color = valueColor, textAlign = TextAlign.End, softWrap = false)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = labelStyle, color = labelColor)
            Text(value, modifier = Modifier.testTag(tag), style = valueStyle.tabular(), color = valueColor, textAlign = TextAlign.End, softWrap = false)
            after()
        }
    }
}

/** The actions of the card as a list: an icon and a label to a row, «Удалить» apart under a line, in the error colour. */
@Composable
private fun Actions(state: CardState, model: CardModel, filePicker: FilePicker) {
    TableCard {
        Column(Modifier.padding(8.dp)) {
            if (state.isPriced) {
                ActionRow(LineIcons.Copy, "Сделать копию и изменить", model::copyAndEdit, CalculationTags.CARD_COPY_EDIT)
                ActionRow(LineIcons.Refresh, "Пересчитать по текущим ценам", model::prepareRecalc, CalculationTags.CARD_RECALC)
            }
            // The file is chosen first; the password is asked after it, and the calculation is deleted once the file is written and checked.
            ActionRow(
                LineIcons.Archive,
                "В архив",
                {
                    filePicker.chooseSaveTarget(ARCHIVE_DIALOG_TITLE, model.suggestedArchiveName(), ARCHIVE_EXTENSION)?.let(model::requestArchive)
                },
                CalculationTags.CARD_ARCHIVE,
                iconTag = CalculationTags.CARD_ARCHIVE_ICON,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val error = MaterialTheme.extendedColors.errorText
            ActionRow(LineIcons.Trash, "Удалить", model::requestDelete, CalculationTags.CARD_DELETE, color = error, iconColor = error)
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
    iconTag: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.gap).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp).then(if (iconTag == null) Modifier else Modifier.testTag(iconTag)))
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/** «Новые версии»: what was made from this calculation, each a link to its card or, for a draft, its editor. */
@Composable
private fun Revisions(revisions: List<CardLink>, onOpenLink: (CardLink) -> Unit) {
    SectionCard("Новые версии") {
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
    SlavDialog(
        title = "Изменить тираж",
        onDismissRequest = onCancel,
        confirmButton = {
            PrimaryButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.CARD_QUANTITY_CONFIRM)) { Text("Создать новую версию") }
        },
        dismissButton = {
            SecondaryButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.CARD_QUANTITY_CANCEL)) { Text("Отмена") }
        },
    ) {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            Text("Расчёт не изменится: будет создана его новая версия с тем же снимком цен и тем же результатом. Цена изделия останется прежней.")
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
    }
}

/** Spec 8.3: the old and the new unit price side by side; nothing changes until confirmed. */
@Composable
private fun RecalcDialog(preview: RecalcPreview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SlavDialog(
        title = "Пересчитать по текущим ценам?",
        onDismissRequest = onCancel,
        confirmButton = {
            PrimaryButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.CARD_RECALC_CONFIRM)) { Text("Создать новую версию") }
        },
        dismissButton = {
            SecondaryButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.CARD_RECALC_CANCEL)) { Text("Отмена") }
        },
    ) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            AmountLine("Цена за единицу по снимку от ${preview.snapshotDate}", preview.oldUnitPrice)
            AmountLine("Цена за единицу по текущим ценам", preview.newUnitPrice, strong = true)
            if (preview.oldTotal != null && preview.newTotal != null) {
                AmountLine("Итого за тираж", "${preview.oldTotal} → ${preview.newTotal}")
            }
            Text(
                (if (preview.unchanged) "Цена не изменится. " else "") +
                    "Будет создана новая версия расчёта по текущим ценам из настроек, этот расчёт останется как есть.",
                modifier = Modifier.padding(top = Spacing.small),
            )
        }
    }
}
