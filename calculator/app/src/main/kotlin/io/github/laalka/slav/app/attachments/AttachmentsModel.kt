package io.github.laalka.slav.app.attachments

import io.github.laalka.slav.app.FileOpener
import io.github.laalka.slav.app.calculations.DeleteFlow
import io.github.laalka.slav.app.calculations.DeleteQuestion
import io.github.laalka.slav.app.ui.formatFileSize
import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.data.AttachResult
import io.github.laalka.slav.data.Attachment
import io.github.laalka.slav.data.AttachmentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists

/** A drawing as the block draws it; [size] is text such as «345,6 КБ». */
data class AttachmentRow(val id: UUID, val fileName: String, val size: String)

/** What the block says about the last thing the user did. */
data class AttachmentsMessage(val text: String, val isError: Boolean)

data class AttachmentsState(
    val rows: List<AttachmentRow>,
    val message: AttachmentsMessage?,
    /** The drawing waiting for the administrator password to be removed. */
    val removeQuestion: DeleteQuestion?,
)

/**
 * The «Чертежи» block of the editor and of the card: the drawings of one calculation. Attaching
 * is always allowed, it does not change the price. Removing is free where [passwordGuard] is null
 * (a draft) and otherwise asks for the administrator password, as deleting a calculation does; a
 * wrong password removes nothing.
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
    passwordGuard: AdminPassword?,
) {
    private var message: AttachmentsMessage? = null
    private val removal = passwordGuard?.let { DeleteFlow(it, ::removeNow) }

    private val _state = MutableStateFlow(build())
    val state: StateFlow<AttachmentsState> = _state.asStateFlow()

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

    /** «Удалить»: removes the drawing at once from a draft, asks for the password on a calculated record. */
    fun requestRemove(id: UUID) {
        if (removal == null) removeNow(id) else removal.request(id)
        refresh()
    }

    fun onRemovePasswordChange(text: String) {
        removal?.onPasswordChange(text)
        refresh()
    }

    fun cancelRemove() {
        removal?.cancel()
        refresh()
    }

    /** Removes the drawing when the password is the administrator's; a wrong one is flagged and the file stays. */
    fun confirmRemove() {
        removal?.confirm()
        refresh()
    }

    fun dismissMessage() {
        message = null
        refresh()
    }

    private fun removeNow(id: UUID) {
        val attachment = find(id) ?: return
        message = try {
            attachments.remove(id)
            null
        } catch (e: IOException) {
            AttachmentsMessage("Не удалось удалить «${attachment.fileName}»: ${e.message}. Закройте файл в других программах и повторите.", isError = true)
        }
    }

    private fun find(id: UUID): Attachment? = calculationId()?.let(attachments::list)?.firstOrNull { it.id == id }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): AttachmentsState {
        val files = calculationId()?.let(attachments::list).orEmpty()
        val pending = removal?.pending?.let { id -> files.firstOrNull { it.id == id } }
        return AttachmentsState(
            rows = files.map { AttachmentRow(it.id, it.fileName, formatFileSize(it.sizeBytes)) },
            message = message,
            removeQuestion = pending?.let { DeleteQuestion(it.fileName, removal.error) },
        )
    }
}
