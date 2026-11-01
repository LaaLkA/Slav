package io.github.laalka.slav.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.laalka.slav.app.theme.extendedColors

/** The height of a button and of a field: the least that is a comfortable target for a pointer. */
val ControlHeight = 44.dp

/** The side of the icon in a button: smaller than Material's 24 dp, which towers over the 14 sp label. */
private val BUTTON_ICON = 18.dp

private val BUTTON_PADDING = PaddingValues(horizontal = 20.dp)
private val TEXT_BUTTON_PADDING = PaddingValues(horizontal = 12.dp)

// A disabled control keeps its look at half strength, the way the design draws it.
private const val DISABLED_ALPHA = 0.5f

private val DASH = 6.dp
private val DASH_GAP = 4.dp

/**
 * A button is laid out as high as it is drawn, [ControlHeight]: Material pads a button out to 48 dp so that a
 * finger can find it, which on a desktop only pushes the buttons off the line of the fields beside them.
 */
@Composable
private fun Compact(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified, content = content)
}

/** The icon of a button, sized to its label. */
@Composable
fun ButtonIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, modifier = modifier.size(BUTTON_ICON))
}

/** The one main action of a screen: the accent fill. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Compact {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = ControlHeight),
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                disabledContainerColor = scheme.primary.copy(alpha = DISABLED_ALPHA),
                disabledContentColor = scheme.onPrimary.copy(alpha = DISABLED_ALPHA),
            ),
            elevation = null,
            contentPadding = BUTTON_PADDING,
            content = content,
        )
    }
}

/** Any other action: the outline colour round the text, no accent. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Compact {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = ControlHeight),
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.extendedColors.buttonFill,
                contentColor = scheme.onSurface,
                disabledContainerColor = MaterialTheme.extendedColors.buttonFill,
                disabledContentColor = scheme.onSurface.copy(alpha = DISABLED_ALPHA),
            ),
            border = BorderStroke(1.dp, if (enabled) scheme.outline else scheme.outline.copy(alpha = DISABLED_ALPHA)),
            contentPadding = BUTTON_PADDING,
            content = content,
        )
    }
}

/** An action that destroys something: the error text on the tinted error container. */
@Composable
fun DestructiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.extendedColors
    Compact {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = ControlHeight),
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = colors.errorText,
                disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                disabledContentColor = colors.errorText.copy(alpha = DISABLED_ALPHA),
            ),
            border = BorderStroke(1.dp, if (enabled) colors.errorBorder else colors.errorBorder.copy(alpha = colors.errorBorder.alpha * DISABLED_ALPHA)),
            contentPadding = BUTTON_PADDING,
            content = content,
        )
    }
}

/**
 * A secondary button whose outline is dashed: the action of developer mode, which is not part of the product and
 * so is drawn as a placeholder is. The dashes are drawn over a button with no border of its own.
 */
@Composable
fun DashedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val outline = if (enabled) scheme.outline else scheme.outline.copy(alpha = DISABLED_ALPHA)
    Compact {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = ControlHeight).dashedBorder(1.dp, outline, shape.topStart),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.extendedColors.buttonFill,
                contentColor = scheme.onSurface,
                disabledContainerColor = MaterialTheme.extendedColors.buttonFill,
                disabledContentColor = scheme.onSurface.copy(alpha = DISABLED_ALPHA),
            ),
            elevation = null,
            contentPadding = BUTTON_PADDING,
            content = content,
        )
    }
}

/**
 * A text button in the accent text colour. Material colours the text of a [androidx.compose.material3.TextButton]
 * with `primary`, which in the dark theme is the orange of a filled button and is not read on a card;
 * [io.github.laalka.slav.app.theme.ExtendedColors.accentText] is the orange for text, in both themes. A [compact]
 * one is 36 dp high with 13 sp text: the actions in the title of a card and in a row of a table.
 */
@Composable
fun AccentTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Compact {
        TextButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = if (compact) COMPACT_HEIGHT else ControlHeight),
            enabled = enabled,
            shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.extendedColors.accentText,
                disabledContentColor = MaterialTheme.extendedColors.accentText.copy(alpha = DISABLED_ALPHA),
            ),
            contentPadding = TEXT_BUTTON_PADDING,
        ) {
            if (compact) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = COMPACT_TEXT)) { content() }
            } else {
                content()
            }
        }
    }
}

/** The height of a [compact] text button and the size of its text. */
private val COMPACT_HEIGHT = 36.dp
private val COMPACT_TEXT = 13.sp

/** [width] of a dashed line round a rounded rectangle with corners of [corner], drawn inside the bounds of the element. */
internal fun Modifier.dashedBorder(width: Dp, color: Color, corner: CornerSize): Modifier = drawWithContent {
    // Over the content: a button paints its own fill, which would hide a line drawn behind it.
    drawContent()
    val stroke = width.toPx()
    val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH_GAP.toPx()))
    // The stroke is centred on the path, so the path is inset by half of it to keep the line inside the bounds.
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(corner.toPx(size, this) - stroke / 2),
        style = Stroke(width = stroke, pathEffect = dashes),
    )
}
