package io.github.laalka.slav.app.editor

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.CheckRow
import io.github.laalka.slav.app.ui.ChoiceField
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.SectionCard

private val NUMBER_WIDTH = 168.dp
private val CHOICE_WIDTH = 208.dp
private val WIDE_NUMBER_WIDTH = 208.dp

// Segments share the row's width equally; this fits «Труба профильная» on one line.
private val KIND_ROW_WIDTH = 960.dp

// The parameters of a checked operation sit under its title, past the checkbox.
private val PARAMS_INDENT = 48.dp

// Lines a text up with the text of the outlined fields in the same row.
private val FIELD_TEXT_OFFSET = 18.dp

@Composable
fun EditorScreen(model: EditorModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header(state, onBack)
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
                state.parts.forEach { part -> key(part.id) { PartCard(part, state.currency, model) } }
                state.partsError?.let { ErrorText(it) }
                OutlinedButton(onClick = model::addPart, modifier = Modifier.testTag(EditorTags.ADD_PART)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Добавить деталь", modifier = Modifier.padding(start = Spacing.small))
                }
                SectionCard("Операции изделия", subtitle = "Сборка из деталей: сварка сборки и покраска всего изделия") {
                    state.productOperations.forEach { op -> key(op.ref) { OperationBlock(op, state.currency, model) } }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        HorizontalDivider()
        CalculateBar(state, model::calculate)
    }
}

@Composable
private fun Header(state: EditorState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.small, end = Spacing.large, top = Spacing.medium, bottom = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(EditorTags.BACK)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К расчётам")
        }
        Column(Modifier.weight(1f)) {
            Text(if (state.isNew) "Новый расчёт" else "Редактирование расчёта", style = MaterialTheme.typography.headlineSmall)
            Text(state.priceSource, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PartCard(part: PartView, currency: String, model: EditorModel) {
    SectionCard(part.title) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
            TextInput(part.name, "Название детали", { model.onPartNameChange(part.id, it) }, Modifier.weight(1f))
            NumberInput(part.quantity, "На изделие, шт", { model.onPartQuantityChange(part.id, it) })
            TextButton(
                onClick = { model.copyPart(part.id) },
                modifier = Modifier.padding(top = Spacing.small).testTag("${part.tag}/copy"),
            ) { Text("Копировать") }
            IconButton(
                onClick = { model.deletePart(part.id) },
                modifier = Modifier.padding(top = Spacing.small).testTag("${part.tag}/delete"),
            ) { Icon(Icons.Filled.Delete, contentDescription = "Удалить деталь") }
        }

        Subheader("Материал")
        SingleChoiceSegmentedButtonRow(Modifier.width(KIND_ROW_WIDTH)) {
            SortamentKind.entries.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = kind == part.kind,
                    onClick = { model.onKindChange(part.id, kind) },
                    shape = SegmentedButtonDefaults.itemShape(index, SortamentKind.entries.size),
                    modifier = Modifier.testTag("${part.tag}/kind/${kind.name}"),
                ) { Text(kind.title, maxLines = 1, softWrap = false) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
            Choice(part.grade, Modifier.width(CHOICE_WIDTH)) { model.onGradeChange(part.id, part.grade.options[it]) }
            Choice(part.size, Modifier.width(CHOICE_WIDTH)) { model.onSizeChange(part.id, part.sizeOptions[it]) }
            NumberInput(part.length, "Длина, мм", { model.onLengthChange(part.id, it) })
            part.width?.let { NumberInput(it, "Ширина, мм", { text -> model.onWidthChange(part.id, text) }) }
            Column(Modifier.padding(top = Spacing.small)) {
                Text("Масса детали", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    part.mass ?: "—",
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    modifier = Modifier.testTag("${part.tag}/mass"),
                    softWrap = false,
                )
            }
        }
        ExtraRow(part.materialExtra, currency, LineRef.MaterialOf(part.id), model)

        Subheader("Операции")
        part.operations.forEach { op -> key(op.ref) { OperationBlock(op, currency, model) } }
    }
}

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
                op.extra?.let { ExtraRow(it, currency, op.ref, model) }
            }
        }
    }
}

@Composable
private fun Params(line: LineRef, params: OperationParams, model: EditorModel) {
    val part = (line as? LineRef.OfPart)?.part
    when (params) {
        is OperationParams.LaserSheet -> {
            SingleChoiceSegmentedButtonRow {
                CutMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == params.mode,
                        onClick = { if (part != null) model.onCutModeChange(part, mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, CutMode.entries.size),
                        modifier = Modifier.testTag("${params.modeTag}/${mode.name}"),
                    ) { Text(mode.title) }
                }
            }
            when (params.mode) {
                CutMode.RECTANGLE -> Hint("Длина реза — периметр детали 2 × (длина + ширина), одно пробитие.")
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
                            ReadOnlyNumber("Глубина, мм", params.depth, "${row.tag}/depth")
                            if (params.bothWallsAvailable && part != null) {
                                CheckRow(
                                    "Через обе стенки",
                                    row.throughBothWalls,
                                    { model.onThroughBothWallsChange(part, row.id, it) },
                                    Modifier.padding(top = Spacing.small).testTag("${row.tag}/both-walls"),
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
        is OperationParams.Welding -> {
            Choice(params.weldType, Modifier.width(CHOICE_WIDTH)) { model.onWeldTypeChange(line, params.weldType.options[it]) }
            CountRows(line, params.rows, "Длина шва, мм", "Кол-во, шт", model)
            RowsFooter(params.rowsError, "Добавить шов", params.addTag) { model.addRow(line) }
        }
        is OperationParams.AssemblyWelding -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                Choice(params.weldType, Modifier.width(CHOICE_WIDTH)) { model.onWeldTypeChange(line, params.weldType.options[it]) }
                Choice(params.group, Modifier.width(CHOICE_WIDTH)) { model.onAssemblyGroupChange(params.group.options[it]) }
                Choice(params.thickness, Modifier.width(CHOICE_WIDTH)) { model.onAssemblyThicknessChange(params.thicknessOptions[it]) }
            }
            CountRows(line, params.rows, "Длина шва, мм", "Кол-во, шт", model)
            RowsFooter(params.rowsError, "Добавить шов", params.addTag) { model.addRow(line) }
        }
        is OperationParams.Painting -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
            NumberInput(params.areaOverride, "Своя площадь, м²", { model.onAreaOverrideChange(line, it) }, WIDE_NUMBER_WIDTH)
            Hint(
                params.autoArea?.let { "По габаритам — $it. Пустое поле — считать по габаритам." }
                    ?: "Площадь по габаритам появится, когда заполнены размеры и количество деталей.",
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
    error?.let { ErrorText(it) }
    TextButton(onClick = onAdd, modifier = Modifier.testTag(addTag)) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(addLabel, modifier = Modifier.padding(start = Spacing.small))
    }
}

@Composable
private fun DeleteRowButton(tag: String, onDelete: () -> Unit) {
    IconButton(onClick = onDelete, modifier = Modifier.padding(top = Spacing.small).testTag(tag)) {
        Icon(Icons.Filled.Delete, contentDescription = "Удалить строку")
    }
}

@Composable
private fun ExtraRow(extra: ExtraView, currency: String, line: LineRef, model: EditorModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
        NumberInput(extra.amount, "Доп. операции, $currency", { model.onExtraAmountChange(line, it) }, WIDE_NUMBER_WIDTH)
        TextInput(extra.comment, "Комментарий к доп. операциям", { model.onExtraCommentChange(line, it) }, Modifier.weight(1f))
    }
}

@Composable
private fun CalculateBar(state: EditorState, onCalculate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large, vertical = Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            if (state.calcErrors.isNotEmpty()) CalcErrors(state.calcErrors, state.calcErrorsHint)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Text(
                    if (state.canCalculate) "Все обязательные поля заполнены" else "Заполните выделенные поля: ${state.missingCount}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.canCalculate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Button(onClick = onCalculate, enabled = state.canCalculate, modifier = Modifier.testTag(EditorTags.CALCULATE)) {
                    Text("Рассчитать")
                }
            }
        }
    }
}

/** Spec 7.3: everything that stopped the calculation at once; the table, row and column of each missing price. */
@Composable
private fun CalcErrors(errors: List<ReadableError>, hint: String?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(EditorTags.ERRORS),
    ) {
        Column(
            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text("Расчёт не выполнен — не хватает данных", style = MaterialTheme.typography.titleSmall)
            }
            val cells = errors.mapNotNull { it.cell }
            if (cells.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                    listOf("Таблица" to 2f, "Строка" to 1f, "Столбец" to 1f, "Где заполнить" to 2f).forEach { (title, weight) ->
                        Text(title, modifier = Modifier.weight(weight), style = MaterialTheme.typography.labelMedium)
                    }
                }
                cells.forEach { cell ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                        Text(cell.table, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                        Text(cell.row, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(cell.column, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.tabular())
                        Text("Настройки → ${cell.tab.title}", modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            errors.filter { it.cell == null }.forEach { Text(it.message, style = MaterialTheme.typography.bodyMedium) }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TextInput(field: FieldView, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    InputField(field.text, onChange, modifier.testTag(field.tag), label = label, error = field.error)
}

@Composable
private fun NumberInput(field: FieldView, label: String, onChange: (String) -> Unit, width: Dp = NUMBER_WIDTH) {
    InputField(field.text, onChange, Modifier.width(width).testTag(field.tag), label = label, error = field.error, numeric = true)
}

@Composable
private fun Choice(choice: ChoiceView, modifier: Modifier, onSelect: (Int) -> Unit) {
    ChoiceField(choice.label, choice.selected, choice.options, onSelect, modifier, error = choice.error, testTag = choice.tag)
}

/** A value the user does not type, drawn as a field so it lines up with the row (spec 6.4: depth = thickness). */
@Composable
private fun ReadOnlyNumber(label: String, value: String, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.tabular().copy(textAlign = TextAlign.End),
        modifier = Modifier.width(NUMBER_WIDTH).testTag(tag),
    )
}

@Composable
private fun Subheader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.small))
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun WarningText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}
