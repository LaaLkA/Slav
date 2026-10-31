package io.github.laalka.slav.app.attachments

import io.github.laalka.slav.app.FileOpener
import io.github.laalka.slav.app.ui.formatFileSize
import io.github.laalka.slav.data.AttachResult
import io.github.laalka.slav.data.Attachment
import io.github.laalka.slav.data.AttachmentRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

/** A drawing as the block draws it; [size] is text such as «345,6 КБ». */
data class AttachmentRow(val id: UUID, val fileName: String, val size: String)

/** What the block says about the last thing the user did. */
data class AttachmentsMessage(val text: String, val isError: Boolean)

/** The picture of a drawing in its row, once it is known. */
sealed interface DrawingThumbnail {
    /** The first page of the PDF or the image itself, scaled down. */
    class Ready(val image: BufferedImage) : DrawingThumbnail

    /** The file cannot be decoded (damaged, a PDF that asks for a password); the row draws a neutral document icon. */
    data object Unavailable : DrawingThumbnail
}

data class AttachmentsState(
    val rows: List<AttachmentRow>,
    val message: AttachmentsMessage?,
    /** The pictures made so far, by the id of the row; a row without one is still waiting for it. */
    val thumbnails: Map<UUID, DrawingThumbnail>,
)

/**
 * The «Чертежи» block of the editor and of the card: the drawings of one calculation. Attaching
 * and removing are always allowed and ask for nothing, on a draft and on a calculated record alike:
 * they change the drawings of this calculation only, never its price, status or version.
 *
 * Every row shows a thumbnail. It is decoded on [ioDispatcher], outside the thread of [scope] (the
 * UI's), and appears in the state when it is ready; the rows themselves never wait for it.
 *
 * The calculation is given by [calculationId], null while an editor's new calculation is not saved
 * yet. Attaching to that one first saves it, through [storedCalculationId], so a drawing always
 * belongs to a calculation that exists.
 */
class AttachmentsModel(
    private val attachments: AttachmentRepository,
    private val opener: FileOpener,
    private val calculationId: () -> UUID?,
    private val storedCalculationId: () -> UUID,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var message: AttachmentsMessage? = null

    /** The drawings whose thumbnail is being made, so that a reload does not start a second decoding. */
    private val making: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private val initialFiles = files()
    private val _state = MutableStateFlow(build(initialFiles, emptyMap()))
    val state: StateFlow<AttachmentsState> = _state.asStateFlow()

    init {
        makeThumbnails(initialFiles)
    }

    /** Reads the list again, after the calculation may have changed elsewhere. */
    fun reload() = refresh()

    /**
     * Copies [source] into the calculation's drawings; a file that is not accepted is explained in
     * the message. The refusal is asked before the calculation is stored, so a wrong file does not
     * leave a new calculation behind as an empty draft.
     */
    fun attach(source: Path) {
        val refusal = attachments.refusalOf(source)
        message = if (refusal != null) {
            AttachmentsMessage(refusal, isError = true)
        } else {
            when (val result = attachments.add(storedCalculationId(), source)) {
                is AttachResult.Added -> null
                is AttachResult.Refused -> AttachmentsMessage(result.message, isError = true)
            }
        }
        refresh()
    }

    /** Opens the drawing in the program the system has for it; where it cannot, the message gives the path of the file. */
    fun open(id: UUID) {
        val attachment = find(id) ?: return
        val file = attachments.pathOf(attachment)
        message = when {
            !file.exists() -> AttachmentsMessage("Файл «${attachment.fileName}» не найден на диске: $file", isError = true)
            else -> try {
                opener.open(file)
                null
            } catch (e: UnsupportedOperationException) {
                AttachmentsMessage("Эта система не открывает файлы из программы. Чертёж «${attachment.fileName}» лежит здесь: $file", isError = false)
            } catch (e: IOException) {
                AttachmentsMessage("Не удалось открыть «${attachment.fileName}»: ${e.message}. Файл лежит здесь: $file", isError = true)
            }
        }
        refresh()
    }

    /** «Удалить»: removes the drawing and its file at once, with no question; the file that cannot be deleted stays, with a message. */
    fun remove(id: UUID) {
        val attachment = find(id) ?: return
        message = try {
            attachments.remove(id)
            null
        } catch (e: IOException) {
            AttachmentsMessage("Не удалось удалить «${attachment.fileName}»: ${e.message}. Закройте файл в других программах и повторите.", isError = true)
        }
        refresh()
    }

    fun dismissMessage() {
        message = null
        refresh()
    }

    private fun find(id: UUID): Attachment? = files().firstOrNull { it.id == id }

    private fun files(): List<Attachment> = calculationId()?.let(attachments::list).orEmpty()

    private fun refresh() {
        val files = files()
        _state.update { previous -> build(files, previous.thumbnails) }
        makeThumbnails(files)
    }

    /** The state of [files] with the thumbnails already [made], of the rows that are still there. */
    private fun build(files: List<Attachment>, made: Map<UUID, DrawingThumbnail>): AttachmentsState {
        val rows = files.map { AttachmentRow(it.id, it.fileName, formatFileSize(it.sizeBytes)) }
        return AttachmentsState(rows, message, rows.mapNotNull { row -> made[row.id]?.let { row.id to it } }.toMap())
    }

    /** Starts the thumbnail of every file of [files] that has none yet and is not being made. */
    private fun makeThumbnails(files: List<Attachment>) {
        val done = _state.value.thumbnails
        for (attachment in files) {
            if (attachment.id in done || !making.add(attachment.id)) continue
            scope.launch {
                try {
                    val image = withContext(ioDispatcher) { attachments.thumbnail(attachment, THUMBNAIL_PX) }
                    val thumbnail = if (image == null) DrawingThumbnail.Unavailable else DrawingThumbnail.Ready(image)
                    _state.update { current ->
                        // The drawing may have been removed while its picture was being made.
                        if (current.rows.any { it.id == attachment.id }) current.copy(thumbnails = current.thumbnails + (attachment.id to thumbnail)) else current
                    }
                } finally {
                    making.remove(attachment.id)
                }
            }
        }
    }

    companion object {
        /** The thumbnail is shown about 72 dp wide; twice that is sharp on a screen of double density. */
        const val THUMBNAIL_PX = 160
    }
}
