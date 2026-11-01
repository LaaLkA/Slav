package io.github.laalka.slav.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
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

/** A titled section of a tab. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/**
 * A single-line field whose error is shown under it. What is typed lives in the field's own state
 * and every change is reported: typing never waits for a model round trip, which would move the
 * caret. [value] is therefore read only when the field enters the composition; a screen whose
 * model replaces the text itself re-creates the field (a `key` on the model's epoch). A [password]
 * field shows dots instead of the text.
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
    OutlinedField(
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
        label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        leadingIcon = leadingIcon,
        error = error,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/** The text of every field: 16 sp, on a screen and in a dialog alike. */
private val FIELD_TEXT: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge

/** The outline of a field in error is this thick whether or not the field is focused. */
private val ERROR_OUTLINE = 2.dp

// The room an outlined field leaves above itself for its floating label (Material's OutlinedTextField does the same).
private val LABEL_ROOM = 8.dp

// The height of a button, an icon button or a check box with its touch area: Material gives them all 48 dp.
private val TOUCH_TARGET = 48.dp

/**
 * Puts a 48 dp control (a button, an icon button, a check box) that stands in a top-aligned row beside an outlined
 * field level with it: the field is [OutlinedTextFieldDefaults.MinHeight] tall below its [LABEL_ROOM], so the middle
 * of the control meets the middle of the field. A control as tall as the field, such as [OptionPicker], takes
 * [LABEL_ROOM] alone.
 */
fun Modifier.besideField(): Modifier = padding(top = LABEL_ROOM + (OutlinedTextFieldDefaults.MinHeight - TOUCH_TARGET) / 2)

/**
 * An outlined single-line field with [error] under it. It is Material's `OutlinedTextField` assembled from
 * its parts, because that one draws the outline of a field in error 1 dp thick until the field is focused:
 * here it is [ERROR_OUTLINE] always, and the supporting error text is [WarningWeight].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    textStyle: TextStyle,
    label: (@Composable () -> Unit)?,
    error: String?,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = OutlinedTextFieldDefaults.colors()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val isError = error != null
    val textColor = when {
        !enabled -> colors.disabledTextColor
        isError -> colors.errorTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(if (label != null) Modifier.padding(top = LABEL_ROOM) else Modifier)
            .semantics(mergeDescendants = true) {}
            .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.merge(TextStyle(color = textColor)),
        cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        singleLine = true,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = true,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                isError = isError,
                label = label,
                placeholder = placeholder,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                supportingText = error?.let { { Text(it, fontWeight = WarningWeight) } },
                colors = colors,
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = enabled,
                        isError = isError,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = OutlinedTextFieldDefaults.shape,
                        focusedBorderThickness = if (isError) ERROR_OUTLINE else OutlinedTextFieldDefaults.FocusedBorderThickness,
                        unfocusedBorderThickness = if (isError) ERROR_OUTLINE else OutlinedTextFieldDefaults.UnfocusedBorderThickness,
                    )
                },
            )
        },
    )
}

/** A text in a numeric column: tabular figures, right-aligned. */
@Composable
fun NumberText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End)
}

/** A column header of a table; numeric columns are right-aligned like their values. */
@Composable
fun HeaderText(text: String, modifier: Modifier = Modifier, numeric: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
    )
}

/** A message inside the screen; errors are never shown in dialogs. An error is a [WarningPanel]. */
@Composable
fun NoticeCard(text: String, isError: Boolean, onDismiss: (() -> Unit)? = null) {
    if (isError) {
        WarningPanel(text, onDismiss = onDismiss)
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.medium, top = Spacing.small, bottom = Spacing.small, end = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Text(text, modifier = Modifier.weight(1f).padding(vertical = Spacing.small), style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Скрыть") }
            }
        }
    }
}

/** A choice from [options]; disabled while there is nothing to choose. */
@Composable
fun OptionPicker(selected: String?, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        // Shaped and sized like the outlined text fields it stands next to.
        OutlinedButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.extraSmall,
            contentPadding = PaddingValues(start = Spacing.medium, end = Spacing.small),
        ) {
            Text(selected ?: "—", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
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
        OutlinedField(
            value = selected.orEmpty(),
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            textStyle = FIELD_TEXT,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

/**
 * One of [options] chosen in a row of segments; [selectedIndex] is the chosen one. This is the only segmented
 * control of the program, so every row looks and sizes alike: Material's `SingleChoiceSegmentedButtonRow`
 * with a segment per option and the chosen one shown by its colour only. The check mark Material puts into the
 * chosen segment is left out: it widens that segment alone, so a label that fitted would wrap or be cut as soon
 * as it is chosen. A label is one line and never cut: Material sizes the row by the longest word of each label,
 * not by the whole label, so here every segment is as wide as its own label measures plus [SEGMENT_PADDING] on
 * both sides, and all segments of a row are of one height. Segment [index] is tagged `testTagOf(index)` when
 * that is not null.
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
    val labelStyle = MaterialTheme.typography.labelLarge
    val borderPx = with(density) { SegmentedButtonDefaults.BorderWidth.toPx() }
    val paddingPx = with(density) { SEGMENT_PADDING.toPx() }
    // The row lays segments out by weight; a weight in pixels gives each segment exactly its label and padding.
    val segmentPx = remember(options, labelStyle, paddingPx, measurer) {
        options.map { measurer.measure(it, labelStyle, maxLines = 1, softWrap = false).size.width + 2 * paddingPx }
    }
    // Neighbouring segments overlap by one border width.
    val rowWidth = with(density) { (segmentPx.sum() - borderPx * (options.size - 1)).toDp() }
    SingleChoiceSegmentedButtonRow(modifier.width(rowWidth)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.weight(segmentPx[index]).then(testTagOf(index)?.let { Modifier.testTag(it) } ?: Modifier),
                contentPadding = PaddingValues(horizontal = SEGMENT_PADDING),
                icon = {},
            ) {
                Text(option, style = labelStyle, maxLines = 1, softWrap = false)
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
