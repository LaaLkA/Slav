package io.github.laalka.slav.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.extendedColors

/** The weight of every error text: the regular one does not stand out from the text around it. */
val WarningWeight = FontWeight.SemiBold

/**
 * A circle with an exclamation mark in it: the icon of an alert. The Material «error outline» icon, which the core
 * icon set does not carry (see [ArchiveIcon]).
 */
private val AlertIcon: ImageVector by lazy {
    materialIcon(name = "Outlined.ErrorOutline") {
        materialPath {
            moveTo(11f, 15f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(11f, 7f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(11.99f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
            reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
            reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
            reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
            close()
        }
    }
}

private val ALERT_ICON_SIZE = 18.dp

/**
 * An error or a warning of a screen: [title] on the error container with an alert icon, then [content]
 * under it. Every message that stops the user, from the count of unfilled fields to the list of missing
 * prices, is drawn with it, so they all look alike. A message that can be put away takes [onDismiss].
 */
@Composable
fun WarningPanel(
    title: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.extendedColors.errorBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.gap,
                top = Spacing.small,
                bottom = Spacing.small,
                end = if (onDismiss == null) Spacing.gap else Spacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                Icon(AlertIcon, contentDescription = null, modifier = Modifier.size(ALERT_ICON_SIZE), tint = MaterialTheme.colorScheme.error)
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.small),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = WarningWeight),
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Скрыть") }
                }
            }
            content()
        }
    }
}
