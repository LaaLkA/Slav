package io.github.laalka.slav.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** The title of a dialog: 18 sp semibold, which is smaller than the 26 sp Material gives it and larger than the body under it. */
private val DialogTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp)

/**
 * The dialog of the program: 16 dp corners, the [title] in 18 sp semibold and the [content] in the 14 sp body, with
 * the buttons of the screens in its foot, [dismissButton] a [SecondaryButton] and [confirmButton] the
 * [PrimaryButton] or the [DestructiveButton] of what the dialog asks.
 */
@Composable
fun SlavDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, style = DialogTitle) },
        text = content,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = MaterialTheme.shapes.extraLarge,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
