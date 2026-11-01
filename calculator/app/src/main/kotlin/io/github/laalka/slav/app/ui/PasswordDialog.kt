package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing

/** The test tags of one [PasswordDialog]: its password field and its two buttons. */
data class PasswordDialogTags(val password: String, val confirm: String, val cancel: String)

/**
 * A step that needs the administrator password, destructive unless [destructive] says otherwise (its button is
 * then the accent one): [message] says what will happen, the error of a wrong password is shown under the field,
 * never in a separate dialog.
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
    destructive: Boolean = true,
) {
    SlavDialog(
        title = title,
        onDismissRequest = onCancel,
        confirmButton = {
            if (destructive) {
                DestructiveButton(onClick = onConfirm, modifier = Modifier.testTag(tags.confirm)) { Text(confirmLabel) }
            } else {
                PrimaryButton(onClick = onConfirm, modifier = Modifier.testTag(tags.confirm)) { Text(confirmLabel) }
            }
        },
        dismissButton = { SecondaryButton(onClick = onCancel, modifier = Modifier.testTag(tags.cancel)) { Text("Отмена") } },
    ) {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
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
    }
}
