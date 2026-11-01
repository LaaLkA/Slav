package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.laalka.slav.app.theme.extendedColors

/**
 * A text button in the accent text colour. Material colours the text of a [TextButton] with `primary`, which in the
 * dark theme is the orange of a filled button and is not read on a card; [io.github.laalka.slav.app.theme.ExtendedColors.accentText]
 * is the orange for text, in both themes.
 */
@Composable
fun AccentTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.extendedColors.accentText),
        content = content,
    )
}
