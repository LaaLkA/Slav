package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.ADMIN_PASSWORD
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.app.testScope
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.AttachResult
import io.github.laalka.slav.data.CalculationArchive
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «В архив» from the list: selecting rows, the password, the file, and what is deleted when. */
class ArchiveListModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    /** The folder the archives are written to. */
    private lateinit var out: Path
    private lateinit var bracket: UUID
    private lateinit var support: UUID
    private lateinit var frame: UUID

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
        out = temp.newFolder("out").toPath()
    }

    @AfterTest
    fun close() = deps.close()

    /** «Кронштейн» and «Опора» calculated, with two drawings on the first, and the draft «Рама». */
    private fun stored() {
        bracket = deps.saveCalculated(goldenInput("Кронштейн"))
        support = deps.saveCalculated(goldenInput("Опора", quantity = 3))
        frame = deps.calculations.saveDraft(goldenInput("Рама").copy(parts = emptyList()))
        attach(bracket, "чертёж.pdf", byteArrayOf(1, 2, 3))
        attach(bracket, "узел.png", byteArrayOf(4, 5, 6, 7))
    }

    private fun attach(calculation: UUID, name: String, content: ByteArray) {
        val file = temp.newFolder().toPath().resolve(name).also { it.writeBytes(content) }
        assertIs<AttachResult.Added>(deps.attachments.add(calculation, file))
    }

    private fun model(archive: CalculationArchive = deps.archive) = CalculationListModel(deps.calculations, deps.settings, deps.adminPassword, archive)

    private fun CalculationListModel.idOf(name: String): UUID = state.value.rows.first { it.name == name }.id

    private val CalculationListModel.names: Set<String> get() = state.value.rows.map { it.name }.toSet()

    private val CalculationListModel.checked: Set<String> get() = state.value.rows.filter { it.id in state.value.selected }.map { it.name }.toSet()

    private fun CalculationListModel.select(vararg names: String) {
        startSelecting()
        names.forEach { toggleSelected(idOf(it)) }
    }

    private fun CalculationListModel.confirmWith(password: String) {
        onArchivePasswordChange(password)
        confirmArchive()
    }

    private fun entryNames(archive: Path): Set<String> = ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

    private fun remaining(): Set<UUID> = deps.calculations.list().map { it.id }.toSet()

    private fun failingArchive(reason: String = "PDF failed") =
        CalculationArchive(deps.calculations, deps.attachments, pdf = { throw IOException(reason) }, appVersion = "test")

    @Test
    fun `rows are checked one by one, all at once and back, and only while selecting`() {
        stored()
        val list = model()

        list.toggleSelected(bracket)
        assertFalse(list.state.value.selecting)
        assertTrue(list.state.value.selected.isEmpty(), "nothing is checked outside the selection")

        list.startSelecting()
        assertTrue(list.state.value.selecting)
        list.toggleSelected(bracket)
        list.toggleSelected(frame)
        assertEquals(setOf("Кронштейн", "Рама"), list.checked)
        list.toggleSelected(bracket)
        assertEquals(setOf("Рама"), list.checked)

        list.toggleSelectAll()
        assertEquals(setOf("Кронштейн", "Опора", "Рама"), list.checked)
        list.toggleSelectAll()
        assertEquals(emptySet(), list.checked, "when all are checked «Выбрать все» unchecks them")

        list.toggleSelected(support)
        list.toggleSelectAll()
        assertEquals(list.names, list.checked, "with some checked it checks the rest")

        list.stopSelecting()
        assertFalse(list.state.value.selecting)
        assertTrue(list.state.value.selected.isEmpty())
    }

    @Test
    fun `only the rows shown can be checked, and a filter unchecks the rows it hides`() {
        stored()
        val list = model()
        list.startSelecting()

        list.toggleSelectAll()
        list.onQueryChange("Кронш")
        assertEquals(setOf("Кронштейн"), list.checked, "the hidden rows are no longer checked")
        list.onQueryChange("")
        assertEquals(setOf("Кронштейн"), list.checked, "and are not checked again when they come back")

        list.onQueryChange("Опора")
        list.toggleSelected(bracket)
        assertEquals(emptySet(), list.state.value.selected, "a hidden row cannot be checked")
        list.toggleSelectAll()
        assertEquals(setOf("Опора"), list.checked, "«Выбрать все» takes the rows shown, not all of them")
    }

    @Test
    fun `a row that is deleted is no longer checked`() {
        stored()
        val list = model()
        list.select("Кронштейн", "Опора")

        list.requestDelete(support)
        list.onDeletePasswordChange(ADMIN_PASSWORD)
        list.confirmDelete()

        assertEquals(setOf("Кронштейн"), list.checked)
    }

    @Test
    fun `the save dialog offers slav-archive and today's date`() {
        assertEquals("slav-archive-${LocalDate.now()}.zip", model().suggestedArchiveName())
    }

    @Test
    fun `nothing checked asks nothing`() {
        stored()
        val list = model()
        list.startSelecting()

        list.requestArchive(out.resolve("a.zip"))

        assertNull(list.state.value.archiveQuestion)
    }

    @Test
    fun `the question names the number of calculations and the file`() {
        stored()
        val list = model()
        list.select("Кронштейн", "Опора")

        list.requestArchive(out.resolve("a.zip"))

        assertEquals(ArchiveQuestion(2, "", out.resolve("a.zip").toString(), null), list.state.value.archiveQuestion)
        list.cancelArchive()
        list.select("Опора")
        list.requestArchive(out.resolve("a.zip"))
        assertEquals("Опора", list.state.value.archiveQuestion?.name, "one calculation is asked about by name")
    }

    @Test
    fun `a wrong password deletes nothing, writes nothing and asks again`() {
        stored()
        val target = out.resolve("a.zip")
        val list = model()
        list.select("Кронштейн", "Рама")
        list.requestArchive(target)

        list.confirmWith("not-the-password")

        assertEquals(WRONG_PASSWORD, list.state.value.archiveQuestion?.error)
        assertEquals(setOf(bracket, support, frame), remaining())
        assertEquals(emptyList(), out.listDirectoryEntries(), "no archive and no temporary file")
        assertEquals(2, deps.attachments.list(bracket).size)
        assertNull(list.state.value.message)
        list.cancelArchive()
        assertNull(list.state.value.archiveQuestion)
        assertEquals(emptyList(), out.listDirectoryEntries())
        assertEquals(setOf(bracket, support, frame), remaining())
    }

    @Test
    fun `the right password writes the archive and deletes exactly the checked rows`() {
        stored()
        val target = out.resolve("a.zip")
        val list = model()
        list.select("Кронштейн", "Рама")
        list.requestArchive(target)

        list.confirmWith(ADMIN_PASSWORD)

        val folder = "Кронштейн__${bracket.toString().take(8)}"
        assertEquals(
            setOf(
                "manifest.json",
                "$folder/calculation.json",
                "$folder/calculation.pdf",
                "$folder/drawings/чертёж.pdf",
                "$folder/drawings/узел.png",
                "Рама__${frame.toString().take(8)}/calculation.json",
            ),
            entryNames(target),
        )
        assertEquals(setOf(support), remaining(), "the rows that were not checked stay")
        assertEquals(emptyList(), deps.attachments.list(bracket), "the drawings go with their calculation")
        assertEquals(listOf(target), out.listDirectoryEntries(), "no temporary file is left beside the archive")
        val state = list.state.value
        assertEquals(ListMessage("В архив отправлено расчётов: 2 — $target", isError = false), state.message)
        assertFalse(state.selecting)
        assertNull(state.archiveQuestion)
        assertEquals(setOf("Опора"), list.names)
    }

    @Test
    fun `a write failure deletes nothing and says why`() {
        stored()
        val target = out.resolve("a.zip")
        val list = model(failingArchive("PDF failed"))
        list.select("Кронштейн", "Опора")
        list.requestArchive(target)

        list.confirmWith(ADMIN_PASSWORD)

        assertEquals(setOf(bracket, support, frame), remaining())
        assertEquals(2, deps.attachments.list(bracket).size)
        assertEquals(emptyList(), out.listDirectoryEntries(), "the half-written file is removed")
        val message = assertNotNull(list.state.value.message)
        assertTrue(message.isError)
        assertEquals("Не удалось отправить в архив: не удалось записать архив в $target: PDF failed. Ничего не удалено.", message.text)
        assertEquals(setOf("Кронштейн", "Опора"), list.checked, "the selection stays for another try")
        assertTrue(list.state.value.selecting)
    }

    @Test
    fun `a target folder that cannot be written to deletes nothing`() {
        stored()
        val folder = Files.createDirectory(out.resolve("locked"))
        val list = model()
        list.select("Кронштейн")
        list.requestArchive(folder.resolve("a.zip"))
        folder.toFile().setWritable(false)
        try {
            assumeTrue("the folder is writable anyway (running as the superuser)", !Files.isWritable(folder))

            list.confirmWith(ADMIN_PASSWORD)

            assertEquals(setOf(bracket, support, frame), remaining())
            val message = assertNotNull(list.state.value.message)
            assertTrue(message.isError)
            assertTrue(message.text.startsWith("Не удалось отправить в архив: не удалось записать архив в"), message.text)
            assertTrue(message.text.endsWith("Ничего не удалено."), message.text)
        } finally {
            folder.toFile().setWritable(true)
        }
        assertEquals(emptyList(), folder.listDirectoryEntries())
    }

    @Test
    fun `a failed archive leaves a file that was already there as it was, a good one replaces it whole`() {
        stored()
        val target = out.resolve("a.zip").also { it.writeText("old archive") }

        ArchiveFlow(failingArchive(), deps.calculations, deps.adminPassword) {}.send(listOf(bracket), target)
        assertEquals("old archive", target.readText())
        assertEquals(setOf(bracket, support, frame), remaining())

        val outcome = ArchiveFlow(deps.archive, deps.calculations, deps.adminPassword) {}.send(listOf(bracket), target)
        assertEquals(ArchiveOutcome.Sent(1, target), outcome)
        assertTrue("manifest.json" in entryNames(target))
        assertEquals(listOf(target), out.listDirectoryEntries())
    }

    @Test
    fun `an archive that does not verify deletes nothing and is not moved into place`() {
        val first = deps.saveCalculated(goldenInput("Первый"))
        val second = deps.saveCalculated(goldenInput("Второй"))
        attach(first, "a.pdf", byteArrayOf(1))
        // While the second calculation is being written the first one loses the drawing the package already holds.
        val unsteady = CalculationArchive(
            deps.calculations,
            deps.attachments,
            pdf = { calculation ->
                if (calculation.id == second) deps.attachments.list(first).forEach { deps.attachments.remove(it.id) }
                "pdf".toByteArray()
            },
            appVersion = "test",
        )
        val target = out.resolve("a.zip")

        val outcome = ArchiveFlow(unsteady, deps.calculations, deps.adminPassword) {}.send(listOf(first, second), target)

        val failed = assertIs<ArchiveOutcome.Failed>(outcome)
        assertEquals(
            "Не удалось отправить в архив: архив не прошёл проверку: чертежей расчёта «Первый»: в архиве 1, в программе 0. Ничего не удалено.",
            failed.message,
        )
        assertEquals(setOf(first, second), remaining())
        assertEquals(emptyList(), out.listDirectoryEntries(), "neither the archive nor the temporary file is left")
    }

    @Test
    fun `an id that is not there fails before anything is written or deleted`() {
        stored()
        val target = out.resolve("a.zip")

        val outcome = ArchiveFlow(deps.archive, deps.calculations, deps.adminPassword) {}.send(listOf(bracket, UUID.randomUUID()), target)

        assertTrue(assertIs<ArchiveOutcome.Failed>(outcome).message.endsWith("Ничего не удалено."))
        assertEquals(setOf(bracket, support, frame), remaining())
        assertFalse(target.exists())
        assertEquals(emptyList(), out.listDirectoryEntries())
    }

    @Test
    fun `archiving a source leaves its revisions, and the card of a revision whose source is gone still opens`() {
        stored()
        val source = assertIs<StoredCalculation.Full>(deps.calculations.get(bracket))
        val revision = deps.calculations.createRevision(bracket, source.input, assertNotNull(source.snapshot), assertNotNull(source.result), source.snapshotAt)
        val list = model()
        list.select("Кронштейн")
        list.requestArchive(out.resolve("a.zip"))

        list.confirmWith(ADMIN_PASSWORD)

        assertEquals(setOf(support, frame, revision), remaining(), "only the checked source went")
        val card = CardModel(
            deps.calculations, deps.settings, deps.adminPassword, deps.attachments, deps.fileOpener, testScope(), revision,
            onRevision = {}, onDeleted = {}, onOpenEditor = { _, _ -> }, archive = deps.archive, onArchived = {},
        )
        assertNull(card.state.value.source, "the link to the archived source is not drawn")
        assertEquals("Кронштейн №2", card.state.value.name)
        assertTrue(card.state.value.isPriced)
        assertEquals(CalculationStatus.CALCULATED, card.state.value.status)
        card.reload()
        assertNull(card.state.value.source)
    }

    @Test
    fun `the outcome of a card's archive is shown above the list`() {
        stored()
        val list = model()

        list.announce(ArchiveOutcome.Sent(1, out.resolve("a.zip")))

        assertEquals(ListMessage("В архив отправлено расчётов: 1 — ${out.resolve("a.zip")}", isError = false), list.state.value.message)
    }
}
