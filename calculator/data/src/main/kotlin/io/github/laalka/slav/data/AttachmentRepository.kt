package io.github.laalka.slav.data

import io.github.laalka.slav.data.db.Attachment as AttachmentRow
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.math.max

/** A drawing attached to a calculation: a copy of the user's file kept in the data folder. */
data class Attachment(
    val id: UUID,
    val calculationId: UUID,
    /** The name the user's file had, shown as it was. */
    val fileName: String,
    /** The name of the copy on disk; see [AttachmentRepository.pathOf]. */
    val storedName: String,
    val sizeBytes: Long,
    val addedAt: Instant,
)

/** What came of [AttachmentRepository.add]. */
sealed interface AttachResult {
    data class Added(val attachment: Attachment) : AttachResult

    /** The file was not attached; [message] says why, in words for the user. */
    data class Refused(val message: String) : AttachResult
}

/**
 * Drawings attached to calculations. The files are copied into
 * [AppPaths.attachmentsDir]/<calculationId>/<attachmentId>-<cleaned name>, so the attachment
 * stays when the user's original is moved or deleted. Only PDF and JPG/JPEG/PNG files up to
 * [MAX_FILE_BYTES] are accepted. The rows and the files change together: a file that could not be
 * copied leaves no row, and a row is removed only after its file is.
 *
 * A calculation that is copied, revised or deleted takes its attachments with it; that is done by
 * [CalculationRepository], which every screen goes through, not by the screens.
 */
class AttachmentRepository(database: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val queries = database.database.attachmentQueries
    private val root = database.paths.attachmentsDir

    /**
     * Why [source] cannot be attached, in words for the user; null when it can. The extension is
     * checked on the name of [source], in any letter case. Nothing is written, so a caller can ask
     * before it stores anything for the attachment to belong to.
     */
    fun refusalOf(source: Path): String? {
        val fileName = source.name
        if (fileName.substringAfterLast('.', "").lowercase() !in ALLOWED_EXTENSIONS) {
            return "«$fileName» не прикреплён: можно прикреплять только файлы PDF, JPG, JPEG и PNG."
        }
        if (!source.isRegularFile()) return "«$fileName» не прикреплён: файл не найден."
        val size = try {
            Files.size(source)
        } catch (e: IOException) {
            return "«$fileName» не прикреплён: не удалось прочитать файл (${e.message})."
        }
        return if (size > MAX_FILE_BYTES) "«$fileName» не прикреплён: файл больше ${MAX_FILE_BYTES / MEGABYTE} МБ." else null
    }

    /** Copies [source] into the folder of [calculationId], unless [refusalOf] has a reason not to. */
    fun add(calculationId: UUID, source: Path): AttachResult {
        refusalOf(source)?.let { return AttachResult.Refused(it) }
        val fileName = source.name
        val id = UUID.randomUUID()
        val storedName = storedName(id, fileName)
        val target = folder(calculationId).resolve(storedName)
        try {
            target.parent.createDirectories()
            Files.copy(source, target)
            val attachment = Attachment(id, calculationId, fileName, storedName, Files.size(target), Instant.ofEpochMilli(clock.millis()))
            queries.insert(
                id = id.toString(),
                calculationId = calculationId.toString(),
                fileName = fileName,
                storedName = storedName,
                sizeBytes = attachment.sizeBytes,
                addedAt = attachment.addedAt.toEpochMilli(),
            )
            return AttachResult.Added(attachment)
        } catch (e: IOException) {
            discard(target)
            return AttachResult.Refused("«$fileName» не прикреплён: не удалось скопировать файл (${e.message}).")
        } catch (e: RuntimeException) {
            discard(target)
            throw e
        }
    }

    /** The files of [calculationId] in the order they were attached. */
    fun list(calculationId: UUID): List<Attachment> =
        queries.selectByCalculation(calculationId.toString()).executeAsList().map { it.toAttachment() }

    /** Where the copy of [attachment] is on disk. */
    fun pathOf(attachment: Attachment): Path = folder(attachment.calculationId).resolve(attachment.storedName)

    /**
     * A small picture of [attachment] for its row: the image itself, or the first page of a PDF,
     * at most [maxPx] on its longer side with the aspect kept, never scaled up. Null when the file
     * cannot be decoded (damaged, a PDF that asks for a password) or is not on the disk.
     *
     * The picture is kept as a PNG next to the file, in `.thumbs/<stored name>.png` of the folder
     * of the calculation, and read from there the next time; it goes with the attachment
     * ([remove], [removeAll]) and is not copied by [copyAll], the copy makes its own when it is asked.
     * Slow, decodes the file: not for the UI thread.
     */
    fun thumbnail(attachment: Attachment, maxPx: Int): BufferedImage? {
        require(maxPx > 0) { "maxPx must be positive, was $maxPx" }
        val source = pathOf(attachment)
        if (!source.isRegularFile()) return null
        val cache = thumbnailPath(attachment)
        cachedThumbnail(cache, maxPx)?.let { return it }
        val picture = Thumbnails.render(source, maxPx) ?: return null
        cacheThumbnail(cache, picture)
        return picture
    }

    /**
     * Removes the attachment [id] and its file. When the file cannot be deleted (another program
     * holds it open) an [IOException] is thrown and the attachment stays. Does nothing when [id]
     * is already gone.
     */
    fun remove(id: UUID) {
        val attachment = queries.selectById(id.toString()).executeAsOneOrNull()?.toAttachment() ?: return
        Files.deleteIfExists(pathOf(attachment))
        discard(thumbnailPath(attachment))
        queries.delete(id.toString())
    }

    /**
     * Gives [toCalculationId] its own copy of every file of [fromCalculationId], with the same names
     * and sizes, in the same order. A row whose file has vanished from the disk has nothing to copy
     * and is skipped. The thumbnails are not copied ([thumbnail] makes them again). When a copy fails,
     * none of the files copied so far is kept and the exception is thrown.
     */
    fun copyAll(fromCalculationId: UUID, toCalculationId: UUID) {
        val copied = mutableListOf<Path>()
        try {
            queries.transaction {
                for (source in list(fromCalculationId)) {
                    val sourceFile = pathOf(source)
                    if (!sourceFile.isRegularFile()) continue
                    val id = UUID.randomUUID()
                    val storedName = storedName(id, source.fileName)
                    val target = folder(toCalculationId).resolve(storedName)
                    copied.add(target)
                    target.parent.createDirectories()
                    Files.copy(sourceFile, target)
                    queries.insert(
                        id = id.toString(),
                        calculationId = toCalculationId.toString(),
                        fileName = source.fileName,
                        storedName = storedName,
                        sizeBytes = Files.size(target),
                        addedAt = source.addedAt.toEpochMilli(),
                    )
                }
            }
        } catch (e: IOException) {
            copied.forEach(::discard)
            throw e
        } catch (e: RuntimeException) {
            copied.forEach(::discard)
            throw e
        }
    }

    /**
     * Removes every attachment of [calculationId], rows and folder, thumbnails included. A file another program holds
     * open may stay on the disk; no row points to it any more, and the calculation id is never reused.
     */
    fun removeAll(calculationId: UUID) {
        queries.deleteByCalculation(calculationId.toString())
        folder(calculationId).toFile().deleteRecursively()
    }

    /**
     * Whether both calculations hold the same files by name and size, as a calculation and its copy
     * do. A row whose file has vanished from the disk is not counted: [copyAll] does not copy it.
     */
    fun hasSameFiles(first: UUID, second: UUID): Boolean = files(first) == files(second)

    private fun files(calculationId: UUID): Map<Pair<String, Long>, Int> =
        list(calculationId).filter { pathOf(it).isRegularFile() }.groupingBy { it.fileName to it.sizeBytes }.eachCount()

    private fun folder(calculationId: UUID): Path = root.resolve(calculationId.toString())

    private fun thumbnailPath(attachment: Attachment): Path =
        folder(attachment.calculationId).resolve(THUMBNAILS_FOLDER).resolve("${attachment.storedName}.png")

    /**
     * The picture saved at [cache], fitted to [maxPx]; null when there is none, it cannot be read, or
     * it is smaller than [maxPx] (made for a smaller size, or from a small file: it is made again).
     */
    private fun cachedThumbnail(cache: Path, maxPx: Int): BufferedImage? {
        if (!cache.isRegularFile()) return null
        val picture = try {
            ImageIO.read(cache.toFile())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return if (max(picture.width, picture.height) < maxPx) null else Thumbnails.scaledDown(picture, maxPx)
    }

    /** A picture that cannot be saved is still shown, it is made again next time. */
    private fun cacheThumbnail(cache: Path, picture: BufferedImage) {
        var temporary: Path? = null
        try {
            cache.parent.createDirectories()
            // Written aside and moved, so a picture asked for twice at once never leaves a half-written file.
            temporary = Files.createTempFile(cache.parent, "thumb", ".tmp")
            if (!ImageIO.write(picture, "png", temporary.toFile())) throw IOException("No PNG writer")
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            temporary?.let(::discard)
        }
    }

    private fun discard(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (_: IOException) {
            // Only an unreferenced copy is left behind.
        }
    }

    private fun AttachmentRow.toAttachment() = Attachment(
        id = UUID.fromString(id),
        calculationId = UUID.fromString(calculation_id),
        fileName = file_name,
        storedName = stored_name,
        sizeBytes = size_bytes,
        addedAt = Instant.ofEpochMilli(added_at),
    )

    companion object {
        /** 50 MB, counted in MiB as the file dialogs of the operating systems do. */
        const val MAX_FILE_BYTES = 50L * 1024 * 1024

        /** Lower case, without the dot. */
        val ALLOWED_EXTENSIONS: Set<String> = setOf("pdf", "jpg", "jpeg", "png")

        private const val MEGABYTE = 1024L * 1024

        // Inside the folder of a calculation; the stored files are named after an id, so a dot name cannot meet one.
        private const val THUMBNAILS_FOLDER = ".thumbs"

        // The cleaned name is cut to this many bytes of UTF-8, so the name on disk (the id is 37 more) is far
        // below the 255 bytes of the file systems and the whole path stays short enough for Windows.
        private const val MAX_NAME_BYTES = 120

        // What no file name may hold on Windows (the strictest of the systems), and control characters.
        private val UNSAFE_CHARACTERS = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")

        /** `<attachment id>-<name cleaned of path separators and the like, shortened to fit>`; the extension is kept. */
        internal fun storedName(id: UUID, fileName: String): String = "$id-${cleanFileName(fileName)}"

        /**
         * [fileName] with the characters no file system takes replaced by "_", outer spaces cut and, when
         * longer than [MAX_NAME_BYTES] in UTF-8, shortened before the extension, which is kept.
         */
        internal fun cleanFileName(fileName: String): String {
            val cleaned = UNSAFE_CHARACTERS.replace(fileName, "_").trim()
            if (cleaned.toByteArray().size <= MAX_NAME_BYTES) return cleaned
            val dot = cleaned.lastIndexOf('.')
            val extension = if (dot < 0) "" else cleaned.substring(dot)
            return "${cleaned.dropLast(extension.length).truncatedToBytes(MAX_NAME_BYTES - extension.toByteArray().size)}$extension"
        }

        /** The longest beginning of the text that fits [maxBytes] in UTF-8, cut between characters, never inside a surrogate pair. */
        private fun String.truncatedToBytes(maxBytes: Int): String {
            var bytes = 0
            var end = 0
            while (end < length) {
                val next = offsetByCodePoints(end, 1)
                bytes += substring(end, next).toByteArray().size
                if (bytes > maxBytes) break
                end = next
            }
            return substring(0, end)
        }
    }
}
