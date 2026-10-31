package io.github.laalka.slav.app.calculations

import androidx.compose.runtime.Composable
import io.github.laalka.slav.app.ui.PasswordDialog
import io.github.laalka.slav.app.ui.PasswordDialogTags

private val TAGS = PasswordDialogTags(CalculationTags.ARCHIVE_PASSWORD, CalculationTags.ARCHIVE_CONFIRM, CalculationTags.ARCHIVE_CANCEL)

/** «Отправить в архив?»: the calculations are deleted once the file is written, so the administrator password is asked first. */
@Composable
fun ArchiveDialog(question: ArchiveQuestion, onPasswordChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val what = if (question.count == 1) "Расчёт «${question.name}» будет записан" else "Расчёты (${question.count}) будут записаны"
    val gone = if (question.count == 1) "удалён" else "удалены"
    PasswordDialog(
        title = "Отправить в архив?",
        message = "$what в файл ${question.target} и $gone из программы вместе с чертежами. Для удаления нужен пароль администратора.",
        confirmLabel = "В архив",
        error = question.error,
        tags = TAGS,
        onPasswordChange = onPasswordChange,
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}
