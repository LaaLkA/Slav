package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile

/** Thrown by [CalculationArchive.verify] when the archive file is not what was meant to be written; [message] says why, for the user. */
class ArchiveVerificationException(message: String) : IOException(message)

/**
 * The package a calculation leaves the program in: a zip with a `manifest.json` and a folder per
 * calculation, `<name>__<first 8 characters of the id>/`, holding
 *  - `calculation.json`, the stored row: the envelope keys are the columns of the calculation table, and
 *    `input`, `snapshot` and `result` are the model as the database holds it ([storageJson] and its serializers);
 *  - `calculation.pdf`, the client's copy, for a priced calculation (a draft has no price, so no PDF);
 *  - `drawings/<file name>`, the attached drawings under the names the user's files had, equal names told
 *    apart by a number before the extension.
 *
 * Names are cleaned like the names of stored drawings ([AttachmentRepository]). The manifest holds
 * [FORMAT_VERSION], the time the package was made, the version of the program when [appVersion] is given,
 * and one entry per calculation. The package does not depend on the UI: it is written to any [OutputStream],
 * so the same bytes can go to a file now and to a server later. A calculation is archived on its own;
 * the revisions of it, and the calculation it was made from, stay where they are unless asked for as well.
 *
 * [pdf] renders the client's copy of a priced calculation (the PDF export lives in the UI module); null
 * means it could not, which fails the package: a priced calculation is never archived without it.
 */
class CalculationArchive(
    private val calculations: CalculationRepository,
    private val attachments: AttachmentRepository,
    private val pdf: (StoredCalculation) -> ByteArray?,
    private val appVersion: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Writes the package of the calculations [ids] to [out], which is flushed but left open. Equal ids
     * count once, in the order they first appear. A drawing whose file has vanished from the disk is
     * left out, as copying a calculation leaves it out. Throws [NoSuchElementException] for an id with no
     * calculation, and [IOException] when a file cannot be read, a PDF cannot be made or [out] fails; the
     * calculations are only read, whatever happens. What [out] holds after a failure is not a package.
     */
    fun write(ids: List<UUID>, out: OutputStream) {
        val items = ids.distinct().map { id -> plan(calculations.get(id) ?: throw NoSuchElementException("No calculation $id")) }
        require(items.isNotEmpty()) { "There is nothing to archive" }
        val madeAt = clock.instant()
        val manifest = ArchiveManifest(FORMAT_VERSION, madeAt.toString(), appVersion, items.map { it.entry })
        val stamp = madeAt.toEpochMilli()
        val zip = ZipOutputStream(out)
        zip.put(MANIFEST, stamp, archiveJson.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray())
        for (item in items) {
            val calculation = item.calculation
            zip.put("${item.folder}/$CALCULATION_JSON", stamp, archiveJson.encodeToString(ArchivedCalculation.serializer(), calculation.archived()).toByteArray())
            if (calculation.isPriced) {
                val bytes = pdf(calculation) ?: throw IOException("Не удалось подготовить PDF расчёта «${calculation.name}»")
                zip.put("${item.folder}/$CALCULATION_PDF", stamp, bytes)
            }
            for (drawing in item.drawings) {
                zip.putNextEntry(entry("${item.folder}/$DRAWINGS/${drawing.entryName}", stamp))
                Files.copy(drawing.file, zip)
                zip.closeEntry()
            }
        }
        zip.finish()
        zip.flush()
    }

    /**
     * Opens the file [archive], written by [write] for [ids], and checks it as a whole: the zip reads to its
     * end with every checksum right and its listing agrees with its entries; the manifest has exactly these
     * calculations; each has a readable `calculation.json` of its own id, a PDF when it is priced, and as many
     * drawings as the program holds for it. Throws [ArchiveVerificationException] on the first thing that is
     * not so, and [IOException] when the file cannot be read.
     */
    fun verify(archive: Path, ids: List<UUID>) {
        val wanted = ids.distinct()
        val listing = try {
            ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        } catch (e: ZipException) {
            throw ArchiveVerificationException("файл архива повреждён (${e.message})")
        }
        val sizes = LinkedHashMap<String, Long>()
        val texts = HashMap<String, ByteArray>()
        try {
            // ZipInputStream, not ZipFile: only the stream checks the checksum of an entry it has read to the end.
            ZipInputStream(Files.newInputStream(archive).buffered()).use { zip ->
                while (true) {
                    val name = (zip.nextEntry ?: break).name
                    if (name == MANIFEST || name.endsWith("/$CALCULATION_JSON")) {
                        texts[name] = zip.readBytes().also { sizes[name] = it.size.toLong() }
                    } else {
                        sizes[name] = zip.copyTo(OutputStream.nullOutputStream())
                    }
                }
            }
        } catch (e: ZipException) {
            throw ArchiveVerificationException("файл архива повреждён (${e.message})")
        }
        if (listing != sizes.keys) throw ArchiveVerificationException("оглавление архива не совпадает с его содержимым")

        val manifest = try {
            archiveJson.decodeFromString(ArchiveManifest.serializer(), String(texts[MANIFEST] ?: throw ArchiveVerificationException("в архиве нет $MANIFEST"), Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ArchiveVerificationException("$MANIFEST не читается (${e.message})")
        }
        if (manifest.formatVersion != FORMAT_VERSION) throw ArchiveVerificationException("версия формата архива ${manifest.formatVersion}, ожидалась $FORMAT_VERSION")
        if (manifest.entries.size != wanted.size || manifest.entries.map { it.id }.toSet() != wanted.map { it.toString() }.toSet()) {
            throw ArchiveVerificationException("в $MANIFEST не те расчёты, что отправлялись в архив")
        }
        for (id in wanted) verifyCalculation(id, manifest, sizes, texts)
    }

    private fun verifyCalculation(id: UUID, manifest: ArchiveManifest, sizes: Map<String, Long>, texts: Map<String, ByteArray>) {
        val calculation = calculations.get(id) ?: throw ArchiveVerificationException("расчёта $id уже нет в программе")
        val name = "«${calculation.name.ifBlank { UNNAMED }}»"
        val entry = manifest.entries.single { it.id == id.toString() }
        val file = texts["${entry.folder}/$CALCULATION_JSON"] ?: throw ArchiveVerificationException("в архиве нет $CALCULATION_JSON расчёта $name")
        val stored = try {
            archiveJson.decodeFromString(ArchivedCalculation.serializer(), String(file, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ArchiveVerificationException("$CALCULATION_JSON расчёта $name не читается (${e.message})")
        }
        if (stored.id != id.toString()) throw ArchiveVerificationException("$CALCULATION_JSON расчёта $name записан для другого расчёта")
        if (calculation.isPriced && (sizes["${entry.folder}/$CALCULATION_PDF"] ?: 0L) == 0L) {
            throw ArchiveVerificationException("в архиве нет $CALCULATION_PDF расчёта $name")
        }
        val inArchive = sizes.keys.count { it.startsWith("${entry.folder}/$DRAWINGS/") }
        val inProgram = drawingsOf(id).size
        if (inArchive != inProgram || entry.drawings != inProgram) {
            throw ArchiveVerificationException("чертежей расчёта $name: в архиве $inArchive, в программе $inProgram")
        }
    }

    private fun plan(calculation: StoredCalculation): Planned {
        val taken = HashSet<String>()
        val drawings = drawingsOf(calculation.id).map { Drawing(attachments.pathOf(it), uniqueName(AttachmentRepository.cleanFileName(it.fileName), taken)) }
        val folder = "${AttachmentRepository.cleanFileName(calculation.name).ifEmpty { UNNAMED }}__${calculation.id.toString().take(ID_CHARACTERS)}"
        val entry = ArchiveEntry(
            id = calculation.id.toString(),
            name = calculation.name,
            counterparty = calculation.counterparty,
            kind = calculation.kind,
            status = calculation.status,
            unitPrice = calculation.unitPrice?.amount?.toPlainString(),
            folder = folder,
            drawings = drawings.size,
        )
        return Planned(calculation, folder, drawings, entry)
    }

    /** The drawings of [id] the archive can hold: the ones whose files are still on the disk. */
    private fun drawingsOf(id: UUID): List<Attachment> = attachments.list(id).filter { attachments.pathOf(it).isRegularFile() }

    private class Planned(val calculation: StoredCalculation, val folder: String, val drawings: List<Drawing>, val entry: ArchiveEntry)

    private class Drawing(val file: Path, val entryName: String)

    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"
        const val CALCULATION_JSON = "calculation.json"
        const val CALCULATION_PDF = "calculation.pdf"
        const val DRAWINGS = "drawings"

        private const val ID_CHARACTERS = 8
        private const val UNNAMED = "Без названия"

        // Readable by a person and complete for a program that does not know the defaults of the model.
        internal val archiveJson: Json = Json(storageJson) {
            prettyPrint = true
            encodeDefaults = true
        }

        private fun ZipOutputStream.put(name: String, stamp: Long, bytes: ByteArray) {
            putNextEntry(entry(name, stamp))
            write(bytes)
            closeEntry()
        }

        private fun entry(name: String, stamp: Long) = ZipEntry(name).also { it.time = stamp }

        /** [name] if free, else "<base> (2).<ext>", "<base> (3).<ext>"…; names are told apart without regard to letter case, as a file system may. */
        private fun uniqueName(name: String, taken: MutableSet<String>): String {
            var candidate = name
            var number = 2
            while (!taken.add(candidate.lowercase())) {
                val dot = name.lastIndexOf('.')
                candidate = if (dot > 0) "${name.substring(0, dot)} ($number)${name.substring(dot)}" else "$name ($number)"
                number++
            }
            return candidate
        }

        private fun StoredCalculation.archived(): ArchivedCalculation {
            val (inputJson, resultJson) = when (this) {
                is StoredCalculation.Full ->
                    archiveJson.encodeToJsonElement(CalculationInput.serializer(), input) to
                        result?.let { archiveJson.encodeToJsonElement(StoredResult.serializer(), StoredResult.of(it)) }
                is StoredCalculation.Quick ->
                    archiveJson.encodeToJsonElement(QuickCalculationInput.serializer(), input) to
                        result?.let { archiveJson.encodeToJsonElement(StoredQuickResult.serializer(), StoredQuickResult.of(it)) }
            }
            return ArchivedCalculation(
                id = id.toString(),
                kind = kind,
                status = status,
                name = name,
                counterparty = counterparty,
                quantity = quantity,
                unitPrice = unitPrice?.amount?.toPlainString(),
                createdAt = createdAt.toString(),
                updatedAt = updatedAt.toString(),
                snapshotAt = snapshotAt?.toString(),
                sourceId = sourceId?.toString(),
                input = inputJson,
                snapshot = snapshot?.let { archiveJson.encodeToJsonElement(PriceBook.serializer(), it) },
                result = resultJson,
            )
        }
    }
}

/** `manifest.json`: what the package holds, one [ArchiveEntry] per calculation. */
@Serializable
internal data class ArchiveManifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("app_version") val appVersion: String? = null,
    val entries: List<ArchiveEntry>,
)

/** [folder] is where the calculation lies in the zip, [drawings] how many files its `drawings` folder holds. */
@Serializable
internal data class ArchiveEntry(
    val id: String,
    val name: String,
    val counterparty: String,
    val kind: CalculationKind,
    val status: CalculationStatus,
    @SerialName("unit_price") val unitPrice: String?,
    val folder: String,
    val drawings: Int,
)

/** `calculation.json`: the stored row of a calculation; the instants are ISO-8601, a draft has no price, snapshot or result. */
@Serializable
internal data class ArchivedCalculation(
    val id: String,
    val kind: CalculationKind,
    val status: CalculationStatus,
    val name: String,
    val counterparty: String,
    val quantity: Int,
    @SerialName("unit_price") val unitPrice: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("snapshot_at") val snapshotAt: String?,
    @SerialName("source_id") val sourceId: String?,
    val input: JsonElement,
    val snapshot: JsonElement?,
    val result: JsonElement?,
)
