package io.github.laalka.slav.app

import io.github.laalka.slav.data.PasswordSeed
import java.nio.file.Path

/** The administrator password of the tests; the one of the program is not in the repository. */
const val ADMIN_PASSWORD = "test-admin-pass"

/** The seed of [ADMIN_PASSWORD], with few rounds so the tests stay quick. */
fun testAdminSeed(): PasswordSeed = PasswordSeed.of(ADMIN_PASSWORD.toCharArray(), ByteArray(16) { 3 }, iterations = 1_000)

/** UI tests drive no file dialogs. */
object NoFilePicker : FilePicker {
    override fun chooseSaveTarget(title: String, suggestedName: String, extension: String): Path? = error("The test opens no file dialogs")

    override fun chooseImportSource(): Path? = error("The test opens no file dialogs")

    override fun chooseDrawing(): Path? = error("The test opens no file dialogs")
}

/** Starts no program: remembers the files it was asked to open, or fails the way the system can. */
class RecordingOpener : FileOpener {
    val opened = mutableListOf<Path>()

    /** Thrown by the next and every later [open] until it is set back to null. */
    var failure: Exception? = null

    override fun open(file: Path) {
        failure?.let { throw it }
        opened.add(file)
    }
}

/** A picker of drawings that hands out [next] and opens no other dialog. */
class DrawingPicker(var next: Path? = null) : FilePicker {
    override fun chooseSaveTarget(title: String, suggestedName: String, extension: String): Path? = error("The test opens no save dialog")

    override fun chooseImportSource(): Path? = error("The test opens no import dialog")

    override fun chooseDrawing(): Path? = next
}
