package io.github.laalka.slav.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.attachments.AttachmentsBlock
import io.github.laalka.slav.app.calculations.PriceSummary
import io.github.laalka.slav.app.library.AddFromLibraryRow
import io.github.laalka.slav.app.library.EditorLibraryDialogs
import io.github.laalka.slav.app.library.LibraryTags
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.AccentTextButton
import io.github.laalka.slav.app.ui.BarTitleStyle
import io.github.laalka.slav.app.ui.ButtonIcon
import io.github.laalka.slav.app.ui.CardSurface
import io.github.laalka.slav.app.ui.CheckRow
import io.github.laalka.slav.app.ui.ChoiceField
import io.github.laalka.slav.app.ui.DashedButton
import io.github.laalka.slav.app.ui.DestructiveButton
import io.github.laalka.slav.app.ui.FieldGrid
import io.github.laalka.slav.app.ui.FillRandomButton
import io.github.laalka.slav.app.ui.IconAction
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.LineIcons
import io.github.laalka.slav.app.ui.PrimaryButton
import io.github.laalka.slav.app.ui.SecondaryButton
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.SegmentedChoice
import io.github.laalka.slav.app.ui.SlavDialog
import io.github.laalka.slav.app.ui.TitleBar
import io.github.laalka.slav.app.ui.TouchTarget
import io.github.laalka.slav.app.ui.WarningPanel
import io.github.laalka.slav.app.ui.WarningWeight
import io.github.laalka.slav.app.ui.besideField
import io.github.laalka.slav.app.ui.fieldTextOffset
import io.github.laalka.slav.app.ui.gridSpan

internal val NUMBER_WIDTH = 168.dp
internal val CHOICE_WIDTH = 208.dp
internal val WIDE_NUMBER_WIDTH = 208.dp

// The parameters of a checked operation sit under its title, past the checkbox.
private val PARAMS_INDENT = 48.dp

// Lines a text up with the text of the outlined fields in the same row.
private val FIELD_TEXT_OFFSET = fieldTextOffset(lineHeight = 20.dp)

/** [onFillRandom] is set in developer mode: the button that fills the editor with random data. */
@Composable
fun EditorScreen(model: EditorModel, filePicker: FilePicker, onBack: () -> Unit, onFillRandom: (() -> Unit)? = null) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header(
            title = when {
                state.isNew -> "Новый расчёт"
                state.inPlace -> "Изменение расчёта"
                else -> "Редактирование расчёта"
            },
            subtitle = if (state.inPlace) "${state.priceSource}. Расчёт не изменится, пока вы не нажмёте «Рассчитать»" else state.priceSource,
            onBack = onBack,
            actions = { onFillRandom?.let { FillRandomButton(it, Modifier.testTag(EditorTags.FILL_RANDOM)) } },
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
                    quantity = state.quantity,
                    onQuantityChange = model::onQuantityChange,
                    discount = state.discount,
                    onDiscountChange = model::onDiscountChange,
                )
                AttachmentsBlock(model.attachments, filePicker)
                AddFromLibraryRow(model.library)
                state.parts.forEachIndexed { index, part -> key(part.id) { PartCard(index + 1, part, state.currency, model) } }
                state.partsError?.let { WarningPanel(it) }
                DashedButton(
                    onClick = model::addPart,
                    modifier = Modifier.fillMaxWidth().height(ADD_PART_HEIGHT).testTag(EditorTags.ADD_PART),
                ) {
                    ButtonIcon(LineIcons.Plus)
                    Text("Добавить деталь", modifier = Modifier.padding(start = Spacing.small))
                }
                SectionCard("Операции изделия", subtitle = "Сборка из деталей: сварка сборки и покраска всего изделия") {
                    state.productOperations.forEach { op -> key(op.ref) { OperationBlock(op, state.currency, model) } }
                }
            }
        }
    }
    EditorLibraryDialogs(model.library)
}

private val ADD_PART_HEIGHT = 48.dp

/**
 * «Отменить изменения?»: a calculated record edited in place is rewritten only by «Рассчитать», so leaving
 * the editor with changes loses them. The buttons are tagged [EditorTags.LEAVE_CONFIRM] and [EditorTags.LEAVE_STAY].
 */
@Composable
fun DiscardEditsDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    SlavDialog(
        title = "Отменить изменения?",
        onDismissRequest = onKeepEditing,
        confirmButton = {
            DestructiveButton(onClick = onDiscard, modifier = Modifier.testTag(EditorTags.LEAVE_CONFIRM)) { Text("Отменить изменения") }
        },
        dismissButton = {
            SecondaryButton(onClick = onKeepEditing, modifier = Modifier.testTag(EditorTags.LEAVE_STAY)) { Text("Продолжить редактирование") }
        },
    ) {
        Text("Расчёт останется таким, каким он был: всё, что вы изменили, будет потеряно. Чертежи, прикреплённые при редактировании, остаются.")
    }
}

/**
 * «Изделие»: the name, the counterparty, the run size and the optional discount of a calculation, in one row while the
 * card is wide enough, shared by both kinds of editor. A simplified calculation is one piece, so its [quantity] is null
 * and it has no field for it. The discount is one optional percent taken off the price, above 0 and below 100.
 */
@Composable
internal fun ProductCard(
    name: FieldView,
    onNameChange: (String) -> Unit,
    counterparty: FieldView,
    onCounterpartyChange: (String) -> Unit,
    quantity: FieldView?,
    onQuantityChange: (String) -> Unit,
    discount: FieldView,
    onDiscountChange: (String) -> Unit,
) {
    SectionCard("Изделие") {
        FieldGrid(minCell = PRODUCT_CELL) {
            TextInput(name, "Название расчёта", onNameChange, Modifier.gridSpan(2))
            TextInput(counterparty, "Контрагент", onCounterpartyChange, Modifier.gridSpan(2))
            if (quantity != null) NumberInput(quantity, "Тираж, шт", onQuantityChange, Modifier)
            NumberInput(discount, "Скидка, %", onDiscountChange, Modifier)
        }
    }
}

// The cell of the product card: a number fits in one, a name takes two.
private val PRODUCT_CELL = 100.dp

/**
 * The title bar of an editor with the line saying which prices it uses; the back button is tagged [EditorTags.BACK].
 * [actions] stand at the right end of the bar.
 */
@Composable
internal fun Header(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TitleBar(onBack = onBack, backTag = EditorTags.BACK, actions = actions) {
        Text(title, style = BarTitleStyle)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One part: a numbered title with its actions, then the material, the shape, the grid of fields, the mass and the operations. */
@Composable
private fun PartCard(number: Int, part: PartView, currency: String, model: EditorModel) {
    CardSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap), verticalAlignment = Alignment.CenterVertically) {
            NumberBadge(number)
            Text(part.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AccentTextButton(onClick = { model.copyPart(part.id) }, modifier = Modifier.testTag("${part.tag}/copy"), compact = true) { Text("Копировать") }
            AccentTextButton(
                onClick = { model.library.requestSave(part.id) },
                modifier = Modifier.testTag(LibraryTags.toLibrary(part.tag)),
                compact = true,
            ) { Text("В библиотеку") }
            IconAction(LineIcons.Trash, "Удалить деталь", { model.deletePart(part.id) }, Modifier.testTag("${part.tag}/delete"))
        }
        TextInput(part.name, "Название детали", { model.onPartNameChange(part.id, it) }, Modifier.fillMaxWidth())

        Caption("Материал")
        SegmentedChoice(
            options = SortamentKind.entries.map { it.title },
            selectedIndex = SortamentKind.entries.indexOf(part.kind),
            onSelect = { model.onKindChange(part.id, SortamentKind.entries[it]) },
            testTagOf = { "${part.tag}/kind/${SortamentKind.entries[it].name}" },
        )
        part.shape?.let { shape ->
            Caption("Форма")
            SegmentedChoice(
                options = ShapeKind.entries.map { it.title },
                selectedIndex = ShapeKind.entries.indexOf(shape),
                onSelect = { model.onShapeChange(part.id, ShapeKind.entries[it]) },
                testTagOf = { EditorTags.shape(part.tag, ShapeKind.entries[it]) },
            )
        }
        FieldGrid(minCell = PART_CELL) {
            Choice(part.grade, Modifier.fillMaxWidth()) { model.onGradeChange(part.id, part.grade.options[it]) }
            Choice(part.size, Modifier.fillMaxWidth()) { model.onSizeChange(part.id, part.sizeOptions[it]) }
            part.dimensions.forEach { dimension ->
                key(dimension.field) {
                    NumberInput(dimension.value, dimension.field.label, { model.onDimensionChange(part.id, dimension.field, it) }, Modifier)
                }
            }
            if (part.massOverrideShown) {
                NumberInput(part.massOverride, "Вес по чертежу, кг", { model.onMassOverrideChange(part.id, it) }, Modifier)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Bottom) {
            Text("Масса детали", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                part.mass ?: "—",
                style = MaterialTheme.typography.titleMedium.tabular(),
                modifier = Modifier.testTag("${part.tag}/mass"),
                softWrap = false,
            )
            val massSource = when {
                part.massFromDrawing -> "по чертежу"
                part.massByWeight -> "по весу"
                else -> null
            }
            if (massSource != null) {
                Text(
                    massSource,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("${part.tag}/mass-hint"),
                )
            }
        }
        val material = LineRef.MaterialOf(part.id)
        ExtraRow(part.materialExtra, currency, { model.onExtraAmountChange(material, it) }, { model.onExtraCommentChange(material, it) })

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Caption("Операции")
        part.operations.forEach { op -> key(op.ref) { OperationBlock(op, currency, model) } }
    }
}

// The cell of the grid of a part's fields: the label «Типоразмер» and a size such as «100 × 100 × 4» fit in one.
private val PART_CELL = 140.dp

/** The number of a part in its card: a 28 dp square on the track colour. */
@Composable
private fun NumberBadge(number: Int) {
    Box(
        modifier = Modifier.size(BADGE_SIZE).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(number.toString(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurface)
    }
}

private val BADGE_SIZE = 28.dp

@Composable
private fun OperationBlock(op: OperationView, currency: String, model: EditorModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        CheckRow(
            text = op.title,
            checked = op.checked,
            onCheckedChange = { model.setChecked(op.ref, it) },
            modifier = Modifier.testTag("${op.tag}/check"),
            enabled = op.unavailableHint == null,
            hint = op.unavailableHint,
        )
        val params = op.params
        if (params != null) {
            Column(Modifier.padding(start = PARAMS_INDENT), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Params(op.ref, params, model)
                op.extra?.let { extra ->
                    ExtraRow(extra, currency, { model.onExtraAmountChange(op.ref, it) }, { model.onExtraCommentChange(op.ref, it) })
                }
            }
        }
    }
}

@Composable
private fun Params(line: LineRef, params: OperationParams, model: EditorModel) {
    val part = (line as? LineRef.OfPart)?.part
    when (params) {
        is OperationParams.LaserSheet -> {
            SegmentedChoice(
                options = CutMode.entries.map { it.title },
                selectedIndex = CutMode.entries.indexOf(params.mode),
                onSelect = { if (part != null) model.onCutModeChange(part, CutMode.entries[it]) },
                testTagOf = { "${params.modeTag}/${CutMode.entries[it].name}" },
            )
            when (params.mode) {
                CutMode.BY_SHAPE -> Hint(
                    "Длина реза — все контуры формы детали; пробитий столько же, сколько контуров: кольцо — 2, остальные формы — 1. " +
                        "Для формы «По весу» длина реза — введённый периметр, пробитие одно.",
                )
                CutMode.CONTOUR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                        NumberInput(params.contourLength, "Длина реза L, мм", { if (part != null) model.onContourLengthChange(part, it) }, WIDE_NUMBER_WIDTH)
                        NumberInput(params.pierces, "Пробитий, шт", { if (part != null) model.onPiercesChange(part, it) })
                    }
                    Hint("Наружный и внутренние контуры с чертежа. Круглые отверстия — в «Лазер: отверстия».")
                }
            }
        }
        is OperationParams.TubeLaser -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
            NumberInput(params.cuts, "Резов, шт", { if (part != null) model.onCutsChange(part, it) })
            Hint(
                params.cutLength?.let { "Длина одного реза — периметр сечения, $it. Торцевой рез без пробития." }
                    ?: "Длина реза — периметр сечения; появится, когда выбран типоразмер.",
                Modifier.padding(top = FIELD_TEXT_OFFSET),
            )
        }
        is OperationParams.Holes -> {
            params.rows.forEach { row ->
                key(row.id) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                            NumberInput(row.diameter, "Диаметр d, мм", { model.onRowLengthChange(line, row.id, it) })
                            NumberInput(row.count, "Кол-во, шт", { model.onRowCountChange(line, row.id, it) })
                            if (params.bothWallsAvailable && part != null) {
                                CheckRow(
                                    "Через обе стенки",
                                    row.throughBothWalls,
                                    { model.onThroughBothWallsChange(part, row.id, it) },
                                    Modifier.besideField(TouchTarget).testTag("${row.tag}/both-walls"),
                                )
                            }
                            DeleteRowButton("${row.tag}/delete") { model.deleteRow(line, row.id) }
                        }
                        row.warning?.let { WarningText(it) }
                    }
                }
            }
            RowsFooter(params.rowsError, "Добавить отверстие", params.addTag) { model.addRow(line) }
        }
        is OperationParams.Bending -> {
            CountRows(line, params.rows, "Длина гиба, мм", "Гибов, шт", model)
            RowsFooter(params.rowsError, "Добавить гиб", params.addTag) { model.addRow(line) }
        }
        is OperationParams.AssemblyWelding -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                Choice(params.weldType, Modifier.width(CHOICE_WIDTH)) { model.onAssemblyWeldTypeChange(params.weldType.options[it]) }
                Choice(params.group, Modifier.width(CHOICE_WIDTH)) { model.onAssemblyGroupChange(params.group.options[it]) }
                Choice(params.thickness, Modifier.width(CHOICE_WIDTH)) { model.onAssemblyThicknessChange(params.thicknessOptions[it]) }
            }
            NumberInput(params.seamLength, "Длина шва, мм", model::onSeamLengthChange, WIDE_NUMBER_WIDTH)
        }
        is OperationParams.Painting -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
            NumberInput(params.areaOverride, "Своя площадь, м²", { model.onAreaOverrideChange(line, it) }, WIDE_NUMBER_WIDTH)
            Hint(
                params.autoArea?.let { "По габаритам — $it: ${params.minAreaHint}. Пустое поле — считать по габаритам." }
                    ?: "Площадь по габаритам появится, когда заполнены марки и размеры деталей: ${params.minAreaHint}.",
                Modifier.padding(top = FIELD_TEXT_OFFSET),
            )
        }
    }
}

@Composable
private fun CountRows(line: LineRef, rows: List<CountRowView>, lengthLabel: String, countLabel: String, model: EditorModel) {
    rows.forEach { row ->
        key(row.id) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                NumberInput(row.length, lengthLabel, { model.onRowLengthChange(line, row.id, it) })
                NumberInput(row.count, countLabel, { model.onRowCountChange(line, row.id, it) })
                DeleteRowButton("${row.tag}/delete") { model.deleteRow(line, row.id) }
            }
        }
    }
}

@Composable
private fun RowsFooter(error: String?, addLabel: String, addTag: String, onAdd: () -> Unit) {
    error?.let { WarningPanel(it) }
    AccentTextButton(onClick = onAdd, modifier = Modifier.testTag(addTag)) {
        ButtonIcon(Icons.Filled.Add)
        Text(addLabel, modifier = Modifier.padding(start = Spacing.small))
    }
}

@Composable
private fun DeleteRowButton(tag: String, onDelete: () -> Unit) {
    IconButton(onClick = onDelete, modifier = Modifier.besideField(TouchTarget).testTag(tag)) {
        Icon(Icons.Filled.Delete, contentDescription = "Удалить строку")
    }
}

/** «Доп. операции» of one line: the amount and its comment. */
@Composable
internal fun ExtraRow(extra: ExtraView, currency: String, onAmountChange: (String) -> Unit, onCommentChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
        NumberInput(extra.amount, "Доп. операции, $currency", onAmountChange, WIDE_NUMBER_WIDTH)
        TextInput(extra.comment, "Комментарий к доп. операциям", onCommentChange, Modifier.weight(1f))
    }
}

/**
 * The bar under an editor, shared by both kinds of calculation: why «Рассчитать» is disabled, the price of the
 * input as it stands ([summary], when there is one), the errors of the last attempt (spec 7.3) and the button
 * itself, tagged [EditorTags.CALCULATE].
 */
@Composable
internal fun CalculateBar(
    canCalculate: Boolean,
    missingCount: Int,
    summary: PriceSummary?,
    calcErrors: List<ReadableError>,
    calcErrorsHint: String?,
    onCalculate: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large, vertical = Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            if (calcErrors.isNotEmpty()) {
                CalcErrors(calcErrors, calcErrorsHint, modifier = Modifier.testTag(EditorTags.ERRORS))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    if (canCalculate) {
                        Text(
                            "Все обязательные поля заполнены",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        WarningPanel("Заполните выделенные поля: $missingCount", Modifier.testTag(EditorTags.MISSING))
                    }
                    summary?.let { PriceSummaryRow(it) }
                }
                PrimaryButton(onClick = onCalculate, enabled = canCalculate, modifier = Modifier.testTag(EditorTags.CALCULATE)) {
                    Text("Рассчитать")
                }
            }
        }
    }
}

/** The price of the input: the sum before the discount, the discount, the price of one piece and, for a full calculation, the run total. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriceSummaryRow(summary: PriceSummary) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        summary.discount?.let { discount ->
            SummaryItem("Сумма без скидки", discount.subtotal, EditorTags.SUMMARY_SUBTOTAL)
            SummaryItem(discount.label, discount.amount, EditorTags.SUMMARY_DISCOUNT)
        }
        SummaryItem("Цена за единицу", summary.unitPrice, EditorTags.SUMMARY_UNIT_PRICE, strong = true)
        summary.total?.let { SummaryItem("Итого за тираж", it, EditorTags.SUMMARY_TOTAL, strong = true) }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, tag: String, strong: Boolean = false) {
    // The label and the value are of different sizes: they stand on one baseline, not with their middles level.
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(label, modifier = Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.alignByBaseline().testTag(tag),
            style = (if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium).tabular(),
            softWrap = false,
        )
    }
}

/**
 * Spec 7.3: everything that stopped the calculation at once; the table, row and column of each
 * missing price. Also shown by the card when «Пересчитать по текущим ценам» fails (spec 8.3). Wide enough
 * it is a table of four columns; in a narrow place (the cost panel) each missing price is two lines, what is missing
 * and where to fill it, because four columns of 70 dp wrap every word.
 */
@Composable
fun CalcErrors(
    errors: List<ReadableError>,
    hint: String?,
    title: String = "Расчёт не выполнен — не хватает данных",
    modifier: Modifier = Modifier,
) {
    WarningPanel(title, modifier) {
        BoxWithConstraints {
            val table = maxWidth >= CALC_ERRORS_TABLE_WIDTH
            Column(
                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                val cells = errors.mapNotNull { it.cell }
                if (cells.isNotEmpty() && table) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                        listOf("Таблица" to 2f, "Строка" to 1f, "Столбец" to 1f, "Где заполнить" to 2f).forEach { (title, weight) ->
                            Text(title, modifier = Modifier.weight(weight), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    cells.forEach { cell ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                            Text(cell.table, modifier = Modifier.weight(2f), style = errorBody())
                            Text(cell.row, modifier = Modifier.weight(1f), style = errorBody())
                            Text(cell.column, modifier = Modifier.weight(1f), style = errorBody().tabular())
                            Text("Настройки → ${cell.tab.title}", modifier = Modifier.weight(2f), style = errorBody())
                        }
                    }
                } else {
                    cells.forEach { cell ->
                        Column {
                            Text("${cell.table}: ${cell.row}, ${cell.column}", style = errorBody())
                            Text("Настройки → ${cell.tab.title}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                errors.filter { it.cell == null }.forEach { Text(it.message, style = errorBody()) }
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// The width of the error list from which its four columns each fit a word: the cost panel and the narrow bar are on either side of it.
private val CALC_ERRORS_TABLE_WIDTH = 460.dp

/** The text of an error list: heavier than the body text around it. */
@Composable
private fun errorBody() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)

@Composable
internal fun TextInput(field: FieldView, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    InputField(field.text, onChange, modifier.testTag(field.tag), label = label, error = field.error)
}

@Composable
internal fun NumberInput(field: FieldView, label: String, onChange: (String) -> Unit, width: Dp = NUMBER_WIDTH) {
    NumberInput(field, label, onChange, Modifier.width(width))
}

/** A number field that takes the room [modifier] gives it, a cell of a [FieldGrid] for instance. */
@Composable
internal fun NumberInput(field: FieldView, label: String, onChange: (String) -> Unit, modifier: Modifier) {
    InputField(field.text, onChange, modifier.testTag(field.tag), label = label, error = field.error, numeric = true)
}

@Composable
internal fun Choice(choice: ChoiceView, modifier: Modifier, onSelect: (Int) -> Unit) {
    ChoiceField(choice.label, choice.selected, choice.options, onSelect, modifier, error = choice.error, testTag = choice.tag)
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WarningText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = WarningWeight), color = MaterialTheme.colorScheme.tertiary)
    }
}
