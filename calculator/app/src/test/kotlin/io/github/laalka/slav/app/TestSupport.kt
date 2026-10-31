package io.github.laalka.slav.app

import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.data.PasswordSeed
import io.github.laalka.slav.data.StoredCalculation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The administrator password of the tests; the one of the program is not in the repository. */
const val ADMIN_PASSWORD = "test-admin-pass"

/** The seed of [ADMIN_PASSWORD], with few rounds so the tests stay quick. */
fun testAdminSeed(): PasswordSeed = PasswordSeed.of(ADMIN_PASSWORD.toCharArray(), ByteArray(16) { 3 }, iterations = 1_000)

/**
 * A scope for a model whose background work (the thumbnails of the drawings) the test does not look
 * at. It runs on the thread that launches, so the test is not waiting for a dispatcher.
 */
fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

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

// The tests that look inside a stored calculation mostly deal with full ones: they read it as such,
// and a quick one makes them fail with a class cast rather than with a wrong value.
val StoredCalculation.input: CalculationInput get() = (this as StoredCalculation.Full).input

val StoredCalculation.result: CalcResult? get() = (this as StoredCalculation.Full).result

fun StoredCalculation?.asQuick(): StoredCalculation.Quick = this as StoredCalculation.Quick

/**
 * A clock that lets a test act at the moment a repository stamps a write, which is after an editor's
 * own checks have passed and before the write itself: the way another writer gets in between.
 */
class InterferingClock : Clock() {
    /** Run once, at the next reading of the clock. */
    var before: (() -> Unit)? = null

    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant {
        before?.let { action ->
            before = null
            action()
        }
        return Instant.now()
    }
}
