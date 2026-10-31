package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.laalka.slav.app.theme.Spacing

/** The weight of every error text: the regular one does not stand out from the text around it. */
val WarningWeight = FontWeight.SemiBold

/**
 * An error or a warning of a screen: [title] on the error container with a warning icon, then [content]
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
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.medium,
                top = Spacing.small,
                bottom = Spacing.small,
                end = if (onDismiss == null) Spacing.medium else Spacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.small),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = WarningWeight),
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Скрыть") }
                }
            }
            content()
        }
    }
}
