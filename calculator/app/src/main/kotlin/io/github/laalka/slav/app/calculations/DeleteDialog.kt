package io.github.laalka.slav.app.calculations

import androidx.compose.runtime.Composable
import io.github.laalka.slav.app.ui.PasswordDialog
import io.github.laalka.slav.app.ui.PasswordDialogTags

private val TAGS = PasswordDialogTags(CalculationTags.DELETE_PASSWORD, CalculationTags.DELETE_CONFIRM, CalculationTags.DELETE_CANCEL)

/** «Удалить расчёт?»: deleting needs the administrator password, the error of a wrong one is shown under the field. */
@Composable
fun DeleteDialog(question: DeleteQuestion, onPasswordChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    PasswordDialog(
        title = "Удалить расчёт?",
        message = "Расчёт «${question.name}» будет удалён без возможности восстановления. Для удаления нужен пароль администратора.",
        confirmLabel = "Удалить",
        error = question.error,
        tags = TAGS,
        onPasswordChange = onPasswordChange,
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}
