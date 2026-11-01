package io.github.laalka.slav.app.attachments

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.AccentTextButton
import io.github.laalka.slav.app.ui.ButtonIcon
import io.github.laalka.slav.app.ui.NoticeCard
import io.github.laalka.slav.app.ui.SecondaryButton
import io.github.laalka.slav.app.ui.SectionCard

object AttachmentTags {
    const val ATTACH = "attachments/attach"
    const val EMPTY = "attachments/empty"
    const val MESSAGE = "attachments/message"

    /**
     * A row by its position; its parts are "<row>/name", "<row>/size", "<row>/open", "<row>/remove" and
     * "<row>/thumbnail". The thumbnail holds "<row>/thumbnail/image" once the picture is made, or
     * "<row>/thumbnail/icon" when the file has none.
     */
    fun row(index: Int) = "attachments/row/$index"
}

private val THUMBNAIL_SIZE = 72.dp

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
        state.rows.forEachIndexed { index, row -> DrawingRow(AttachmentTags.row(index), row, state.thumbnails[row.id], model) }
        SecondaryButton(onClick = { filePicker.chooseDrawing()?.let(model::attach) }, modifier = Modifier.testTag(AttachmentTags.ATTACH)) {
            ButtonIcon(Icons.Filled.Add)
            Text("Прикрепить чертёж", modifier = Modifier.padding(start = Spacing.small))
        }
    }
}

@Composable
private fun DrawingRow(tag: String, row: AttachmentRow, thumbnail: DrawingThumbnail?, model: AttachmentsModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Thumbnail("$tag/thumbnail", thumbnail, onOpen = { model.open(row.id) })
        Column(Modifier.weight(1f)) {
            Text(row.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$tag/name"))
            Text(
                row.size,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$tag/size"),
            )
        }
        AccentTextButton(onClick = { model.open(row.id) }, modifier = Modifier.testTag("$tag/open")) { Text("Открыть") }
        AccentTextButton(onClick = { model.remove(row.id) }, modifier = Modifier.testTag("$tag/remove")) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The picture of the drawing, as large as it is on the screen; a click opens the file like «Открыть». [thumbnail] is null while the picture is being made. */
@Composable
private fun Thumbnail(tag: String, thumbnail: DrawingThumbnail?, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .size(THUMBNAIL_SIZE)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = "Открыть", role = Role.Button, onClick = onOpen)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        when (thumbnail) {
            is DrawingThumbnail.Ready -> {
                val bitmap = remember(thumbnail.image) { thumbnail.image.toComposeImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize().testTag("$tag/image"),
                )
            }
            DrawingThumbnail.Unavailable -> Icon(
                DocumentIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp).testTag("$tag/icon"),
            )
            null -> Unit
        }
    }
}
