package io.github.laalka.slav.data

import io.github.laalka.slav.core.PriceBook
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val book = PriceBook.default()
    private val paths by lazy { AppPaths(dir.resolve("data")) }

    // Where the user's files are before they are attached; away from the data folder.
    private val incoming by lazy { dir.resolve("incoming").createDirectories() }
    private lateinit var db: AppDatabase
    private lateinit var attachments: AttachmentRepository

    // Built with the default wiring, as the program builds it: it must take the drawings along by itself.
    private lateinit var calculations: CalculationRepository

    @BeforeTest
    fun open() {
        db = DatabaseFactory(paths, clock).open()
        attachments = AttachmentRepository(db, clock)
        calculations = CalculationRepository(db, clock)
    }

    @AfterTest
    fun close() = db.close()

    private fun file(name: String, content: ByteArray = name.toByteArray()): Path = incoming.resolve(name).also { it.writeBytes(content) }

    private fun added(calculation: UUID, source: Path): Attachment =
        assertIs<AttachResult.Added>(attachments.add(calculation, source)).attachment

    private fun refusal(calculation: UUID, source: Path): String =
        assertIs<AttachResult.Refused>(attachments.add(calculation, source)).message

    private fun folder(calculation: UUID): Path = paths.attachmentsDir.resolve(calculation.toString())

    private fun bytesOf(attachment: Attachment): List<Byte> = attachments.pathOf(attachment).readBytes().toList()

    private fun calculated(name: String = "Кронштейн"): UUID = sheetInput(name).let { calculations.saveCalculated(it, book, book.priced(it)) }

    @Test
    fun `a file is copied into the folder of the calculation and listed`() {
        val calculation = UUID.randomUUID()
        val source = file("Кронштейн.pdf", byteArrayOf(1, 2, 3, 4, 5))

        val attachment = added(calculation, source)

        assertEquals("Кронштейн.pdf", attachment.fileName)
        assertEquals(5L, attachment.sizeBytes)
        assertEquals(clock.now, attachment.addedAt)
        assertEquals(calculation, attachment.calculationId)
        assertEquals(listOf(attachment), attachments.list(calculation))
        val stored = attachments.pathOf(attachment)
        assertEquals(folder(calculation).resolve("${attachment.id}-Кронштейн.pdf"), stored)
        assertContentEquals(source.readBytes(), stored.readBytes())
        assertEquals(emptyList(), attachments.list(UUID.randomUUID()))
        // The attachment is the program's own copy: the user can move or delete the original.
        source.deleteExisting()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), stored.readBytes())
    }

    @Test
    fun `the same file attached twice is two attachments`() {
        val calculation = UUID.randomUUID()
        val source = file("plan.pdf")

        val first = added(calculation, source)
        val second = added(calculation, source)

        assertNotEquals(first.id, second.id)
        assertNotEquals(attachments.pathOf(first), attachments.pathOf(second))
        assertEquals(2, attachments.list(calculation).size)
    }

    @Test
    fun `files are listed in the order they were attached`() {
        val calculation = UUID.randomUUID()
        val names = listOf("c.pdf", "a.png", "b.jpg")

        names.forEach {
            added(calculation, file(it))
            clock.advance(Duration.ofSeconds(1))
        }

        assertEquals(names, attachments.list(calculation).map { it.fileName })
    }

    @Test
    fun `pdf, jpg, jpeg and png are accepted in any letter case`() {
        val calculation = UUID.randomUUID()
        val names = listOf("a.pdf", "B.PDF", "c.jpg", "D.JPG", "e.jpeg", "F.JpEg", "g.png", "Чертёж.PNG")

        names.forEach {
            val source = file(it)
            assertNull(attachments.refusalOf(source), it)
            added(calculation, source)
        }

        assertEquals(names.sorted(), attachments.list(calculation).map { it.fileName }.sorted())
    }

    @Test
    fun `any other file is refused with a message and leaves nothing behind`() {
        val calculation = UUID.randomUUID()

        listOf("plan.dwg", "archive.zip", "noextension", "plan.pdf.exe", ".hidden", "trailing.").forEach { name ->
            val source = file(name)
            val message = refusal(calculation, source)
            assertEquals(message, attachments.refusalOf(source), "asking first gives the same reason")
            assertTrue(name in message, message)
            assertTrue("PDF, JPG, JPEG и PNG" in message, message)
        }

        assertEquals(emptyList(), attachments.list(calculation))
        assertFalse(paths.attachmentsDir.exists())
    }

    @Test
    fun `a file over 50 MB is refused and one of exactly 50 MB is accepted`() {
        val calculation = UUID.randomUUID()
        val tooBig = incoming.resolve("big.pdf").also { RandomAccessFile(it.toFile(), "rw").use { raf -> raf.setLength(AttachmentRepository.MAX_FILE_BYTES + 1) } }
        val limit = incoming.resolve("limit.pdf").also { RandomAccessFile(it.toFile(), "rw").use { raf -> raf.setLength(AttachmentRepository.MAX_FILE_BYTES) } }

        val message = refusal(calculation, tooBig)
        assertTrue("big.pdf" in message && "50 МБ" in message, message)
        assertEquals(emptyList(), attachments.list(calculation))
        assertFalse(folder(calculation).exists())

        val attachment = added(calculation, limit)
        assertEquals(AttachmentRepository.MAX_FILE_BYTES, attachment.sizeBytes)
        assertEquals(AttachmentRepository.MAX_FILE_BYTES, attachments.pathOf(attachment).toFile().length())
    }

    @Test
    fun `a file that does not exist and a folder are refused`() {
        val calculation = UUID.randomUUID()
        val folderNamedLikeAFile = incoming.resolve("drawings.pdf").createDirectory()

        assertTrue("gone.pdf" in refusal(calculation, incoming.resolve("gone.pdf")))
        assertTrue("drawings.pdf" in refusal(calculation, folderNamedLikeAFile))

        assertEquals(emptyList(), attachments.list(calculation))
    }

    @Test
    fun `a file that cannot be copied is refused and leaves no row or file`() {
        val calculation = UUID.randomUUID()
        // The calculation's folder cannot be made: a file of that name is in the way.
        paths.attachmentsDir.createDirectories()
        folder(calculation).writeText("not a folder")

        val message = refusal(calculation, file("plan.pdf"))

        assertTrue("plan.pdf" in message, message)
        assertEquals(emptyList(), attachments.list(calculation))
    }

    @Test
    fun `the name on disk holds no separators or characters a file system refuses, and stays short`() {
        val id = UUID.randomUUID()

        assertEquals("$id-a_b_c_d_e_f_g_h_i_j.pdf", AttachmentRepository.storedName(id, "a/b\\c:d*e?f\"g<h>i|j.pdf"))
        assertEquals("$id-.._.._evil.pdf", AttachmentRepository.storedName(id, "../../evil.pdf"))
        assertEquals("$id-a_b_c.png", AttachmentRepository.storedName(id, "a\u0000b\tc.png"))
        assertEquals("$id-Чертёж детали №5.PDF", AttachmentRepository.storedName(id, "  Чертёж детали №5.PDF "))

        val long = AttachmentRepository.storedName(id, "я".repeat(300) + ".pdf")
        assertEquals("$id-" + "я".repeat(58) + ".pdf", long)
        assertTrue(long.toByteArray().size <= 37 + 120)

        // 116 bytes are left for the name: «a» and 28 emoji of 4 bytes, and the 29th is not cut in half.
        assertEquals("$id-a" + "😀".repeat(28) + ".png", AttachmentRepository.storedName(id, "a" + "😀".repeat(80) + ".png"))
    }

    @Test
    fun `a file with a long name is attached under a shorter name on disk`() {
        val calculation = UUID.randomUUID()
        // 214 bytes: the longest name a file system takes, as far as the user's own file goes.
        val source = incoming.resolve("長".repeat(70) + ".pdf").also { it.writeBytes(byteArrayOf(9)) }

        val attachment = added(calculation, source)

        assertEquals(source.name, attachment.fileName)
        assertTrue(attachment.storedName.toByteArray().size <= 37 + 120, attachment.storedName)
        assertContentEquals(byteArrayOf(9), attachments.pathOf(attachment).readBytes())
    }

    @Test
    fun `removing an attachment deletes its row and its file, the others stay`() {
        val calculation = UUID.randomUUID()
        val removed = added(calculation, file("a.pdf"))
        val kept = added(calculation, file("b.pdf"))

        attachments.remove(removed.id)

        assertEquals(listOf(kept), attachments.list(calculation))
        assertFalse(attachments.pathOf(removed).exists())
        assertTrue(attachments.pathOf(kept).exists())
        // Removing it again, or an id nobody knows, changes nothing.
        attachments.remove(removed.id)
        attachments.remove(UUID.randomUUID())
        assertEquals(listOf(kept), attachments.list(calculation))
    }

    @Test
    fun `an attachment whose file has vanished from the disk can still be removed`() {
        val calculation = UUID.randomUUID()
        val attachment = added(calculation, file("a.pdf"))
        attachments.pathOf(attachment).deleteExisting()

        attachments.remove(attachment.id)

        assertEquals(emptyList(), attachments.list(calculation))
    }

    @Test
    fun `an attachment whose file cannot be deleted stays in the list`() {
        val calculation = UUID.randomUUID()
        val attachment = added(calculation, file("a.pdf"))
        // A folder with something in it where the file was: the deletion fails the way a file held open does.
        val stored = attachments.pathOf(attachment)
        stored.deleteExisting()
        stored.createDirectory().resolve("inside").writeText("x")

        assertFailsWith<IOException> { attachments.remove(attachment.id) }

        assertEquals(listOf(attachment), attachments.list(calculation))
    }

    @Test
    fun `copyAll gives the other calculation its own copies of the files`() {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val first = added(from, file("a.pdf", byteArrayOf(1, 2, 3)))
        clock.advance(Duration.ofSeconds(1))
        val second = added(from, file("B.PNG", byteArrayOf(4, 5, 6, 7)))

        attachments.copyAll(from, to)

        val copies = attachments.list(to)
        assertEquals(listOf("a.pdf", "B.PNG"), copies.map { it.fileName })
        assertEquals(listOf(3L, 4L), copies.map { it.sizeBytes })
        assertEquals(listOf(first.addedAt, second.addedAt), copies.map { it.addedAt })
        assertTrue(copies.all { it.calculationId == to })
        // The same bytes, in files of their own.
        attachments.list(from).zip(copies).forEach { (original, copy) ->
            assertNotEquals(original.id, copy.id)
            assertNotEquals(attachments.pathOf(original), attachments.pathOf(copy))
            assertEquals(folder(to), attachments.pathOf(copy).parent)
            assertEquals(bytesOf(original), bytesOf(copy))
        }
        // Own files: the source can go and the copies keep their bytes.
        attachments.removeAll(from)
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6, 7)), copies.map(::bytesOf))
        assertEquals(copies, attachments.list(to))
    }

    @Test
    fun `copyAll skips a file that has vanished and copies nothing from a calculation without files`() {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val lost = added(from, file("lost.pdf"))
        added(from, file("here.pdf", byteArrayOf(8)))
        attachments.pathOf(lost).deleteExisting()

        attachments.copyAll(from, to)
        attachments.copyAll(UUID.randomUUID(), UUID.randomUUID())

        assertEquals(listOf("here.pdf"), attachments.list(to).map { it.fileName })
    }

    @Test
    fun `copyAll that fails throws and leaves no row behind`() {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        added(from, file("a.pdf"))
        folder(to).writeText("not a folder")

        assertFailsWith<IOException> { attachments.copyAll(from, to) }

        assertEquals(emptyList(), attachments.list(to))
        assertEquals(1, attachments.list(from).size)
    }

    @Test
    fun `removeAll deletes the rows and the folder of one calculation only`() {
        val gone = UUID.randomUUID()
        val kept = UUID.randomUUID()
        added(gone, file("a.pdf"))
        added(gone, file("b.pdf"))
        val other = added(kept, file("c.pdf"))

        attachments.removeAll(gone)

        assertEquals(emptyList(), attachments.list(gone))
        assertFalse(folder(gone).exists())
        assertEquals(listOf(other), attachments.list(kept))
        assertEquals(listOf(other.storedName), folder(kept).listDirectoryEntries().map { it.name })
        // Nothing is left to remove the second time, and that is no error.
        attachments.removeAll(gone)
    }

    @Test
    fun `a copy of a calculation has copies of its drawings`() {
        val id = calculated()
        val drawing = added(id, file("a.pdf", byteArrayOf(1, 2)))

        val copy = calculations.copy(id)

        val copied = attachments.list(copy).single()
        assertEquals("a.pdf", copied.fileName)
        assertNotEquals(attachments.pathOf(drawing), attachments.pathOf(copied))
        assertEquals(bytesOf(drawing), bytesOf(copied))
        assertEquals(listOf(drawing), attachments.list(id))
    }

    @Test
    fun `a new version and a revision draft take the drawings along, the source keeps its own`() {
        val id = calculated()
        val drawing = added(id, file("a.pdf", byteArrayOf(1, 2)))
        val stored = assertNotNull(calculations.get(id))
        val snapshot = assertNotNull(stored.snapshot)
        val result = assertNotNull(stored.result)

        val revision = calculations.createRevision(id, stored.input.copy(quantity = 7), snapshot, result)
        val draft = calculations.createRevisionDraft(id)

        listOf(revision, draft).forEach { version ->
            val copied = attachments.list(version).single()
            assertEquals("a.pdf", copied.fileName)
            assertNotEquals(attachments.pathOf(drawing), attachments.pathOf(copied))
            assertEquals(bytesOf(drawing), bytesOf(copied))
        }
        assertEquals(listOf(drawing), attachments.list(id))
    }

    @Test
    fun `deleting a calculation removes its drawings and their folder, not anybody else's`() {
        val deleted = calculated("Удаляемый")
        val kept = calculated("Остающийся")
        added(deleted, file("a.pdf"))
        val other = added(kept, file("b.pdf"))

        calculations.delete(deleted)

        assertNull(calculations.get(deleted))
        assertEquals(emptyList(), attachments.list(deleted))
        assertFalse(folder(deleted).exists())
        assertEquals(listOf(other), attachments.list(kept))
        assertTrue(attachments.pathOf(other).exists())
    }

    @Test
    fun `an untouched revision draft is deleted with its drawings`() {
        val id = calculated()
        added(id, file("a.pdf"))
        val draft = calculations.createRevisionDraft(id)
        assertEquals(1, attachments.list(draft).size)

        assertTrue(calculations.deleteUntouchedRevisionDraft(draft))

        assertNull(calculations.get(draft))
        assertEquals(emptyList(), attachments.list(draft))
        assertFalse(folder(draft).exists())
        // The source keeps its drawing.
        assertEquals(1, attachments.list(id).size)
        assertTrue(attachments.pathOf(attachments.list(id).single()).exists())
    }

    @Test
    fun `a revision draft is untouched even when a file of its source has vanished from the disk`() {
        val id = calculated()
        added(id, file("here.pdf"))
        attachments.pathOf(added(id, file("lost.pdf"))).deleteExisting()
        val draft = calculations.createRevisionDraft(id)
        assertEquals(listOf("here.pdf"), attachments.list(draft).map { it.fileName }, "the missing file is not copied")

        assertTrue(calculations.deleteUntouchedRevisionDraft(draft))

        assertNull(calculations.get(draft))
    }

    @Test
    fun `a revision draft whose drawings were changed is the user's work and is kept`() {
        val id = calculated()
        added(id, file("a.pdf"))
        val withExtra = calculations.createRevisionDraft(id)
        val withoutOriginal = calculations.createRevisionDraft(id)
        clock.advance(Duration.ofMinutes(1))
        added(withExtra, file("b.png"))
        attachments.remove(attachments.list(withoutOriginal).single().id)

        assertFalse(calculations.deleteUntouchedRevisionDraft(withExtra))
        assertFalse(calculations.deleteUntouchedRevisionDraft(withoutOriginal))

        assertNotNull(calculations.get(withExtra))
        assertNotNull(calculations.get(withoutOriginal))
        assertEquals(listOf("a.pdf", "b.png"), attachments.list(withExtra).map { it.fileName })
    }
}
