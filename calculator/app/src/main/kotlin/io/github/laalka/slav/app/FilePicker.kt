package io.github.laalka.slav.app

import io.github.laalka.slav.data.AttachmentRepository
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name

/** Asks the user for a file; null when the dialog was cancelled. */
interface FilePicker {
    /** A file to write; [extension] includes its dot (".json"), and a name typed without it gets it. */
    fun chooseSaveTarget(title: String, suggestedName: String, extension: String): Path?

    fun chooseImportSource(): Path?

    /** A drawing to attach to a calculation: a PDF, JPG, JPEG or PNG file. */
    fun chooseDrawing(): Path?
}

/** The system file dialogs: AWT's FileDialog opens the native dialog on macOS and Windows. */
class NativeFilePicker(private val owner: Frame) : FilePicker {
    override fun chooseSaveTarget(title: String, suggestedName: String, extension: String): Path? {
        val chosen = FileDialog(owner, title, FileDialog.SAVE).apply { file = suggestedName }.pick() ?: return null
        // Files are recognised by their extension; a name typed without it gets one.
        return if (chosen.name.endsWith(extension, ignoreCase = true)) chosen else chosen.resolveSibling(chosen.name + extension)
    }

    override fun chooseImportSource(): Path? = FileDialog(owner, "Импорт настроек", FileDialog.LOAD).apply {
        // macOS honours the filter; the Windows dialog takes the pattern from the file name field instead.
        setFilenameFilter { _, name -> name.endsWith(JSON, ignoreCase = true) }
        if (System.getProperty("os.name").startsWith("Windows")) file = "*$JSON"
    }.pick()

    override fun chooseDrawing(): Path? = FileDialog(owner, "Прикрепить чертёж", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in AttachmentRepository.ALLOWED_EXTENSIONS }
        if (System.getProperty("os.name").startsWith("Windows")) {
            file = AttachmentRepository.ALLOWED_EXTENSIONS.joinToString(";") { "*.$it" }
        }
    }.pick()

    /** Shows the modal dialog and waits for the user. */
    private fun FileDialog.pick(): Path? {
        isVisible = true
        val name = file ?: return null
        return Path(directory, name)
    }

    private companion object {
        const val JSON = ".json"
    }
}
