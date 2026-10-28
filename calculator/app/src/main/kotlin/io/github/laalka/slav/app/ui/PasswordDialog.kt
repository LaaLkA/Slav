package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing

/** The test tags of one [PasswordDialog]: its password field and its two buttons. */
data class PasswordDialogTags(val password: String, val confirm: String, val cancel: String)

/**
 * A destructive step that needs the administrator password: [message] says what will happen, the
 * error of a wrong password is shown under the field, never in a separate dialog.
 */
@Composable
fun PasswordDialog(
    title: String,
    message: String,
    confirmLabel: String,
    error: String?,
    tags: PasswordDialogTags,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(message)
                InputField(
                    value = "",
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth().testTag(tags.password),
                    label = "Пароль администратора",
                    error = error,
                    onEnter = onConfirm,
                    password = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(tags.confirm)) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(tags.cancel)) { Text("Отмена") }
        },
    )
}
