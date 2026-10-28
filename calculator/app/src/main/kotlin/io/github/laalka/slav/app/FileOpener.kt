package io.github.laalka.slav.app

import java.awt.Desktop
import java.io.IOException
import java.nio.file.Path

/** Shows a file in the program the operating system has for it. */
interface FileOpener {
    /**
     * Opens [file]. Throws [UnsupportedOperationException] when the system gives a program no way
     * to open files, and [IOException] when there is no program for this file or it cannot be started.
     */
    fun open(file: Path)
}

/** java.awt.Desktop: the same as a double click on the file in the file manager. */
object DesktopFileOpener : FileOpener {
    override fun open(file: Path) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw UnsupportedOperationException("Desktop.Action.OPEN is not supported")
        }
        Desktop.getDesktop().open(file.toFile())
    }
}
