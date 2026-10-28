package io.github.laalka.slav.app.calculations

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
import io.github.laalka.slav.app.ui.InputField

/** «Удалить расчёт?»: deleting needs the administrator password, the error of a wrong one is shown under the field. */
@Composable
fun DeleteDialog(question: DeleteQuestion, onPasswordChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Удалить расчёт?") },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("Расчёт «${question.name}» будет удалён без возможности восстановления. Для удаления нужен пароль администратора.")
                InputField(
                    value = "",
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth().testTag(CalculationTags.DELETE_PASSWORD),
                    label = "Пароль администратора",
                    error = question.error,
                    onEnter = onConfirm,
                    password = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(CalculationTags.DELETE_CONFIRM)) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(CalculationTags.DELETE_CANCEL)) { Text("Отмена") }
        },
    )
}
