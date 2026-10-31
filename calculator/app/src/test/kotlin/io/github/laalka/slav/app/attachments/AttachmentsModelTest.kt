package io.github.laalka.slav.app.attachments

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.RecordingOpener
import io.github.laalka.slav.app.calculations.CardModel
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.app.testScope
import io.github.laalka.slav.app.ui.formatFileSize
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val opener = RecordingOpener()
    private lateinit var deps: Deps
    private lateinit var incoming: Path

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed(), fileOpener = opener)
        incoming = temp.newFolder("incoming").toPath()
    }

    @AfterTest
    fun close() = deps.close()

    private fun drawing(name: String, size: Int = 2048): Path = incoming.resolve(name).also { it.writeBytes(ByteArray(size) { i -> i.toByte() }) }

    private fun card(id: UUID) = CardModel(
        deps.calculations,
        deps.settings,
        deps.adminPassword,
        deps.attachments,
        deps.fileOpener,
        testScope(),
        id,
        onRevision = {},
        onDeleted = {},
    )

    private fun draftCard(): CardModel = card(deps.calculations.saveDraft(goldenInput()))

    private fun calculatedCard(): CardModel = card(deps.saveCalculated())

    private fun AttachmentsModel.rows() = state.value.rows

    private fun CardModel.fileOf(row: AttachmentRow): Path =
        deps.attachments.pathOf(deps.attachments.list(id).single { it.id == row.id })

    @Test
    fun `the sizes are written in bytes, kilobytes and megabytes`() {
        assertEquals("0 Б", formatFileSize(0))
        assertEquals("1023 Б", formatFileSize(1023))
        assertEquals("1 КБ", formatFileSize(1024))
        assertEquals("1,5 КБ", formatFileSize(1536))
        assertEquals("345,7 КБ", formatFileSize(354_000))
        assertEquals("1 000 КБ", formatFileSize(1_024_000))
        assertEquals("1 МБ", formatFileSize(1024L * 1024))
        assertEquals("12,3 МБ", formatFileSize(12_900_000))
        assertEquals("50 МБ", formatFileSize(50L * 1024 * 1024))
    }

    @Test
    fun `a draft attaches, opens and removes a drawing`() {
        val card = draftCard()
        val attachments = card.attachments
        assertEquals(emptyList(), attachments.rows())

        attachments.attach(drawing("План.PDF", size = 2048))

        val row = attachments.rows().single()
        assertEquals("План.PDF", row.fileName)
        assertEquals("2 КБ", row.size)
        assertNull(attachments.state.value.message)

        attachments.open(row.id)
        assertEquals(listOf(card.fileOf(row)), opener.opened)
        assertNull(attachments.state.value.message)

        val file = card.fileOf(row)
        attachments.remove(row.id)
        assertEquals(emptyList(), attachments.rows())
        assertFalse(file.exists())
    }

    @Test
    fun `attaching to a calculated card adds the row to the same calculation, with no copy and no version`() {
        val card = calculatedCard()
        val before = assertNotNull(deps.calculations.get(card.id))
        val listBefore = deps.calculations.list()
        assertEquals(CalculationStatus.CALCULATED, before.status)

        card.attachments.attach(drawing("plan.pdf"))
        card.attachments.attach(drawing("detail.png"))

        assertEquals(listOf("plan.pdf", "detail.png"), card.attachments.rows().map { it.fileName })
        assertEquals(listOf("plan.pdf", "detail.png"), deps.attachments.list(card.id).map { it.fileName }, "the drawings belong to this calculation")
        assertEquals(before, deps.calculations.get(card.id), "the id, status, price, result and dates are as they were")
        assertEquals(listBefore, deps.calculations.list(), "no second record: no copy, no revision, no draft")
    }

    @Test
    fun `removing a drawing from a calculated card asks for nothing and leaves the calculation as it was`() {
        val card = calculatedCard()
        card.attachments.attach(drawing("plan.pdf", size = 300))
        card.attachments.attach(drawing("detail.png"))
        val before = assertNotNull(deps.calculations.get(card.id))
        val listBefore = deps.calculations.list()
        val removed = card.attachments.rows().first()
        val kept = card.attachments.rows().last()
        val file = card.fileOf(removed)
        assertTrue(file.exists())

        card.attachments.remove(removed.id)

        assertEquals(listOf(kept), card.attachments.rows())
        assertNull(card.attachments.state.value.message)
        assertFalse(file.exists())
        assertEquals(listOf("detail.png"), deps.attachments.list(card.id).map { it.fileName })
        assertEquals(before, deps.calculations.get(card.id))
        assertEquals(listBefore, deps.calculations.list())
    }

    @Test
    fun `a drawing that is not there any more is removed without a word`() {
        val card = calculatedCard()
        card.attachments.attach(drawing("plan.pdf"))
        val row = card.attachments.rows().single()

        card.attachments.remove(row.id)
        card.attachments.remove(row.id)

        assertEquals(emptyList(), card.attachments.rows())
        assertNull(card.attachments.state.value.message)
    }

    @Test
    fun `a file that is not accepted is explained and leaves the list as it was`() {
        val card = draftCard()
        card.attachments.attach(drawing("kept.png"))

        card.attachments.attach(drawing("scheme.dwg"))

        val message = assertNotNull(card.attachments.state.value.message)
        assertTrue(message.isError)
        assertTrue("scheme.dwg" in message.text && "PDF, JPG, JPEG и PNG" in message.text, message.text)
        assertEquals(listOf("kept.png"), card.attachments.rows().map { it.fileName })

        card.attachments.dismissMessage()
        assertNull(card.attachments.state.value.message)

        // The next file that is accepted also clears an old message.
        card.attachments.attach(drawing("scheme.dwg"))
        card.attachments.attach(drawing("second.jpeg"))
        assertNull(card.attachments.state.value.message)
        assertEquals(listOf("kept.png", "second.jpeg"), card.attachments.rows().map { it.fileName })
    }

    @Test
    fun `where the system cannot open files the message gives the path of the file`() {
        val card = draftCard()
        card.attachments.attach(drawing("plan.pdf"))
        val row = card.attachments.rows().single()
        opener.failure = UnsupportedOperationException()

        card.attachments.open(row.id)

        val message = assertNotNull(card.attachments.state.value.message)
        assertFalse(message.isError, "it is a hint where the file is, not a failure")
        assertTrue(card.fileOf(row).toString() in message.text, message.text)
        assertEquals(emptyList(), opener.opened)
    }

    @Test
    fun `a program that cannot be started is reported with the reason and the path`() {
        val card = draftCard()
        card.attachments.attach(drawing("plan.pdf"))
        val row = card.attachments.rows().single()
        opener.failure = IOException("no application is associated")

        card.attachments.open(row.id)

        val message = assertNotNull(card.attachments.state.value.message)
        assertTrue(message.isError)
        assertTrue("no application is associated" in message.text && card.fileOf(row).toString() in message.text, message.text)
    }

    @Test
    fun `a drawing whose file has vanished is reported instead of opened`() {
        val card = draftCard()
        card.attachments.attach(drawing("plan.pdf"))
        val row = card.attachments.rows().single()
        card.fileOf(row).deleteExisting()

        card.attachments.open(row.id)

        val message = assertNotNull(card.attachments.state.value.message)
        assertTrue(message.isError)
        assertTrue("plan.pdf" in message.text && "не найден" in message.text, message.text)
        assertEquals(emptyList(), opener.opened)
        // It can still be removed from the list.
        card.attachments.remove(row.id)
        assertEquals(emptyList(), card.attachments.rows())
    }

    @Test
    fun `a file that cannot be deleted stays in the list with a message`() {
        val card = calculatedCard()
        card.attachments.attach(drawing("plan.pdf"))
        val row = card.attachments.rows().single()
        // A folder with something in it where the file was: deleting fails the way a file held open does.
        val file = card.fileOf(row)
        file.deleteExisting()
        file.createDirectory().resolve("inside").writeText("x")

        card.attachments.remove(row.id)

        val message = assertNotNull(card.attachments.state.value.message)
        assertTrue(message.isError && "plan.pdf" in message.text, message.text)
        assertEquals(listOf(row), card.attachments.rows())
    }
}
