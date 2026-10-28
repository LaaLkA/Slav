package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationSortColumn
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalculationListModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private lateinit var bracket: UUID
    private lateinit var support: UUID
    private lateinit var frame: UUID

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() = deps.close()

    /** «Кронштейн» 11 340,00 × 1, «Опора» 11 340,00 × 3 and the draft «Рама». */
    private fun stored() {
        bracket = deps.saveCalculated(goldenInput("Кронштейн", quantity = 1))
        support = deps.saveCalculated(goldenInput("Опора", quantity = 3))
        frame = deps.calculations.saveDraft(goldenInput("Рама").copy(parts = emptyList()))
    }

    private fun model() = CalculationListModel(deps.calculations, deps.settings)

    private val CalculationListModel.names: List<String> get() = state.value.rows.map { it.name }

    @Test
    fun `an empty database shows the empty state, not an empty table`() {
        val state = model().state.value

        assertTrue(state.isEmpty)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `rows carry the date, status, money with the currency label and the total`() {
        stored()
        val rows = model().state.value.rows.associateBy { it.name }

        val support = assertNotNull(rows["Опора"])
        assertEquals("11 340,00 ₽", support.unitPrice)
        assertEquals("3", support.quantity)
        assertEquals("34 020,00 ₽", support.total)
        assertEquals(CalculationStatus.CALCULATED, support.status)
        assertTrue(support.updatedAt.startsWith(formatDate(LocalDate.now())))
        val frame = assertNotNull(rows["Рама"])
        assertNull(frame.unitPrice)
        assertNull(frame.total)
        assertTrue(frame.isDraft)
    }

    @Test
    fun `search by an upper-case Cyrillic part of the name finds the calculation`() {
        stored()
        val list = model()

        list.onQueryChange("КРОН")

        assertEquals(listOf("Кронштейн"), list.names)
        assertFalse(list.state.value.isEmpty)
        list.onQueryChange("ра")
        assertEquals(setOf("Рама", "Опора"), list.names.toSet())
    }

    @Test
    fun `the status filter shows only the chosen statuses`() {
        stored()
        deps.calculations.setStatus(support, CalculationStatus.SENT)
        val list = model()

        list.toggleStatus(CalculationStatus.DRAFT)
        assertEquals(listOf("Рама"), list.names)

        list.toggleStatus(CalculationStatus.SENT)
        assertEquals(setOf("Рама", "Опора"), list.names.toSet())

        list.toggleStatus(CalculationStatus.DRAFT)
        list.toggleStatus(CalculationStatus.SENT)
        assertEquals(3, list.names.size, "no status chosen means every status")
    }

    @Test
    fun `the period filter takes the change date and flags a wrong date`() {
        stored()
        val list = model()
        val today = LocalDate.now()

        list.onFromChange(formatDate(today))
        assertEquals(3, list.names.size)

        list.onFromChange(formatDate(today.plusDays(1)))
        assertEquals(emptyList(), list.names)
        assertFalse(list.state.value.isEmpty, "a filter matching nothing is not an empty database")

        list.onFromChange("31.02.2026")
        assertEquals("Формат дд.мм.гггг", list.state.value.fromError)
        assertEquals(3, list.names.size, "a wrong date does not filter")

        list.onFromChange(formatDate(today))
        list.onToChange(formatDate(today.minusDays(1)))
        assertEquals("Раньше начала периода", list.state.value.toError)

        list.clearFilters()
        assertEquals("", list.state.value.fromText)
        assertEquals(3, list.names.size)
    }

    @Test
    fun `a column sorts ascending, a second click reverses it, and drafts come last by price`() {
        stored()
        val list = model()

        list.sortBy(CalculationSortColumn.NAME)
        assertEquals(listOf("Кронштейн", "Опора", "Рама"), list.names)
        list.sortBy(CalculationSortColumn.NAME)
        assertEquals(listOf("Рама", "Опора", "Кронштейн"), list.names)

        list.sortBy(CalculationSortColumn.TOTAL)
        assertEquals(listOf("Кронштейн", "Опора", "Рама"), list.names)
        list.sortBy(CalculationSortColumn.TOTAL)
        assertEquals(listOf("Опора", "Кронштейн", "Рама"), list.names)
    }

    @Test
    fun `delete asks first and removes only on confirm`() {
        stored()
        val list = model()

        list.requestDelete(bracket)
        assertEquals("Кронштейн", list.state.value.pendingDelete?.name)
        list.cancelDelete()
        assertNull(list.state.value.pendingDelete)
        assertNotNull(deps.calculations.get(bracket))

        list.requestDelete(bracket)
        list.confirmDelete()
        assertNull(deps.calculations.get(bracket))
        assertEquals(setOf("Опора", "Рама"), list.names.toSet())
    }

    @Test
    fun `a copy is a draft with the same input and no snapshot`() {
        stored()
        val list = model()

        val copy = assertNotNull(deps.calculations.get(list.copy(support)))

        assertEquals(CalculationStatus.DRAFT, copy.status)
        assertEquals(assertNotNull(deps.calculations.get(support)).input, copy.input)
        assertNull(copy.snapshot)
        assertEquals(4, list.names.size)
    }

    @Test
    fun `export writes the file and reports where`() {
        stored()
        val list = model()
        val target = temp.root.toPath().resolve("Кронштейн.pdf")

        assertEquals("Кронштейн.pdf", list.suggestedFileName(bracket, ExportFormat.PDF))
        list.export(bracket, ExportFormat.PDF, target)

        assertTrue(target.exists() && target.fileSize() > 0)
        val message = assertNotNull(list.state.value.message)
        assertFalse(message.isError)
        assertTrue(target.toString() in message.text)
    }

    @Test
    fun `an export to a folder that does not exist is reported as an error`() {
        stored()
        val list = model()
        val target = temp.root.toPath().resolve("missing").resolve("Кронштейн.xlsx")

        list.export(bracket, ExportFormat.XLSX, target)

        val message = assertNotNull(list.state.value.message)
        assertTrue(message.isError)
        assertTrue(message.text.startsWith("Не удалось сохранить файл"))
    }
}
