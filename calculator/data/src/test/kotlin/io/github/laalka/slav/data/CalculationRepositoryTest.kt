package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.PriceBook
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalculationRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val book = PriceBook.default()
    private lateinit var db: AppDatabase
    private lateinit var calculations: CalculationRepository

    @BeforeTest
    fun open() {
        db = DatabaseFactory(AppPaths(dir), clock).open()
        calculations = CalculationRepository(db, clock)
    }

    @AfterTest
    fun close() = db.close()

    private fun calculated(input: CalculationInput, id: UUID? = null, prices: PriceBook = book) =
        calculations.saveCalculated(input, prices, prices.priced(input), id)

    private fun names(list: List<CalculationSummary>) = list.map { it.name }

    /** The row as the table holds it: the JSON columns are compared as text, not as decoded values. */
    private fun row(id: UUID) = db.database.calculationQueries.selectById(id.toString()).executeAsOne()

    @Test
    fun `a draft has no price, result or snapshot`() {
        val input = sheetInput("Кронштейн")
        val id = calculations.saveDraft(input)

        val draft = assertNotNull(calculations.get(id))
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(input, draft.input)
        assertNull(draft.unitPrice)
        assertNull(draft.result)
        assertNull(draft.snapshot)
        assertNull(draft.snapshotAt)
        val row = calculations.list().single()
        assertNull(row.unitPrice)
        assertNull(row.total)
    }

    @Test
    fun `a calculated one has its price, result and the snapshot of the prices used`() {
        val input = sheetInput("Кронштейн", quantity = 3)
        val id = calculated(input)

        val stored = assertNotNull(calculations.get(id))
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(book.priced(input), stored.result)
        assertEquals(Money(dec("7950.50")), stored.unitPrice)
        assertEquals(book, stored.snapshot)
        assertEquals(clock.now, stored.snapshotAt)
        val row = calculations.list().single()
        assertEquals("7950.50", row.unitPrice?.amount?.toPlainString())
        assertEquals("23851.50", row.total?.amount?.toPlainString())
    }

    @Test
    fun `autosaving a draft updates it in place`() {
        val id = calculations.saveDraft(sheetInput("Кронш"))
        clock.advance(Duration.ofSeconds(5))
        assertEquals(id, calculations.saveDraft(sheetInput("Кронштейн"), id))

        val draft = assertNotNull(calculations.get(id))
        assertEquals("Кронштейн", draft.name)
        assertEquals(clock.now, draft.updatedAt)
        assertNotEquals(draft.createdAt, draft.updatedAt)
        assertEquals(listOf("Кронштейн"), names(calculations.list()))
    }

    @Test
    fun `every status but draft refuses a write over it and the row stays byte for byte`() {
        val old = book.copy(paintingPerM2 = dec("300"))
        val other = book.copy(paintingPerM2 = dec("400"))
        val changed = sheetInput("Другой", quantity = 5)
        for (status in listOf(CalculationStatus.CALCULATED, CalculationStatus.SENT, CalculationStatus.IN_WORK, CalculationStatus.ARCHIVED)) {
            val id = calculated(sheetInput("Кронштейн", quantity = 3), prices = old)
            if (status != CalculationStatus.CALCULATED) calculations.setStatus(id, status)
            val before = row(id)
            clock.advance(Duration.ofHours(1))

            assertFailsWith<IllegalStateException>("$status: saveDraft") { calculations.saveDraft(changed, id) }
            assertFailsWith<IllegalStateException>("$status: saveCalculated") {
                calculations.saveCalculated(changed, other, other.priced(changed), id)
            }
            assertFailsWith<IllegalStateException>("$status: setQuantity") { calculations.setQuantity(id, 10) }

            assertEquals(before, row(id), "$status: the row changed")
        }
    }

    @Test
    fun `the status of a calculated one is set in place and nothing else changes`() {
        val id = calculated(sheetInput("Кронштейн", quantity = 3))
        val before = row(id)
        clock.advance(Duration.ofHours(1))

        calculations.setStatus(id, CalculationStatus.IN_WORK)

        val after = row(id)
        assertEquals(CalculationStatus.IN_WORK.name, after.status)
        assertEquals(before.copy(status = after.status, updated_at = after.updated_at), after)
    }

    @Test
    fun `a revision draft carries the input and the snapshot of its source and points to it`() {
        val old = book.copy(paintingPerM2 = dec("300"))
        val input = sheetInput("Кронштейн", quantity = 3)
        val source = calculated(input, prices = old)
        calculations.setStatus(source, CalculationStatus.SENT)
        val takenAt = clock.now
        val before = row(source)
        clock.advance(Duration.ofDays(2))

        val revision = calculations.createRevisionDraft(source)

        val draft = assertNotNull(calculations.get(revision))
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(input, draft.input)
        assertEquals(old, draft.snapshot)
        assertEquals(takenAt, draft.snapshotAt)
        assertEquals(source, draft.sourceId)
        assertNull(draft.result)
        assertNull(draft.unitPrice)
        assertEquals(before, row(source), "the source is untouched")
        assertEquals(listOf(revision), calculations.revisionsOf(source).map { it.id })
        assertNull(assertNotNull(calculations.get(source)).sourceId)
    }

    @Test
    fun `only a calculated one has revisions`() {
        val draft = calculations.saveDraft(sheetInput("Рама"))

        assertFailsWith<IllegalStateException> { calculations.createRevisionDraft(draft) }
        assertFailsWith<NoSuchElementException> { calculations.createRevisionDraft(UUID.randomUUID()) }
        assertFailsWith<NoSuchElementException> {
            calculations.createRevision(UUID.randomUUID(), sheetInput("Рама"), book, book.priced(sheetInput("Рама")))
        }
        assertEquals(listOf(draft), calculations.list().map { it.id })
    }

    @Test
    fun `a revision draft is autosaved and calculated in place on the snapshot of its source`() {
        val old = book.copy(paintingPerM2 = dec("300"))
        val source = calculated(sheetInput("Кронштейн"), prices = old)
        val takenAt = clock.now
        val revision = calculations.createRevisionDraft(source)
        clock.advance(Duration.ofHours(1))

        calculations.saveDraft(sheetInput("Кронштейн", quantity = 2), revision)
        assertEquals(old, assertNotNull(calculations.get(revision)).snapshot, "an autosave keeps the snapshot")

        val edited = sheetInput("Кронштейн", quantity = 2, cutExtra = "0")
        calculations.saveCalculated(edited, old, old.priced(edited), revision, snapshotAt = takenAt)

        val stored = assertNotNull(calculations.get(revision))
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(source, stored.sourceId)
        assertEquals(old, stored.snapshot)
        assertEquals(takenAt, stored.snapshotAt, "the prices are the old ones, so is their date")
        assertEquals(old.priced(edited), stored.result)
        assertEquals(old.priced(sheetInput("Кронштейн")), assertNotNull(calculations.get(source)).result)
    }

    @Test
    fun `a revision on current prices is a new calculation and the source stays as it was`() {
        val source = calculated(sheetInput("Кронштейн", quantity = 3))
        calculations.setStatus(source, CalculationStatus.SENT)
        val before = row(source)
        clock.advance(Duration.ofDays(1))
        val current = book.copy(materialPricesPerKg = book.materialPricesPerKg.map { it.copy(pricePerKg = it.pricePerKg + dec("10")) })
        val input = sheetInput("Кронштейн", quantity = 3)

        val revision = calculations.createRevision(source, input, current, current.priced(input))

        assertEquals(before, row(source))
        val stored = assertNotNull(calculations.get(revision))
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(source, stored.sourceId)
        assertEquals(current, stored.snapshot)
        assertEquals(clock.now, stored.snapshotAt)
        assertEquals(current.priced(input).unitPrice, stored.unitPrice)
        assertNotEquals(assertNotNull(calculations.get(source)).unitPrice, stored.unitPrice)
        assertEquals(2, calculations.list().size)
    }

    @Test
    fun `a revision with a new run size keeps the snapshot, its date and the result`() {
        val source = calculated(sheetInput("Кронштейн", quantity = 1))
        val original = assertNotNull(calculations.get(source))
        clock.advance(Duration.ofDays(3))

        val revision = calculations.createRevision(
            source,
            original.input.copy(quantity = 10),
            assertNotNull(original.snapshot),
            assertNotNull(original.result),
            snapshotAt = original.snapshotAt,
        )

        val stored = assertNotNull(calculations.get(revision))
        assertEquals(10, stored.quantity)
        assertEquals(original.result, stored.result)
        assertEquals(original.snapshot, stored.snapshot)
        assertEquals(original.snapshotAt, stored.snapshotAt)
        assertEquals("79505.00", calculations.list().single { it.id == revision }.total?.amount?.toPlainString())
        assertEquals(1, assertNotNull(calculations.get(source)).quantity)
    }

    @Test
    fun `revisions of a calculation are listed newest first, drafts in progress included`() {
        val source = calculated(sheetInput("Кронштейн"))
        val input = sheetInput("Кронштейн")
        val first = calculations.createRevision(source, input, book, book.priced(input))
        clock.advance(Duration.ofMinutes(5))
        val draft = calculations.createRevisionDraft(source)
        clock.advance(Duration.ofMinutes(5))
        val third = calculations.createRevision(source, input, book, book.priced(input))
        calculations.createRevision(first, input, book, book.priced(input))

        assertEquals(listOf(third, draft, first), calculations.revisionsOf(source).map { it.id })
        assertEquals(1, calculations.revisionsOf(first).size)
        assertEquals(emptyList(), calculations.revisionsOf(third))
    }

    @Test
    fun `an untouched revision draft is deleted, a changed one or anything else is not`() {
        val source = calculated(sheetInput("Кронштейн"))
        val untouched = calculations.createRevisionDraft(source)
        val changed = calculations.createRevisionDraft(source)
        calculations.saveDraft(sheetInput("Кронштейн", quantity = 2), changed)
        val plain = calculations.saveDraft(sheetInput("Рама"))
        val input = sheetInput("Кронштейн")
        val revision = calculations.createRevision(source, input, book, book.priced(input))

        assertTrue(calculations.deleteUntouchedRevisionDraft(untouched))
        assertFalse(calculations.deleteUntouchedRevisionDraft(changed))
        assertFalse(calculations.deleteUntouchedRevisionDraft(plain))
        assertFalse(calculations.deleteUntouchedRevisionDraft(revision))
        assertFalse(calculations.deleteUntouchedRevisionDraft(source))
        assertFalse(calculations.deleteUntouchedRevisionDraft(untouched), "already gone")

        assertNull(calculations.get(untouched))
        assertEquals(setOf(source, changed, plain, revision), calculations.list().map { it.id }.toSet())
    }

    @Test
    fun `a revision draft whose source was deleted is kept`() {
        val source = calculated(sheetInput("Кронштейн"))
        val revision = calculations.createRevisionDraft(source)
        calculations.delete(source)

        assertFalse(calculations.deleteUntouchedRevisionDraft(revision))
        assertNotNull(calculations.get(revision))
    }

    @Test
    fun `a copy is a new draft without a snapshot, so it takes the current prices`() {
        val input = sheetInput("Кронштейн", quantity = 7)
        val source = calculated(input)
        calculations.setStatus(source, CalculationStatus.SENT)

        val copy = calculations.copy(source)

        assertNotEquals(source, copy)
        val stored = assertNotNull(calculations.get(copy))
        assertEquals(CalculationStatus.DRAFT, stored.status)
        assertEquals(input, stored.input)
        assertNull(stored.snapshot)
        assertNull(stored.result)
        assertNull(stored.sourceId)
        assertEquals(CalculationStatus.SENT, calculations.get(source)?.status)
        assertEquals(2, calculations.list().size)
    }

    @Test
    fun `search finds a Cyrillic name typed in another letter case`() {
        calculations.saveDraft(sheetInput("Кронштейн КР-12"))
        calculated(sheetInput("Рама сварная"))
        calculations.saveDraft(sheetInput("Bracket"))

        assertEquals(listOf("Кронштейн КР-12"), names(calculations.list(query = "КРОН")))
        assertEquals(listOf("Кронштейн КР-12"), names(calculations.list(query = "  кронштейн кр")))
        assertEquals(listOf("Рама сварная"), names(calculations.list(query = "СВАРН")))
        assertEquals(listOf("Bracket"), names(calculations.list(query = "bRACK")))
        assertEquals(3, calculations.list(query = "").size)
    }

    @Test
    fun `search text is literal, SQL wildcards included`() {
        calculations.saveDraft(sheetInput("Скидка 100%"))
        calculations.saveDraft(sheetInput("Рама"))

        assertEquals(listOf("Скидка 100%"), names(calculations.list(query = "%")))
        assertEquals(emptyList(), calculations.list(query = "_"))
    }

    @Test
    fun `filters by status and by the days of the last change in the local zone`() {
        fun at(instant: String) {
            clock.now = Instant.parse(instant)
        }
        at("2026-10-20T10:00:00Z")
        calculations.saveDraft(sheetInput("A")) // 20.10 in Moscow
        at("2026-10-21T22:30:00Z")
        calculated(sheetInput("B")) // 22.10 01:30 in Moscow, still 21.10 in UTC
        at("2026-10-22T20:59:00Z")
        calculations.setStatus(calculated(sheetInput("C")), CalculationStatus.SENT) // 22.10 23:59
        at("2026-10-22T21:00:00Z")
        calculations.saveDraft(sheetInput("D")) // 23.10 00:00

        val oct21 = LocalDate.of(2026, 10, 21)
        val oct22 = LocalDate.of(2026, 10, 22)
        fun listed(statuses: Set<CalculationStatus> = emptySet(), from: LocalDate? = null, to: LocalDate? = null) =
            names(calculations.list(statuses = statuses, from = from, to = to, sort = CalculationSort(CalculationSortColumn.NAME, false)))

        assertEquals(listOf("B", "C"), listed(from = oct22, to = oct22))
        assertEquals(listOf("B", "C", "D"), listed(from = oct21))
        assertEquals(listOf("A"), listed(to = oct21))
        assertEquals(listOf("A", "D"), listed(statuses = setOf(CalculationStatus.DRAFT)))
        assertEquals(listOf("B", "C"), listed(statuses = setOf(CalculationStatus.CALCULATED, CalculationStatus.SENT)))
        assertEquals(listOf("C"), listed(statuses = setOf(CalculationStatus.SENT), from = oct22, to = oct22))
    }

    @Test
    fun `sorts by price and total with drafts last in either direction`() {
        calculated(sheetInput("Дорогой", quantity = 1, cutExtra = "300")) // 8100.50, total 8100.50
        clock.advance(Duration.ofMinutes(1))
        calculations.saveDraft(sheetInput("Черновик"))
        clock.advance(Duration.ofMinutes(1))
        calculated(sheetInput("Дешёвый", quantity = 10, cutExtra = "0")) // 7800.50, total 78005.00

        fun sorted(column: CalculationSortColumn, descending: Boolean) =
            names(calculations.list(sort = CalculationSort(column, descending)))

        assertEquals(listOf("Дешёвый", "Дорогой", "Черновик"), sorted(CalculationSortColumn.UNIT_PRICE, descending = false))
        assertEquals(listOf("Дорогой", "Дешёвый", "Черновик"), sorted(CalculationSortColumn.UNIT_PRICE, descending = true))
        assertEquals(listOf("Дешёвый", "Дорогой", "Черновик"), sorted(CalculationSortColumn.TOTAL, descending = true))
        // Equal values: the most recently changed first.
        assertEquals(listOf("Черновик", "Дорогой", "Дешёвый"), sorted(CalculationSortColumn.QUANTITY, descending = false))
        assertEquals(listOf("Черновик", "Дешёвый", "Дорогой"), sorted(CalculationSortColumn.STATUS, descending = false))
    }

    @Test
    fun `sorts names in Russian alphabetical order, ignoring letter case`() {
        // Code-point order would give Ёлка, Балка, Рама, арка, яблоко.
        listOf("Рама", "яблоко", "арка", "Ёлка", "Балка").forEach { calculations.saveDraft(sheetInput(it)) }

        val ascending = names(calculations.list(sort = CalculationSort(CalculationSortColumn.NAME, descending = false)))
        assertEquals(listOf("арка", "Балка", "Ёлка", "Рама", "яблоко"), ascending)
        val descending = names(calculations.list(sort = CalculationSort(CalculationSortColumn.NAME, descending = true)))
        assertEquals(ascending.reversed(), descending)
    }

    @Test
    fun `the default order is most recently changed first`() {
        val first = calculations.saveDraft(sheetInput("Первый"))
        clock.advance(Duration.ofMinutes(1))
        calculations.saveDraft(sheetInput("Второй"))
        clock.advance(Duration.ofMinutes(1))
        calculations.saveDraft(sheetInput("Первый, правка"), first)

        assertEquals(listOf("Первый, правка", "Второй"), names(calculations.list()))
    }

    @Test
    fun `the user sets only sent, in work and archived, and only on a calculated one`() {
        val calculatedId = calculated(sheetInput("Кронштейн"))
        val draftId = calculations.saveDraft(sheetInput("Рама"))

        for (status in listOf(CalculationStatus.SENT, CalculationStatus.IN_WORK, CalculationStatus.ARCHIVED, CalculationStatus.SENT)) {
            calculations.setStatus(calculatedId, status)
            assertEquals(status, calculations.get(calculatedId)?.status)
        }
        assertFailsWith<IllegalArgumentException> { calculations.setStatus(calculatedId, CalculationStatus.CALCULATED) }
        assertFailsWith<IllegalArgumentException> { calculations.setStatus(calculatedId, CalculationStatus.DRAFT) }
        assertFailsWith<IllegalStateException> { calculations.setStatus(draftId, CalculationStatus.SENT) }
        assertEquals(CalculationStatus.DRAFT, calculations.get(draftId)?.status)
        assertFailsWith<NoSuchElementException> { calculations.setStatus(UUID.randomUUID(), CalculationStatus.SENT) }
    }

    @Test
    fun `the run size of a draft is changed in place`() {
        val id = calculations.saveDraft(sheetInput("Кронштейн", quantity = 1))

        calculations.setQuantity(id, 10)

        assertEquals(10, assertNotNull(calculations.get(id)).quantity)
        assertEquals(10, assertNotNull(calculations.get(id)).input.quantity)
        assertFailsWith<IllegalArgumentException> { calculations.setQuantity(id, 0) }
        assertFailsWith<NoSuchElementException> { calculations.setQuantity(UUID.randomUUID(), 2) }
    }

    @Test
    fun `delete removes the calculation whatever its status`() {
        val kept = calculations.saveDraft(sheetInput("Рама"))
        val deleted = calculated(sheetInput("Кронштейн"))
        calculations.setStatus(deleted, CalculationStatus.ARCHIVED)

        calculations.delete(deleted)

        assertNull(calculations.get(deleted))
        assertEquals(listOf(kept), calculations.list().map { it.id })
        calculations.delete(deleted)
        calculations.delete(kept)
        assertEquals(emptyList(), calculations.list())
    }
}
