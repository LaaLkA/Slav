package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * «Изменить без создания нового расчёта»: a calculated row is replaced in place, keeping its id, its
 * dates of creation, its source and its drawings. The password is the caller's to ask.
 */
class OverwriteCalculatedTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val book = PriceBook.default()
    private val current = book.copy(paintingPerM2 = dec("111"))
    private val paths by lazy { AppPaths(dir.resolve("data")) }
    private lateinit var db: AppDatabase
    private lateinit var attachments: AttachmentRepository
    private lateinit var calculations: CalculationRepository

    @BeforeTest
    fun open() {
        db = DatabaseFactory(paths, clock).open()
        attachments = AttachmentRepository(db, clock)
        calculations = CalculationRepository(db, clock, attachments)
    }

    @AfterTest
    fun close() = db.close()

    private fun calculated(input: CalculationInput, prices: PriceBook = book) = calculations.saveCalculated(input, prices, prices.priced(input))

    private fun calculated(input: QuickCalculationInput, prices: PriceBook = book) = calculations.saveCalculated(input, prices, prices.quickPriced(input))

    private fun overwrite(id: UUID, input: CalculationInput, snapshotAt: Instant? = null) =
        calculations.overwriteCalculated(id, input, current, current.priced(input), snapshotAt)

    private fun overwrite(id: UUID, input: QuickCalculationInput, snapshotAt: Instant? = null) =
        calculations.overwriteCalculated(id, input, current, current.quickPriced(input), snapshotAt)

    private fun row(id: UUID) = db.database.calculationQueries.selectById(id.toString()).executeAsOne()

    private fun attach(calculation: UUID, name: String): Attachment {
        val file = dir.resolve("incoming").createDirectories().resolve(name).also { it.writeBytes(name.toByteArray()) }
        return assertIs<AttachResult.Added>(attachments.add(calculation, file)).attachment
    }

    @Test
    fun `a calculated one is replaced in place and keeps its id, creation date, source and drawings`() {
        val source = calculated(sheetInput("Кронштейн", quantity = 3))
        val sourceBefore = row(source)
        val input = sheetInput("Кронштейн", quantity = 3)
        val id = calculations.createRevision(source, input, book, book.priced(input))
        val plan = attach(id, "plan.pdf")
        val created = row(id).created_at
        clock.advance(Duration.ofDays(2))
        val edited = sheetInput("Кронштейн опорный", quantity = 5, cutExtra = "0", counterparty = "ООО «Вектор»", discountPercent = "10")

        overwrite(id, edited)

        val stored = assertNotNull(calculations.get(id))
        assertEquals(edited, stored.input)
        assertEquals("Кронштейн опорный", stored.name)
        assertEquals("ООО «Вектор»", stored.counterparty)
        assertEquals(5, stored.quantity)
        assertEquals(current, stored.snapshot)
        assertEquals(clock.now, stored.snapshotAt)
        assertEquals(current.priced(edited), stored.result)
        assertEquals(current.priced(edited).unitPrice, stored.unitPrice)
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(clock.now, stored.updatedAt)
        assertEquals(created, row(id).created_at, "created_at is kept")
        assertEquals(source, stored.sourceId)
        assertEquals(listOf(plan), attachments.list(id))
        val after = row(id)
        assertEquals("кронштейн опорный", after.name_search)
        assertEquals("ооо «вектор»", after.counterparty_search)
        assertEquals(5L, after.quantity)
        assertEquals(current.priced(edited).unitPrice.amount.toPlainString(), after.unit_price)
        assertEquals(sourceBefore, row(source), "the source is untouched")
        assertEquals(2, calculations.list().size)
    }

    @Test
    fun `the status goes back to calculated, whatever the user had set`() {
        for ((index, status) in listOf(CalculationStatus.SENT, CalculationStatus.IN_WORK, CalculationStatus.ARCHIVED).withIndex()) {
            val id = calculated(sheetInput("Кронштейн $index"))
            calculations.setStatus(id, status)

            overwrite(id, sheetInput("Кронштейн $index", quantity = 2))

            assertEquals(CalculationStatus.CALCULATED, assertNotNull(calculations.get(id)).status, "from $status")
        }
    }

    @Test
    fun `the date of the prices is now, unless the caller says the prices are older`() {
        val id = calculated(sheetInput("Кронштейн"))
        clock.advance(Duration.ofDays(1))
        val older = Instant.parse("2026-10-01T08:00:00Z")

        overwrite(id, sheetInput("Кронштейн", quantity = 2), snapshotAt = older)
        assertEquals(older, assertNotNull(calculations.get(id)).snapshotAt)

        overwrite(id, sheetInput("Кронштейн", quantity = 3))
        assertEquals(clock.now, assertNotNull(calculations.get(id)).snapshotAt)
    }

    @Test
    fun `a draft, a row of the other kind and a missing row are refused and nothing is written`() {
        val draft = calculations.saveDraft(sheetInput("Черновик"))
        val full = calculated(sheetInput("Кронштейн"))
        val quick = calculated(quickInput("Лист"))
        val before = listOf(row(draft), row(full), row(quick))
        clock.advance(Duration.ofHours(1))

        assertFailsWith<IllegalStateException> { overwrite(draft, sheetInput("Черновик", quantity = 2)) }
        assertFailsWith<IllegalStateException> { overwrite(full, quickInput("Кронштейн")) }
        assertFailsWith<IllegalStateException> { overwrite(quick, sheetInput("Лист")) }
        assertFailsWith<NoSuchElementException> { overwrite(UUID.randomUUID(), sheetInput("Нет такого")) }

        assertEquals(before, listOf(row(draft), row(full), row(quick)))
    }

    @Test
    fun `a name another calculation has is refused, the own name is not, in any letter case`() {
        val id = calculated(sheetInput("Кронштейн"))
        calculated(sheetInput("Рама"))
        val before = row(id)
        clock.advance(Duration.ofHours(1))

        val failure = assertFailsWith<DuplicateNameException> { overwrite(id, sheetInput(" рАМА ", quantity = 2)) }
        assertEquals(" рАМА ", failure.name)
        assertEquals(before, row(id))

        overwrite(id, sheetInput("КРОНШТЕЙН", quantity = 2))
        assertEquals("КРОНШТЕЙН", assertNotNull(calculations.get(id)).name)
        overwrite(id, sheetInput("Кронштейн", quantity = 3))
        assertEquals(3, assertNotNull(calculations.get(id)).quantity)
    }

    @Test
    fun `a simplified calculation is replaced in place with a run size of 1`() {
        val id = calculated(quickInput("Лист"))
        val plan = attach(id, "plan.pdf")
        val created = row(id).created_at
        clock.advance(Duration.ofHours(3))
        val edited = quickInput("Лист на раскрой", quantity = 8, cutExtra = "0", counterparty = "ИП Орлов", discountPercent = "5")

        overwrite(id, edited)

        val stored = assertNotNull(calculations.get(id)).asQuick()
        assertEquals(edited.copy(quantity = 1), stored.input)
        assertEquals(1, stored.quantity)
        assertEquals(1L, row(id).quantity)
        assertEquals(current.quickPriced(edited), stored.result)
        assertEquals(current.quickPriced(edited).unitPrice, stored.unitPrice)
        assertEquals("ИП Орлов", stored.counterparty)
        assertEquals(current, stored.snapshot)
        assertEquals(created, row(id).created_at)
        assertEquals(clock.now, stored.updatedAt)
        assertEquals(listOf(plan), attachments.list(id))
    }

    @Test
    fun `a row can still be revised after it was replaced`() {
        val id = calculated(sheetInput("Кронштейн"))
        overwrite(id, sheetInput("Опора", quantity = 4))
        val stored = assertNotNull(calculations.get(id))

        val revision = calculations.createRevision(id, stored.input, current, assertNotNull(stored.result))

        assertEquals("Опора №2", assertNotNull(calculations.get(revision)).name)
        assertEquals(id, assertNotNull(calculations.get(revision)).sourceId)
    }
}
