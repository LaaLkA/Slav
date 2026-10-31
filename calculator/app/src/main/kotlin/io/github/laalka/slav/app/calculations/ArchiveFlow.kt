package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.data.ArchiveVerificationException
import io.github.laalka.slav.data.CalculationArchive
import io.github.laalka.slav.data.CalculationRepository
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** «В архив» before the user's answer: the calculations [ids] are to be written to [target] and deleted. */
data class ArchiveRequest(val ids: List<UUID>, val target: Path)

/**
 * The question in front of «В архив»: how many calculations, the name of the one when it is one, the
 * file, and what is wrong with the password typed so far.
 */
data class ArchiveQuestion(val count: Int, val name: String, val target: String, val error: String?)

/** What came of sending calculations to an archive file. */
sealed interface ArchiveOutcome {
    /** The file is in place and [count] calculations are deleted. */
    data class Sent(val count: Int, val target: Path) : ArchiveOutcome

    /** [message] says what went wrong and what is left: it tells whether the calculations were deleted. */
    data class Failed(val message: String) : ArchiveOutcome

    fun toMessage(): ListMessage = when (this) {
        is Sent -> ListMessage("В архив отправлено расчётов: $count — $target", isError = false)
        is Failed -> ListMessage(message, isError = true)
    }
}

/** The save dialog of «В архив». */
const val ARCHIVE_DIALOG_TITLE = "Отправить в архив"

/** The extension of an archive file, with its dot. */
const val ARCHIVE_EXTENSION = ".zip"

/** What the save dialog of «В архив» offers: the file name of today in [zone]. */
fun suggestedArchiveName(zone: ZoneId): String = "slav-archive-${LocalDate.now(zone)}$ARCHIVE_EXTENSION"

/**
 * «В архив»: takes calculations out of the program into a zip file ([CalculationArchive]). The user has
 * chosen the file and [request] holds it; the administrator password is asked before anything is
 * written, since the calculations are deleted ([PasswordFlow]); a wrong one does nothing. Then
 * [send] runs: the package is written to a temporary file next to the target, opened again and
 * verified, moved into place, and only after that are the calculations deleted. Any failure before the
 * move leaves the calculations and the target as they were; [onFinished] gets the outcome.
 */
class ArchiveFlow(
    private val archive: CalculationArchive,
    private val calculations: CalculationRepository,
    adminPassword: AdminPassword,
    private val onFinished: (ArchiveOutcome) -> Unit,
) {
    private val password = PasswordFlow<ArchiveRequest>(adminPassword) { onFinished(send(it.ids, it.target)) }

    /** The question being asked, null while there is none. */
    val question: ArchiveQuestion?
        get() = password.pending?.let { ArchiveQuestion(it.ids.size, nameOf(it.ids), it.target.toString(), password.error) }

    fun request(ids: List<UUID>, target: Path) = password.request(ArchiveRequest(ids.distinct(), target))

    fun onPasswordChange(text: String) = password.onPasswordChange(text)

    fun cancel() = password.cancel()

    /** True when the password was right and [send] has run, its outcome handed to [onFinished]; a wrong one is flagged and nothing is written. */
    fun confirm(): Boolean = password.confirm()

    /** The calculations [ids] archived to [target] and deleted, without a password: the caller has verified it. */
    fun send(ids: List<UUID>, target: Path): ArchiveOutcome {
        val wanted = ids.distinct()
        archiveTo(wanted, target)?.let { return it }
        var deleted = 0
        try {
            for (id in wanted) {
                calculations.delete(id)
                deleted++
            }
        } catch (e: Exception) {
            return ArchiveOutcome.Failed(
                "Архив сохранён в $target, но расчёты удалены не все (удалено $deleted из ${wanted.size}): ${reasonOf(e)}. " +
                    "Оставшиеся расчёты можно удалить вручную.",
            )
        }
        return ArchiveOutcome.Sent(wanted.size, target)
    }

    /** Writes, verifies and moves the package; the failure when any step does not succeed, null when [target] holds the verified archive. */
    private fun archiveTo(ids: List<UUID>, target: Path): ArchiveOutcome.Failed? {
        val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.part")
        try {
            Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use { archive.write(ids, it) }
            archive.verify(temporary, ids)
            moveInPlace(temporary, target)
            return null
        } catch (e: ArchiveVerificationException) {
            return notSent("архив не прошёл проверку: ${e.message}")
        } catch (e: IOException) {
            return notSent("не удалось записать архив в $target: ${reasonOf(e)}")
        } catch (e: Exception) {
            return notSent("не удалось подготовить архив: ${reasonOf(e)}")
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: IOException) {
                // Only a stray temporary file is left beside the target.
            }
        }
    }

    private fun notSent(reason: String) = ArchiveOutcome.Failed("Не удалось отправить в архив: $reason. Ничего не удалено.")

    /** Atomic where the file system can, so a target that already exists is replaced whole or not at all. */
    private fun moveInPlace(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // A file system exception's message is the path alone, which says nothing of what happened.
    private fun reasonOf(e: Exception): String = when (e) {
        is AccessDeniedException -> "нет доступа к ${e.file}"
        is NoSuchFileException -> "не найден ${e.file}"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun nameOf(ids: List<UUID>): String =
        if (ids.size == 1) calculations.get(ids.single())?.name?.ifBlank { UNNAMED } ?: UNNAMED else ""
}

private const val UNNAMED = "Без названия"
