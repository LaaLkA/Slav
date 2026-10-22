package io.github.laalka.slav.app

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name

/** Asks the user for a file; null when the dialog was cancelled. */
interface FilePicker {
    fun chooseExportTarget(suggestedName: String): Path?

    fun chooseImportSource(): Path?
}

/** The system file dialogs: AWT's FileDialog opens the native dialog on macOS and Windows. */
class NativeFilePicker(private val owner: Frame) : FilePicker {
    override fun chooseExportTarget(suggestedName: String): Path? {
        val chosen = FileDialog(owner, "Экспорт настроек", FileDialog.SAVE).apply { file = suggestedName }.pick()
            ?: return null
        // Settings files are recognised by their extension; a name typed without it gets one.
        return if (chosen.name.endsWith(JSON, ignoreCase = true)) chosen else chosen.resolveSibling(chosen.name + JSON)
    }

    override fun chooseImportSource(): Path? = FileDialog(owner, "Импорт настроек", FileDialog.LOAD).apply {
        // macOS honours the filter; the Windows dialog takes the pattern from the file name field instead.
        setFilenameFilter { _, name -> name.endsWith(JSON, ignoreCase = true) }
        if (System.getProperty("os.name").startsWith("Windows")) file = "*$JSON"
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
