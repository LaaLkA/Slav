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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertEquals(Money(dec("15841.00")), stored.unitPrice)
        assertEquals(book, stored.snapshot)
        assertEquals(clock.now, stored.snapshotAt)
        val row = calculations.list().single()
        assertEquals("15841.00", row.unitPrice?.amount?.toPlainString())
        assertEquals("47523.00", row.total?.amount?.toPlainString())
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
    fun `an edit saved as a draft drops the price and keeps the snapshot to recalculate on`() {
        val old = book.copy(paintingPerM2 = dec("300"))
        val id = calculated(sheetInput("Кронштейн"), prices = old)
        val takenAt = clock.now
        clock.advance(Duration.ofHours(1))

        calculations.saveDraft(sheetInput("Кронштейн", quantity = 2), id)

        val draft = assertNotNull(calculations.get(id))
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertNull(draft.result)
        assertNull(draft.unitPrice)
        assertEquals(old, draft.snapshot)
        assertEquals(takenAt, draft.snapshotAt)
        assertEquals(2, draft.quantity)
    }

    @Test
    fun `recalculating on current prices replaces the snapshot`() {
        val id = calculated(sheetInput("Кронштейн"))
        clock.advance(Duration.ofDays(1))
        val current = book.copy(materialPricesPerKg = book.materialPricesPerKg.map { it.copy(pricePerKg = it.pricePerKg + dec("10")) })

        calculated(sheetInput("Кронштейн"), id, prices = current)

        val stored = assertNotNull(calculations.get(id))
        assertEquals(current, stored.snapshot)
        assertEquals(clock.now, stored.snapshotAt)
        assertEquals(current.priced(sheetInput("Кронштейн")).unitPrice, stored.unitPrice)
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
        calculated(sheetInput("Дорогой", quantity = 1, cutExtra = "300")) // 16141.00, total 16141.00
        clock.advance(Duration.ofMinutes(1))
        calculations.saveDraft(sheetInput("Черновик"))
        clock.advance(Duration.ofMinutes(1))
        calculated(sheetInput("Дешёвый", quantity = 10, cutExtra = "0")) // 15541.00, total 155410.00

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
    fun `changing the quantity on the card changes the total and keeps the unit price and the snapshot`() {
        val id = calculated(sheetInput("Кронштейн", quantity = 1))
        val before = assertNotNull(calculations.get(id))

        calculations.setQuantity(id, 10)

        val after = assertNotNull(calculations.get(id))
        assertEquals(10, after.quantity)
        assertEquals(before.result, after.result)
        assertEquals(before.snapshot, after.snapshot)
        assertEquals("158410.00", calculations.list().single().total?.amount?.toPlainString())
        assertFailsWith<IllegalArgumentException> { calculations.setQuantity(id, 0) }
    }

    @Test
    fun `delete removes the calculation`() {
        val kept = calculations.saveDraft(sheetInput("Рама"))
        val deleted = calculated(sheetInput("Кронштейн"))

        calculations.delete(deleted)

        assertNull(calculations.get(deleted))
        assertEquals(listOf(kept), calculations.list().map { it.id })
    }
}
