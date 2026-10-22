package io.github.laalka.slav.app

import java.nio.file.Path

/** UI tests drive no file dialogs. */
object NoFilePicker : FilePicker {
    override fun chooseExportTarget(suggestedName: String): Path? = error("The test opens no file dialogs")

    override fun chooseImportSource(): Path? = error("The test opens no file dialogs")
}
