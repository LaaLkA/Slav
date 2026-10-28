package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The simplified calculation in the repository: every rule of the full one holds for it (a calculated
 * row is never rewritten, revisions point to their source, drawings follow the calculation), and the
 * list shows both kinds together.
 */
class QuickCalculationRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val book = PriceBook.default()
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

    private fun calculated(input: QuickCalculationInput, id: UUID? = null, prices: PriceBook = book): UUID =
        calculations.saveCalculated(input, prices, prices.quickPriced(input), id)

    private fun calculatedFull(input: CalculationInput): UUID = calculations.saveCalculated(input, book, book.priced(input))

    private fun quick(id: UUID): StoredCalculation.Quick = assertNotNull(calculations.get(id)).asQuick()

    private fun row(id: UUID) = db.database.calculationQueries.selectById(id.toString()).executeAsOne()

    private fun drawing(name: String): Path = dir.resolve("incoming").createDirectories().resolve(name).also { it.writeBytes(name.toByteArray()) }

    private fun attach(calculation: UUID, name: String): Attachment =
        assertIs<AttachResult.Added>(attachments.add(calculation, drawing(name))).attachment

    @Test
    fun `a quick draft has its kind and input, and no price, result or snapshot`() {
        val input = quickInput("Лист на раскрой")
        val id = calculations.saveDraft(input)

        val draft = quick(id)
        assertEquals(CalculationKind.QUICK, draft.kind)
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(input, draft.input)
        assertNull(draft.result)
        assertNull(draft.snapshot)
        assertNull(draft.snapshotAt)
        assertNull(draft.unitPrice)
        assertFalse(draft.isPriced)
        val summary = calculations.list().single()
        assertEquals(CalculationKind.QUICK, summary.kind)
        assertNull(summary.total)
    }

    @Test
    fun `autosaving a quick draft updates it in place`() {
        val id = calculations.saveDraft(quickInput("Лист"))
        clock.advance(Duration.ofSeconds(5))
        assertEquals(id, calculations.saveDraft(quickInput("Лист на раскрой", quantity = 4), id))

        val draft = quick(id)
        assertEquals("Лист на раскрой", draft.name)
        assertEquals(4, draft.quantity)
        assertEquals(clock.now, draft.updatedAt)
        assertEquals(1, calculations.list().size)
    }

    @Test
    fun `a calculated quick one has its price, result and the snapshot of the prices used`() {
        val input = quickInput("Лист на раскрой", quantity = 3)
        val id = calculated(input)

        val stored = quick(id)
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(book.quickPriced(input), stored.result)
        // 4 800,00 + 200,50 for the material and 600,00 + 150,00 for the cut.
        assertEquals("5750.50", stored.unitPrice?.amount?.toPlainString())
        assertTrue(stored.isPriced)
        assertEquals(book, stored.snapshot)
        assertEquals(clock.now, stored.snapshotAt)
        val summary = calculations.list().single()
        assertEquals("5750.50", summary.unitPrice?.amount?.toPlainString())
        assertEquals("17251.50", summary.total?.amount?.toPlainString())
    }

    @Test
    fun `every status but draft refuses a write over a quick one and the row stays byte for byte`() {
        val old = book.copy(laserCutPerMinute = dec("40"))
        val other = book.copy(laserCutPerMinute = dec("60"))
        val changed = quickInput("Другой", quantity = 5)
        for (status in listOf(CalculationStatus.CALCULATED, CalculationStatus.SENT, CalculationStatus.IN_WORK, CalculationStatus.ARCHIVED)) {
            val id = calculated(quickInput("Лист", quantity = 3), prices = old)
            if (status != CalculationStatus.CALCULATED) calculations.setStatus(id, status)
            val before = row(id)
            clock.advance(Duration.ofHours(1))

            assertFailsWith<IllegalStateException>("$status: saveDraft") { calculations.saveDraft(changed, id) }
            assertFailsWith<IllegalStateException>("$status: saveCalculated") {
                calculations.saveCalculated(changed, other, other.quickPriced(changed), id)
            }
            assertFailsWith<IllegalStateException>("$status: setQuantity") { calculations.setQuantity(id, 10) }

            assertEquals(before, row(id), "$status: the row changed")
        }
    }

    @Test
    fun `the status of a calculated quick one is set in place and nothing else changes`() {
        val id = calculated(quickInput("Лист", quantity = 3))
        val before = row(id)
        clock.advance(Duration.ofHours(1))

        calculations.setStatus(id, CalculationStatus.IN_WORK)

        val after = row(id)
        assertEquals(before.copy(status = CalculationStatus.IN_WORK.name, updated_at = after.updated_at), after)
        assertEquals(CalculationKind.QUICK, quick(id).kind)
    }

    @Test
    fun `the run size of a quick draft is changed in place, a calculated one is not`() {
        val draft = calculations.saveDraft(quickInput("Лист", quantity = 2))
        calculations.setQuantity(draft, 9)
        assertEquals(9, quick(draft).quantity)
        assertEquals(quickInput("Лист", quantity = 9), quick(draft).input)
        assertEquals(9, calculations.list().single().quantity)
        assertFailsWith<IllegalArgumentException> { calculations.setQuantity(draft, 0) }
    }

    @Test
    fun `a row is never written with the input of the other kind`() {
        val full = calculations.saveDraft(sheetInput("Кронштейн"))
        val quick = calculations.saveDraft(quickInput("Лист"))
        val fullBefore = row(full)
        val quickBefore = row(quick)

        assertFailsWith<IllegalStateException> { calculations.saveDraft(quickInput("Лист"), full) }
        assertFailsWith<IllegalStateException> { calculations.saveCalculated(quickInput("Лист"), book, book.quickPriced(quickInput("Лист")), full) }
        assertFailsWith<IllegalStateException> { calculations.saveDraft(sheetInput("Кронштейн"), quick) }
        assertFailsWith<IllegalStateException> {
            calculations.saveCalculated(sheetInput("Кронштейн"), book, book.priced(sheetInput("Кронштейн")), quick)
        }

        assertEquals(fullBefore, row(full))
        assertEquals(quickBefore, row(quick))
        assertEquals(CalculationKind.FULL, assertNotNull(calculations.get(full)).kind)
    }

    @Test
    fun `a revision draft carries the quick input and the snapshot of its source and points to it`() {
        val old = book.copy(laserCutPerMinute = dec("40"))
        val input = quickInput("Лист", quantity = 3)
        val source = calculated(input, prices = old)
        calculations.setStatus(source, CalculationStatus.SENT)
        val takenAt = clock.now
        val before = row(source)
        clock.advance(Duration.ofDays(2))

        val revision = calculations.createRevisionDraft(source)

        val draft = quick(revision)
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(input, draft.input)
        assertEquals(old, draft.snapshot)
        assertEquals(takenAt, draft.snapshotAt)
        assertEquals(source, draft.sourceId)
        assertNull(draft.result)
        assertEquals(before, row(source), "the source is untouched")
        assertEquals(listOf(revision), calculations.revisionsOf(source).map { it.id })
        assertEquals(CalculationKind.QUICK, calculations.revisionsOf(source).single().kind)
    }

    @Test
    fun `a revision draft is calculated in place on the snapshot of its source`() {
        val old = book.copy(laserCutPerMinute = dec("40"))
        val source = calculated(quickInput("Лист"), prices = old)
        val takenAt = clock.now
        val revision = calculations.createRevisionDraft(source)
        clock.advance(Duration.ofHours(1))

        val edited = quickInput("Лист", cutExtra = "0")
        calculations.saveCalculated(edited, old, old.quickPriced(edited), revision, snapshotAt = takenAt)

        val stored = quick(revision)
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(source, stored.sourceId)
        assertEquals(old, stored.snapshot)
        assertEquals(takenAt, stored.snapshotAt)
        assertEquals(old.quickPriced(edited), stored.result)
        assertEquals(old.quickPriced(quickInput("Лист")), quick(source).result)
    }

    @Test
    fun `a revision on current prices and one with a new run size are new rows, the source stays as it was`() {
        val source = calculated(quickInput("Лист", quantity = 1))
        calculations.setStatus(source, CalculationStatus.SENT)
        val original = quick(source)
        val before = row(source)
        clock.advance(Duration.ofDays(1))

        val current = book.copy(laserCutPerMinute = dec("80"))
        val onCurrent = calculations.createRevision(source, original.input, current, current.quickPriced(original.input))
        val resized = calculations.createRevision(
            source,
            original.input.copy(quantity = 10),
            assertNotNull(original.snapshot),
            assertNotNull(original.result),
            snapshotAt = original.snapshotAt,
        )

        assertEquals(before, row(source))
        assertEquals(current, quick(onCurrent).snapshot)
        assertEquals(clock.now, quick(onCurrent).snapshotAt)
        assertNotEquals(original.unitPrice, quick(onCurrent).unitPrice)
        assertEquals(original.result, quick(resized).result)
        assertEquals(original.snapshotAt, quick(resized).snapshotAt)
        assertEquals("57505.00", calculations.list().single { it.id == resized }.total?.amount?.toPlainString())
        assertEquals(setOf(onCurrent, resized), calculations.revisionsOf(source).map { it.id }.toSet())
    }

    @Test
    fun `a revision has the kind of its source`() {
        val full = calculatedFull(sheetInput("Кронштейн"))
        val quick = calculated(quickInput("Лист"))

        assertFailsWith<IllegalStateException> {
            calculations.createRevision(full, quickInput("Лист"), book, book.quickPriced(quickInput("Лист")))
        }
        assertFailsWith<IllegalStateException> {
            calculations.createRevision(quick, sheetInput("Кронштейн"), book, book.priced(sheetInput("Кронштейн")))
        }
        assertEquals(emptyList(), calculations.revisionsOf(full) + calculations.revisionsOf(quick))
    }

    @Test
    fun `an untouched quick revision draft is deleted, a changed one is not`() {
        val source = calculated(quickInput("Лист"))
        val untouched = calculations.createRevisionDraft(source)
        val changed = calculations.createRevisionDraft(source)
        calculations.saveDraft(quickInput("Лист", quantity = 2), changed)

        assertTrue(calculations.deleteUntouchedRevisionDraft(untouched))
        assertFalse(calculations.deleteUntouchedRevisionDraft(changed))
        assertNull(calculations.get(untouched))
        assertEquals(setOf(source, changed), calculations.list().map { it.id }.toSet())
    }

    @Test
    fun `a quick copy is a new quick draft without a snapshot`() {
        val input = quickInput("Лист", quantity = 7)
        val source = calculated(input)
        calculations.setStatus(source, CalculationStatus.SENT)

        val copy = calculations.copy(source)

        val stored = quick(copy)
        assertEquals(CalculationStatus.DRAFT, stored.status)
        assertEquals(input, stored.input)
        assertNull(stored.snapshot)
        assertNull(stored.result)
        assertNull(stored.sourceId)
        assertEquals(CalculationStatus.SENT, calculations.get(source)?.status)
    }

    @Test
    fun `drawings follow a quick calculation through copies and revisions and go with its deletion`() {
        val source = calculated(quickInput("Лист"))
        val plan = attach(source, "plan.pdf")
        val folder = paths.attachmentsDir.resolve(source.toString())
        assertTrue(folder.exists())

        val copy = calculations.copy(source)
        val revisionDraft = calculations.createRevisionDraft(source)
        val input = quick(source).input
        val revision = calculations.createRevision(source, input.copy(quantity = 2), book, book.quickPriced(input))

        for (id in listOf(copy, revisionDraft, revision)) {
            val own = attachments.list(id).single()
            assertEquals("plan.pdf", own.fileName)
            assertNotEquals(plan.id, own.id)
            assertEquals(attachments.pathOf(plan).readBytes().toList(), attachments.pathOf(own).readBytes().toList())
        }
        // A revision draft whose drawings differ from its source's is not an untouched copy.
        attach(revisionDraft, "extra.png")
        assertFalse(calculations.deleteUntouchedRevisionDraft(revisionDraft))

        calculations.delete(source)
        assertNull(calculations.get(source))
        assertEquals(emptyList(), attachments.list(source))
        assertFalse(folder.exists())
        // The copies keep their own files.
        assertEquals(1, attachments.list(copy).size)
    }

    @Test
    fun `the list shows both kinds, and search, filters and sort cover both`() {
        // Changed on the 22nd, 23rd and 24th of October, the first one last by the status set on it.
        val fullId = calculatedFull(sheetInput("Кронштейн опорный"))
        clock.advance(Duration.ofHours(1))
        calculations.setStatus(fullId, CalculationStatus.SENT)
        clock.advance(Duration.ofDays(1))
        val quickId = calculated(quickInput("Лист опорный", quantity = 10))
        clock.advance(Duration.ofDays(1))
        val quickDraft = calculations.saveDraft(quickInput("Заготовка", quantity = 2))
        val all = setOf(fullId, quickId, quickDraft)

        assertEquals(all, calculations.list().map { it.id }.toSet())
        assertEquals(
            mapOf(fullId to CalculationKind.FULL, quickId to CalculationKind.QUICK, quickDraft to CalculationKind.QUICK),
            calculations.list().associate { it.id to it.kind },
        )

        // Search matches the name of either kind, in any letter case.
        assertEquals(setOf(fullId, quickId), calculations.list(query = "ОПОРНЫЙ").map { it.id }.toSet())
        assertEquals(listOf(quickDraft), calculations.list(query = "заготов").map { it.id })

        // Statuses.
        assertEquals(setOf(quickId), calculations.list(statuses = setOf(CalculationStatus.CALCULATED)).map { it.id }.toSet())
        assertEquals(setOf(fullId, quickDraft), calculations.list(statuses = setOf(CalculationStatus.SENT, CalculationStatus.DRAFT)).map { it.id }.toSet())

        // Dates of the last change, both ends inclusive, in the zone of the clock.
        val start = LocalDate.of(2026, 10, 22)
        assertEquals(setOf(quickId), calculations.list(from = start.plusDays(1), to = start.plusDays(1)).map { it.id }.toSet())
        assertEquals(setOf(quickId, quickDraft), calculations.list(from = start.plusDays(1)).map { it.id }.toSet())
        assertEquals(setOf(fullId, quickId), calculations.list(to = start.plusDays(1)).map { it.id }.toSet())

        // Sorting: the draft has no price and comes last in either direction, the others by their own totals.
        val byTotal = calculations.list(sort = CalculationSort(CalculationSortColumn.TOTAL, descending = false)).map { it.id }
        assertEquals(listOf(fullId, quickId, quickDraft), byTotal)
        val byTotalDesc = calculations.list(sort = CalculationSort(CalculationSortColumn.TOTAL, descending = true)).map { it.id }
        assertEquals(listOf(quickId, fullId, quickDraft), byTotalDesc)
        assertEquals(
            listOf(quickDraft, quickId, fullId),
            calculations.list(sort = CalculationSort(CalculationSortColumn.UPDATED_AT, descending = true)).map { it.id },
        )
        assertEquals(
            listOf(quickDraft, fullId, quickId),
            calculations.list(sort = CalculationSort(CalculationSortColumn.NAME, descending = false)).map { it.id },
        )
    }
}
