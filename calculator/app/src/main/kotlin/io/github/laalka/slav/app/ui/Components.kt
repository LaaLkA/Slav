package io.github.laalka.slav.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.extendedColors
import io.github.laalka.slav.app.theme.tabular

/** The scrolling body of a settings tab. */
@Composable
fun TabContent(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            content = content,
        )
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * A titled section of a tab: the surface of the theme with a 1 dp line round it and no shadow, [Spacing.card] of
 * room round its content, the [title] 15 sp semibold and the [subtitle] 13 sp in the secondary colour.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

// ---- fields ----

/** The thickness of the outline of a field in error: the same whether or not the field is focused. */
private val ERROR_OUTLINE = 2.dp

/** The thickness of the outline of a focused field. */
private val FOCUS_OUTLINE = 2.dp

private val IDLE_OUTLINE = 1.dp

/** The room between the outline of a field and its text, measured from the outside edge whatever the thickness of the outline. */
val FieldTextInset = 12.dp

// The gap between the label of a field, the field and the message under it.
private val LABEL_GAP = 6.dp

// The line of a label (12 sp, 16 sp of line height) and the gap under it: what a labelled field has above its box.
private val LABEL_LINE = 16.dp

// What the width of a field comes to when the layout gives it none.
private val FIELD_MIN_WIDTH = 280.dp

private const val DISABLED_ALPHA = 0.38f

/** The room a field leaves above its box for its label. */
val FieldLabelRoom = LABEL_LINE + LABEL_GAP

/** The height of a button with its touch area, an icon button or a check box: Material gives them all 48 dp. */
val TouchTarget = 48.dp

/**
 * Puts a control (a button, an icon button, a check box) that stands in a top-aligned row beside a labelled field
 * level with it: the middle of the control meets the middle of the box of the field, which is [ControlHeight] tall
 * below its label. [controlHeight] is the height of the control, [TouchTarget] for an icon button and a check box.
 */
fun Modifier.besideField(controlHeight: Dp = ControlHeight): Modifier = padding(top = FieldLabelRoom + (ControlHeight - controlHeight) / 2)

/**
 * The room above a text of [lineHeight] that stands in a top-aligned row beside a field, so its line is level with
 * the text in the box: under the label of a [labelled] field, or from the top of a field that has none.
 */
fun fieldTextOffset(lineHeight: Dp, labelled: Boolean = true): Dp = (if (labelled) FieldLabelRoom else 0.dp) + (ControlHeight - lineHeight) / 2

/** The text of every field: 15 sp, on a screen and in a dialog alike. */
private val FIELD_TEXT: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge

/**
 * A single-line field whose error is shown under it. What is typed lives in the field's own state
 * and every change is reported: typing never waits for a model round trip, which would move the
 * caret. [value] is therefore read only when the field enters the composition; a screen whose
 * model replaces the text itself re-creates the field (a `key` on the model's epoch). A [password]
 * field shows dots instead of the text. A [numeric] field is right-aligned, in digits of equal width.
 */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    error: String? = null,
    numeric: Boolean = false,
    onEnter: (() -> Unit)? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    password: Boolean = false,
) {
    var text by remember { mutableStateOf(value) }
    // Not the ambient style: a dialog sets its body text 14 sp, which would make the same field smaller there than on a screen.
    val style = FIELD_TEXT
    Field(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        modifier = if (onEnter == null) modifier else modifier.onPreviewKeyEvent { event ->
            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
            if (isEnter && event.type == KeyEventType.KeyDown) onEnter()
            isEnter
        },
        textStyle = if (numeric) style.tabular().copy(textAlign = TextAlign.End) else style,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        error = error,
        numeric = numeric,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/**
 * A labelled single-line field with [error] under it, assembled from `BasicTextField` because Material's text
 * field puts the label inside the outline and draws the outline of a field in error 1 dp thick until the field is
 * focused. Here the [label] stands above the box (12 sp, in the secondary colour; in the error colour and heavier
 * while the field is in error), the box is [ControlHeight] tall with the outline of the theme, [FOCUS_OUTLINE]
 * thick in the accent while focused and [ERROR_OUTLINE] thick in the error colour whether focused or not, and
 * the message is [WarningWeight]. Everything is drawn in the decoration of the text field, so the field is one
 * node for a test and for a screen reader: it carries the label and the message with the text.
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    textStyle: TextStyle,
    label: String?,
    error: String?,
    numeric: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val isError = error != null
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics(mergeDescendants = true) {}.defaultMinSize(minWidth = FIELD_MIN_WIDTH),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.merge(TextStyle(color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED_ALPHA))),
        cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        singleLine = true,
        decorationBox = { innerTextField ->
            Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
                if (label != null) {
                    // The line is [LABEL_LINE] whatever height the font gives the text, so [FieldLabelRoom] is true.
                    Box(Modifier.height(LABEL_LINE), contentAlignment = Alignment.CenterStart) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isError) MaterialTheme.extendedColors.errorText else scheme.onSurfaceVariant,
                            fontWeight = if (isError) WarningWeight else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FieldBox(
                    outline = when {
                        isError -> BorderStroke(ERROR_OUTLINE, scheme.error)
                        focused -> BorderStroke(FOCUS_OUTLINE, scheme.primary)
                        else -> BorderStroke(IDLE_OUTLINE, if (enabled) scheme.outline else scheme.outline.copy(alpha = 0.5f))
                    },
                    numeric = numeric,
                    placeholder = placeholder.takeIf { value.isEmpty() },
                    textStyle = textStyle,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    innerTextField = innerTextField,
                )
                if (error != null) {
                    Text(
                        error,
                        modifier = Modifier.heightIn(min = LABEL_LINE),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.extendedColors.errorText,
                        fontWeight = WarningWeight,
                    )
                }
            }
        },
    )
}

/** The box of a field: [ControlHeight] tall, on the fill the theme gives a field, with [outline] round it. */
@Composable
private fun FieldBox(
    outline: BorderStroke,
    numeric: Boolean,
    placeholder: String?,
    textStyle: TextStyle,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    innerTextField: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ControlHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
                .border(outline, shape)
                .padding(horizontal = FieldTextInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            leadingIcon?.invoke()
            // The text is anchored to the end of its slot in a number, to the start in anything else.
            Box(Modifier.weight(1f), contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart) {
                if (placeholder != null) {
                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                innerTextField()
            }
            trailingIcon?.invoke()
        }
    }
}

/** A text in a numeric column: tabular figures, right-aligned. */
@Composable
fun NumberText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End)
}

// ---- tables ----

/**
 * A column header of a table: 12 sp semibold in capitals, opened by 0.6 sp; numeric columns are right-aligned like
 * their values. The capitals are drawn, not stored: a test and a screen reader still get the text as it was written.
 */
@Composable
fun HeaderText(
    text: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text.uppercase(),
        modifier = modifier.semantics { this.text = AnnotatedString(text) },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
    )
}

/** The line between the rows of a table and under its header: 1 dp, the colour of the borders of a card. */
@Composable
fun TableDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** The header row of a table: its labels in [HeaderText], a little air round them and the divider under them. */
@Composable
fun TableHeaderRow(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = verticalAlignment,
            content = content,
        )
        TableDivider()
    }
}

/** A row of a table: it is lit with the track colour while the pointer is over it. */
fun Modifier.tableRow(): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    hoverable(interactionSource).background(if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
}

// ---- messages ----

/** A message inside the screen; errors are never shown in dialogs. An error is a [WarningPanel], anything else a success strip. */
@Composable
fun NoticeCard(text: String, isError: Boolean, onDismiss: (() -> Unit)? = null) {
    if (isError) {
        WarningPanel(text, onDismiss = onDismiss)
        return
    }
    Surface(
        color = MaterialTheme.extendedColors.success,
        contentColor = MaterialTheme.extendedColors.onSuccess,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.gap, top = Spacing.small, bottom = Spacing.small, end = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, modifier = Modifier.weight(1f).padding(vertical = Spacing.small), style = MaterialTheme.typography.bodySmall)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Скрыть") }
            }
        }
    }
}

// ---- choices ----

/** A choice from [options], drawn like a field without a label; disabled while there is nothing to choose. */
@Composable
fun OptionPicker(selected: String?, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = options.isNotEmpty()
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ControlHeight)
                .clip(shape)
                .background(scheme.surfaceContainerLowest, shape)
                .border(IDLE_OUTLINE, if (enabled) scheme.outline else scheme.outline.copy(alpha = 0.5f), shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .padding(start = FieldTextInset, end = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected ?: "—",
                modifier = Modifier.weight(1f),
                style = FIELD_TEXT,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Values shown as chips, each with its own remove button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemovableChips(items: List<String>, onRemove: (Int) -> Unit, numeric: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        items.forEachIndexed { index, item ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Text(item, style = if (numeric) MaterialTheme.typography.labelLarge.tabular() else MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Удалить $item", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * A labelled pick from [options], drawn like the text fields next to it, with [error] under it as
 * theirs. [selected] is shown even when it is not among the options. Options are tagged
 * "[testTag]/<option>".
 */
@Composable
fun ChoiceField(
    label: String,
    selected: String?,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    testTag: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = options.isNotEmpty()
    Box(modifier) {
        Field(
            value = selected.orEmpty(),
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            textStyle = FIELD_TEXT,
            label = label,
            error = error,
            enabled = enabled,
            readOnly = true,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        // A read-only field takes no clicks of its own; this layer over it opens the menu.
        Box(
            Modifier.matchParentSize()
                .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                    modifier = if (testTag == null) Modifier else Modifier.testTag("$testTag/$option"),
                )
            }
        }
    }
}

// The room between the edge of a segment and its label, the same on both sides of every segment of every row.
private val SEGMENT_PADDING = Spacing.medium

// A segment is 36 dp high, in a track that leaves 3 dp round the segments and 2 dp between them.
private val SEGMENT_HEIGHT = 36.dp
private val TRACK_PADDING = 3.dp
private val SEGMENT_GAP = 2.dp

/**
 * One of [options] chosen in a row of segments; [selectedIndex] is the chosen one. This is the only segmented
 * control of the program, so every row looks and sizes alike: a track in the colour of the fields' surface
 * variant with a 1 dp line round it, and the chosen segment lifted off it, in the raised surface (white with a
 * soft shadow in the light theme, a step up in the dark one) with its label heavier. The chosen one is shown by
 * those alone, with no check mark: a mark widens that segment only, so a label that fitted would wrap or be
 * cut as soon as it is chosen. A label is one line and never cut: every segment is as wide as its own label
 * measures in the heavy weight, whether it is chosen or not, plus [SEGMENT_PADDING] on both sides, and all
 * segments of a row are of one height. Segment [index] is tagged `testTagOf(index)` when that is not null.
 */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTagOf: (Int) -> String? = { null },
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val chosenStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
    val idleStyle = chosenStyle.copy(fontWeight = FontWeight.Medium)
    val paddingPx = with(density) { SEGMENT_PADDING.toPx() }
    // The width of a segment in pixels: its label measured in the heavier weight and the padding on both sides.
    val segmentWidths = remember(options, chosenStyle, paddingPx, measurer) {
        options.map { with(density) { (measurer.measure(it, chosenStyle, maxLines = 1, softWrap = false).size.width + 2 * paddingPx).toDp() } }
    }
    val scheme = MaterialTheme.colorScheme
    val segmentShape = MaterialTheme.shapes.small
    val trackShape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .clip(trackShape)
            .background(scheme.surfaceVariant, trackShape)
            .border(1.dp, scheme.outlineVariant, trackShape)
            .padding(TRACK_PADDING)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        options.forEachIndexed { index, option ->
            val chosen = index == selectedIndex
            Box(
                modifier = Modifier
                    .width(segmentWidths[index])
                    .height(SEGMENT_HEIGHT)
                    .then(
                        if (chosen) {
                            Modifier.shadow(1.dp, segmentShape, ambientColor = MaterialTheme.extendedColors.raisedShadow, spotColor = MaterialTheme.extendedColors.raisedShadow)
                                .background(scheme.surfaceBright, segmentShape)
                        } else {
                            Modifier
                        },
                    )
                    .clip(segmentShape)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(index) })
                    .then(testTagOf(index)?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // The padding is round the text, not inside the segment: the text gets exactly the width of its label, and
                // is centred in it, so the lighter weight of a segment that is not chosen leaves the room on both sides equal.
                Text(
                    option,
                    modifier = Modifier.padding(horizontal = SEGMENT_PADDING).fillMaxWidth(),
                    style = if (chosen) chosenStyle else idleStyle,
                    color = if (chosen) scheme.onSurface else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** A checkbox with its text; the whole row toggles. [hint] follows the text, e.g. why the row is disabled. */
@Composable
fun CheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
) {
    Row(
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
