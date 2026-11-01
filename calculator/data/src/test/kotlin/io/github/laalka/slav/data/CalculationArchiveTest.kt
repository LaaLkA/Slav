package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Random
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalculationArchiveTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val book = PriceBook.default()
    private val json = CalculationArchive.archiveJson
    private lateinit var db: AppDatabase
    private lateinit var attachments: AttachmentRepository
    private lateinit var calculations: CalculationRepository

    /** The calculations the archive was asked for a PDF of. */
    private val rendered = mutableListOf<UUID>()
    private lateinit var archive: CalculationArchive

    @BeforeTest
    fun open() {
        db = DatabaseFactory(AppPaths(dir.resolve("data")), clock).open()
        attachments = AttachmentRepository(db, clock)
        calculations = CalculationRepository(db, clock, attachments)
        archive = archiveWith { calculation ->
            rendered += calculation.id
            "PDF of ${calculation.name}".toByteArray()
        }
    }

    @AfterTest
    fun close() = db.close()

    private fun archiveWith(pdf: (StoredCalculation) -> ByteArray?) = CalculationArchive(calculations, attachments, pdf, appVersion = "1.2.3", clock = clock)

    private fun full(name: String = "Кронштейн", counterparty: String = "", discountPercent: String? = null): UUID {
        val input = sheetInput(name, quantity = 4, counterparty = counterparty, discountPercent = discountPercent)
        return calculations.saveCalculated(input, book, book.priced(input))
    }

    private fun quick(name: String = "Лист на раскрой"): UUID {
        val input = quickInput(name)
        return calculations.saveCalculated(input, book, book.quickPriced(input))
    }

    private fun draft(name: String = "Рама"): UUID = calculations.saveDraft(sheetInput(name))

    /** A file of [content] named [name] in its own folder, so two drawings can have one name. */
    private fun drawing(name: String, content: ByteArray = name.toByteArray(), folder: String = UUID.randomUUID().toString()): Path =
        dir.resolve("incoming").resolve(folder).createDirectories().resolve(name).also { it.writeBytes(content) }

    // The clock moves on after each file: the drawings of a calculation are listed in the order they were attached.
    private fun attach(calculation: UUID, source: Path): Attachment {
        val attachment = assertIs<AttachResult.Added>(attachments.add(calculation, source)).attachment
        clock.advance(Duration.ofSeconds(1))
        return attachment
    }

    private fun written(vararg ids: UUID): ByteArray = ByteArrayOutputStream().also { archive.write(ids.toList(), it) }.toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun folderOf(id: UUID, name: String) = "${name}__${id.toString().take(8)}"

    private fun manifestOf(entries: Map<String, ByteArray>) =
        json.decodeFromString(ArchiveManifest.serializer(), String(assertNotNull(entries["manifest.json"]), Charsets.UTF_8))

    private fun fileOf(entries: Map<String, ByteArray>, folder: String) =
        json.decodeFromString(ArchivedCalculation.serializer(), String(assertNotNull(entries["$folder/calculation.json"]), Charsets.UTF_8))

    private fun archived(vararg ids: UUID): Path {
        val file = dir.resolve("archive-${UUID.randomUUID()}.zip")
        Files.newOutputStream(file).use { archive.write(ids.toList(), it) }
        return file
    }

    @Test
    fun `a full calculation with two drawings, a quick one and a draft make a zip with a folder each`() {
        val fullId = full("Кронштейн", counterparty = "ООО Ромашка", discountPercent = "10")
        val first = drawing("чертёж.pdf", byteArrayOf(1, 2, 3, 4))
        val second = drawing("чертёж.pdf", byteArrayOf(9, 8, 7))
        attach(fullId, first)
        attach(fullId, second)
        val quickId = quick()
        val draftId = draft()

        val entries = unzip(written(fullId, quickId, draftId))

        val fullFolder = folderOf(fullId, "Кронштейн")
        val quickFolder = folderOf(quickId, "Лист на раскрой")
        val draftFolder = folderOf(draftId, "Рама")
        assertEquals(
            listOf(
                "manifest.json",
                "$fullFolder/calculation.json",
                "$fullFolder/calculation.pdf",
                "$fullFolder/drawings/чертёж.pdf",
                "$fullFolder/drawings/чертёж (2).pdf",
                "$quickFolder/calculation.json",
                "$quickFolder/calculation.pdf",
                "$draftFolder/calculation.json",
            ),
            entries.keys.toList(),
            "a draft has no price, so no PDF; the quick one has no drawings",
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4), entries["$fullFolder/drawings/чертёж.pdf"])
        assertContentEquals(byteArrayOf(9, 8, 7), entries["$fullFolder/drawings/чертёж (2).pdf"])
        assertContentEquals("PDF of Кронштейн".toByteArray(), entries["$fullFolder/calculation.pdf"])
        assertContentEquals("PDF of Лист на раскрой".toByteArray(), entries["$quickFolder/calculation.pdf"])
        assertEquals(listOf(fullId, quickId), rendered, "the PDF is asked for the priced calculations only")
    }

    @Test
    fun `the manifest has the format version, the time, the app version and an entry per calculation`() {
        val fullId = full("Кронштейн", counterparty = "ООО Ромашка")
        attach(fullId, drawing("a.pdf"))
        val quickId = quick()
        val draftId = draft()
        clock.advance(Duration.ofSeconds(90))
        val madeAt = clock.now

        val manifest = manifestOf(unzip(written(fullId, quickId, draftId)))

        assertEquals(1, manifest.formatVersion)
        assertEquals(madeAt.toString(), manifest.createdAt)
        assertEquals("1.2.3", manifest.appVersion)
        val full = calculations.get(fullId)!!
        assertEquals(
            listOf(
                ArchiveEntry(fullId.toString(), "Кронштейн", "ООО Ромашка", CalculationKind.FULL, CalculationStatus.CALCULATED, full.unitPrice!!.amount.toPlainString(), folderOf(fullId, "Кронштейн"), drawings = 1),
                ArchiveEntry(quickId.toString(), "Лист на раскрой", "", CalculationKind.QUICK, CalculationStatus.CALCULATED, calculations.get(quickId)!!.unitPrice!!.amount.toPlainString(), folderOf(quickId, "Лист на раскрой"), drawings = 0),
                ArchiveEntry(draftId.toString(), "Рама", "", CalculationKind.FULL, CalculationStatus.DRAFT, null, folderOf(draftId, "Рама"), drawings = 0),
            ),
            manifest.entries,
        )
    }

    @Test
    fun `an app version that is not given is left out of the manifest as null`() {
        val id = full()
        val out = ByteArrayOutputStream()

        CalculationArchive(calculations, attachments, { ByteArray(1) }, clock = clock).write(listOf(id), out)

        assertNull(manifestOf(unzip(out.toByteArray())).appVersion)
    }

    @Test
    fun `calculation json decodes back to the very input and result that were stored`() {
        val input = sheetInput("Кронштейн", quantity = 4, counterparty = "ООО Ромашка", discountPercent = "12.5")
        val fullId = calculations.saveCalculated(input, book, book.priced(input))
        val quickInput = quickInput("Лист", discountPercent = "5")
        val quickId = calculations.saveCalculated(quickInput, book, book.quickPriced(quickInput))
        val entries = unzip(written(fullId, quickId))

        val full = fileOf(entries, folderOf(fullId, "Кронштейн"))
        val stored = calculations.get(fullId)!!
        assertEquals(fullId.toString(), full.id)
        assertEquals(CalculationKind.FULL, full.kind)
        assertEquals(CalculationStatus.CALCULATED, full.status)
        assertEquals("Кронштейн", full.name)
        assertEquals("ООО Ромашка", full.counterparty)
        assertEquals(4, full.quantity)
        assertEquals(stored.unitPrice!!.amount.toPlainString(), full.unitPrice)
        assertEquals(stored.createdAt.toString(), full.createdAt)
        assertEquals(stored.updatedAt.toString(), full.updatedAt)
        assertEquals(stored.snapshotAt!!.toString(), full.snapshotAt)
        assertNull(full.sourceId)
        assertEquals(stored.input, json.decodeFromJsonElement(CalculationInput.serializer(), full.input))
        assertEquals(stored.result, json.decodeFromJsonElement(StoredResult.serializer(), assertNotNull(full.result)).toResult())
        assertEquals(book, json.decodeFromJsonElement(PriceBook.serializer(), assertNotNull(full.snapshot)))

        val quick = fileOf(entries, folderOf(quickId, "Лист"))
        val storedQuick = calculations.get(quickId).asQuick()
        assertEquals(CalculationKind.QUICK, quick.kind)
        assertEquals(1, quick.quantity)
        assertEquals(storedQuick.input, json.decodeFromJsonElement(QuickCalculationInput.serializer(), quick.input))
        assertEquals(storedQuick.result, json.decodeFromJsonElement(StoredQuickResult.serializer(), assertNotNull(quick.result)).toResult())
    }

    @Test
    fun `the file of a draft has no price, snapshot or result, and a revision names its source`() {
        val sourceId = full("Кронштейн")
        val draftId = draft("Рама")
        val revisionDraft = calculations.createRevisionDraft(sourceId)
        val entries = unzip(written(draftId, revisionDraft))

        val draftFile = fileOf(entries, folderOf(draftId, "Рама"))
        assertEquals(CalculationStatus.DRAFT, draftFile.status)
        assertNull(draftFile.unitPrice)
        assertNull(draftFile.snapshotAt)
        assertNull(draftFile.snapshot)
        assertNull(draftFile.result)
        assertNull(draftFile.sourceId)
        val revisionFile = fileOf(entries, folderOf(revisionDraft, calculations.get(revisionDraft)!!.name))
        assertEquals(sourceId.toString(), revisionFile.sourceId)
        assertNotNull(revisionFile.snapshot, "a revision draft keeps the prices of its source")
    }

    @Test
    fun `equal drawing names get a number before the extension, letter case aside, and none is lost`() {
        val id = full()
        val contents = listOf("a.pdf" to 1, "A.pdf" to 2, "a (2).pdf" to 3, "a.pdf" to 4, "plain.png" to 5)
        contents.forEach { (name, marker) -> attach(id, drawing(name, byteArrayOf(marker.toByte()))) }

        val entries = unzip(written(id))

        val folder = folderOf(id, "Кронштейн")
        val names = entries.keys.filter { "/drawings/" in it }.map { it.substringAfterLast('/') }
        assertEquals(listOf("a.pdf", "A (2).pdf", "a (2) (2).pdf", "a (3).pdf", "plain.png"), names, "in the order the files were attached")
        assertEquals(contents.size, names.map { it.lowercase() }.toSet().size, "no two names collide on a file system that ignores letter case")
        contents.zip(names).forEach { (content, name) ->
            assertEquals(listOf(content.second.toByte()), entries.getValue("$folder/drawings/$name").toList(), name)
        }
    }

    @Test
    fun `drawings attached within one millisecond go into the zip in the order they were attached`() {
        val id = full()
        // Not in the order of the alphabet, so a sort by name would not pass; the clock is not moved, so every drawing has one instant.
        val names = listOf("m.pdf", "c.png", "x.jpg", "a.pdf", "t.png", "f.jpeg", "z.pdf", "b.png", "q.jpg", "d.pdf", "y.png", "e.pdf")
        names.forEach { assertIs<AttachResult.Added>(attachments.add(id, drawing(it))) }

        val entries = unzip(written(id))

        val drawings = entries.keys.filter { "/drawings/" in it }.map { it.substringAfterLast('/') }
        assertEquals(names, drawings)
    }

    @Test
    fun `a drawing whose file has vanished from the disk is left out and not counted`() {
        val id = full()
        val kept = attach(id, drawing("kept.pdf"))
        val lost = attach(id, drawing("lost.pdf"))
        Files.delete(attachments.pathOf(lost))

        val entries = unzip(written(id))

        val folder = folderOf(id, "Кронштейн")
        assertTrue("$folder/drawings/${kept.fileName}" in entries)
        assertFalse("$folder/drawings/lost.pdf" in entries)
        assertEquals(1, manifestOf(entries).entries.single().drawings)
    }

    @Test
    fun `names are cleaned like the names of stored drawings, a blank name gets a stand-in, a long one is cut`() {
        val slashed = full("Кронштейн 10/20: «лист»?")
        val blank = calculations.saveDraft(sheetInput(""))
        val long = full("Д".repeat(300))
        val entries = unzip(written(slashed, blank, long))

        val folders = manifestOf(entries).entries.map { it.folder }
        assertEquals(folderOf(slashed, "Кронштейн 10_20_ «лист»_"), folders[0])
        assertEquals(folderOf(blank, "Без названия"), folders[1])
        assertTrue(folders[2].substringBefore("__").toByteArray().size <= 120, "the cleaned name is cut to the length of a stored file name")
        assertTrue(folders.all { '/' !in it && '\\' !in it && ':' !in it })
        assertTrue(entries.keys.all { it == "manifest.json" || it.substringBefore('/') in folders })
    }

    @Test
    fun `equal ids are archived once, and the stream is flushed but left open`() {
        val id = full()
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }

        archive.write(listOf(id, id), out)

        assertFalse(closed)
        assertEquals(1, manifestOf(unzip(out.toByteArray())).entries.size)
        assertEquals(listOf(id), rendered)
    }

    @Test
    fun `an unknown id writes nothing, an empty list is refused`() {
        val id = full()
        val out = ByteArrayOutputStream()

        assertFailsWith<NoSuchElementException> { archive.write(listOf(id, UUID.randomUUID()), out) }
        assertEquals(0, out.size(), "the calculations are looked up before anything is written")
        assertFailsWith<IllegalArgumentException> { archive.write(emptyList(), out) }
        assertEquals(0, out.size())
    }

    @Test
    fun `archiving only reads the calculations and their drawings`() {
        val id = full()
        val attachment = attach(id, drawing("a.pdf"))
        val before = calculations.get(id)

        written(id)

        assertEquals(before, calculations.get(id))
        assertEquals(listOf(attachment), attachments.list(id))
        assertTrue(Files.isRegularFile(attachments.pathOf(attachment)))
    }

    @Test
    fun `a priced calculation whose PDF cannot be made fails the package`() {
        val id = full("Кронштейн")
        val failing = archiveWith { null }

        val failure = assertFailsWith<IOException> { failing.write(listOf(id), ByteArrayOutputStream()) }

        assertTrue("Кронштейн" in assertNotNull(failure.message))
    }

    @Test
    fun `a stream that fails fails the write`() {
        val id = full()
        val broken = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }

        val failure = assertFailsWith<IOException> { archive.write(listOf(id), broken) }

        assertEquals("disk full", failure.message)
    }

    @Test
    fun `a written archive verifies, and its drawings can be one or none`() {
        val withDrawings = full("С чертежами")
        attach(withDrawings, drawing("a.pdf"))
        attach(withDrawings, drawing("b.png"))
        val without = quick()
        val unfinished = draft()
        val file = archived(withDrawings, without, unfinished)

        archive.verify(file, listOf(withDrawings, without, unfinished))
        archive.verify(file, listOf(unfinished, withDrawings, without, withDrawings))
    }

    @Test
    fun `verification fails for calculations the manifest does not hold, or holds besides`() {
        val first = full("Первый")
        val second = full("Второй")
        val file = archived(first)

        assertTrue(assertFailsWith<ArchiveVerificationException> { archive.verify(file, listOf(first, second)) }.message!!.contains("manifest.json"))
        assertTrue(assertFailsWith<ArchiveVerificationException> { archive.verify(archived(first, second), listOf(first)) }.message!!.contains("manifest.json"))
    }

    @Test
    fun `verification fails when the program holds drawings the archive does not`() {
        val id = full()
        attach(id, drawing("a.pdf"))
        val file = archived(id)
        attach(id, drawing("b.pdf"))

        val failure = assertFailsWith<ArchiveVerificationException> { archive.verify(file, listOf(id)) }

        assertEquals("чертежей расчёта «Кронштейн»: в архиве 1, в программе 2", failure.message)
    }

    @Test
    fun `verification fails when the archive holds a drawing the program no longer has`() {
        val id = full()
        val first = attach(id, drawing("a.pdf"))
        attach(id, drawing("b.pdf"))
        val file = archived(id)
        attachments.remove(first.id)

        val failure = assertFailsWith<ArchiveVerificationException> { archive.verify(file, listOf(id)) }

        assertEquals("чертежей расчёта «Кронштейн»: в архиве 2, в программе 1", failure.message)
    }

    @Test
    fun `verification fails without the calculation json or the PDF of a priced calculation`() {
        val id = full("Кронштейн")
        val folder = folderOf(id, "Кронштейн")
        val bytes = written(id)

        val noJson = dir.resolve("no-json.zip").also { it.writeBytes(rezipped(bytes) { name -> name != "$folder/calculation.json" }) }
        val noPdf = dir.resolve("no-pdf.zip").also { it.writeBytes(rezipped(bytes) { name -> name != "$folder/calculation.pdf" }) }

        assertEquals("в архиве нет calculation.json расчёта «Кронштейн»", assertFailsWith<ArchiveVerificationException> { archive.verify(noJson, listOf(id)) }.message)
        assertEquals("в архиве нет calculation.pdf расчёта «Кронштейн»", assertFailsWith<ArchiveVerificationException> { archive.verify(noPdf, listOf(id)) }.message)
    }

    @Test
    fun `verification fails for a PDF that came out empty, and for a calculation json of another calculation`() {
        val id = full("Кронштейн")
        val other = full("Другой")
        val empty = dir.resolve("empty-pdf.zip")
        Files.newOutputStream(empty).use { archiveWith { ByteArray(0) }.write(listOf(id), it) }
        val folder = folderOf(id, "Кронштейн")
        val swapped = dir.resolve("swapped.zip")
        val otherJson = unzip(written(other)).getValue("${folderOf(other, "Другой")}/calculation.json")
        swapped.writeBytes(rezipped(written(id), replace = mapOf("$folder/calculation.json" to otherJson)))

        assertEquals("в архиве нет calculation.pdf расчёта «Кронштейн»", assertFailsWith<ArchiveVerificationException> { archive.verify(empty, listOf(id)) }.message)
        assertEquals("calculation.json расчёта «Кронштейн» записан для другого расчёта", assertFailsWith<ArchiveVerificationException> { archive.verify(swapped, listOf(id)) }.message)
    }

    @Test
    fun `verification fails for a damaged file, a cut one and a file that is no zip`() {
        val id = full()
        // Incompressible, so the zip holds these bytes as they are and a changed one is the checksum's to find.
        val noise = ByteArray(2_000).also { Random(7).nextBytes(it) }
        attach(id, drawing("noise.png", noise))
        val bytes = written(id)
        val at = indexOf(bytes, noise.copyOfRange(100, 164))
        assertTrue(at >= 0, "the noise is stored as it is")
        val damaged = bytes.copyOf().also { it[at + 10] = (it[at + 10] + 1).toByte() }
        val cut = bytes.copyOf(bytes.size - 40)

        // The reason is the JDK's own words: a changed byte is found by the checksum of its entry, a cut file by its missing listing.
        val cases = listOf(Triple("damaged", damaged, "CRC"), Triple("cut", cut, "END header"), Triple("not a zip", "no zip here".toByteArray(), "END header"))
        for ((label, content, reason) in cases) {
            val file = dir.resolve("$label.zip").also { it.writeBytes(content) }
            val failure = assertFailsWith<ArchiveVerificationException>(label) { archive.verify(file, listOf(id)) }
            assertTrue(failure.message!!.startsWith("файл архива повреждён") && reason in failure.message!!, "$label: ${failure.message}")
        }
        archive.verify(dir.resolve("whole.zip").also { it.writeBytes(bytes) }, listOf(id))
    }

    /** [bytes] as a zip again, without the entries [keep] refuses and with the contents in [replace] put in place of the old. */
    private fun rezipped(bytes: ByteArray, replace: Map<String, ByteArray> = emptyMap(), keep: (String) -> Boolean = { true }): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in unzip(bytes)) {
                if (!keep(name)) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(replace[name] ?: content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { start -> needle.indices.all { haystack[start + it] == needle[it] } } ?: -1
}
