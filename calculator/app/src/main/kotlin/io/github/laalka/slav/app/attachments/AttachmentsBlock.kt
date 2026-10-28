package io.github.laalka.slav.app.attachments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.app.ui.PasswordDialog
import io.github.laalka.slav.app.ui.PasswordDialogTags
import io.github.laalka.slav.app.ui.SectionCard

object AttachmentTags {
    const val ATTACH = "attachments/attach"
    const val EMPTY = "attachments/empty"
    const val MESSAGE = "attachments/message"
    const val REMOVE_PASSWORD = "attachments/remove/password"
    const val REMOVE_CONFIRM = "attachments/remove/confirm"
    const val REMOVE_CANCEL = "attachments/remove/cancel"

    /** A row by its position; its parts are "<row>/name", "<row>/size", "<row>/open" and "<row>/remove". */
    fun row(index: Int) = "attachments/row/$index"
}

private val REMOVE_TAGS = PasswordDialogTags(AttachmentTags.REMOVE_PASSWORD, AttachmentTags.REMOVE_CONFIRM, AttachmentTags.REMOVE_CANCEL)

/** «Чертежи»: the attached files with «Открыть» and «Удалить», and the button that attaches another one. */
@Composable
fun AttachmentsBlock(model: AttachmentsModel, filePicker: FilePicker, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    SectionCard("Чертежи", modifier = modifier, subtitle = "Файлы PDF, JPG, JPEG и PNG, каждый до 50 МБ") {
        state.message?.let { message ->
            Box(Modifier.testTag(AttachmentTags.MESSAGE)) { NoticeCard(message.text, message.isError, onDismiss = model::dismissMessage) }
        }
        if (state.rows.isEmpty()) {
            Text(
                "Чертежей пока нет",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(AttachmentTags.EMPTY),
            )
        }
        state.rows.forEachIndexed { index, row -> DrawingRow(AttachmentTags.row(index), row, model) }
        OutlinedButton(onClick = { filePicker.chooseDrawing()?.let(model::attach) }, modifier = Modifier.testTag(AttachmentTags.ATTACH)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Прикрепить чертёж", modifier = Modifier.padding(start = Spacing.small))
        }
    }
    state.removeQuestion?.let { question ->
        PasswordDialog(
            title = "Удалить чертёж?",
            message = "Чертёж «${question.name}» будет удалён без возможности восстановления. Для удаления нужен пароль администратора.",
            confirmLabel = "Удалить",
            error = question.error,
            tags = REMOVE_TAGS,
            onPasswordChange = model::onRemovePasswordChange,
            onConfirm = model::confirmRemove,
            onCancel = model::cancelRemove,
        )
    }
}

@Composable
private fun DrawingRow(tag: String, row: AttachmentRow, model: AttachmentsModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Column(Modifier.weight(1f)) {
            Text(row.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$tag/name"))
            Text(
                row.size,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$tag/size"),
            )
        }
        TextButton(onClick = { model.open(row.id) }, modifier = Modifier.testTag("$tag/open")) { Text("Открыть") }
        TextButton(onClick = { model.requestRemove(row.id) }, modifier = Modifier.testTag("$tag/remove")) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        }
    }
}
