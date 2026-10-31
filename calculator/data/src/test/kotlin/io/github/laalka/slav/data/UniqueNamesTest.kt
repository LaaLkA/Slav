package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A calculation name is unique among all calculations of both kinds, compared by its search form;
 * a copy and a revision are named by the program with the smallest free number.
 */
class UniqueNamesTest {
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

    private fun calculated(input: CalculationInput, id: UUID? = null) = calculations.saveCalculated(input, book, book.priced(input), id)

    private fun calculated(input: QuickCalculationInput, id: UUID? = null) = calculations.saveCalculated(input, book, book.quickPriced(input), id)

    private fun revision(source: UUID, input: CalculationInput) = calculations.createRevision(source, input, book, book.priced(input))

    private fun row(id: UUID) = db.database.calculationQueries.selectById(id.toString()).executeAsOne()

    private fun nameOf(id: UUID) = assertNotNull(calculations.get(id)).name

    /** What a database of a program without the rule holds: the same name on two rows, written around the repository. */
    private fun insertLegacy(name: String): UUID {
        val id = UUID.randomUUID()
        db.database.calculationQueries.insert(
            id = id.toString(),
            name = name,
            nameSearch = name.lowercase(),
            counterparty = "",
            counterpartySearch = "",
            status = CalculationStatus.DRAFT.name,
            quantity = 1,
            unitPrice = null,
            createdAt = clock.millis(),
            updatedAt = clock.millis(),
            snapshotAt = null,
            inputJson = storageJson.encodeToString(CalculationInput.serializer(), sheetInput(name)),
            snapshotJson = null,
            resultJson = null,
            sourceId = null,
            kind = CalculationKind.FULL.name,
        )
        return id
    }

    @Test
    fun `a new draft cannot take a name that is used, in any letter case and with outer spaces`() {
        val first = calculations.saveDraft(sheetInput("Кронштейн"))

        for (same in listOf("Кронштейн", "КРОНШТЕЙН", "  кронштейн ", "кРоНшТеЙн")) {
            val failure = assertFailsWith<DuplicateNameException>(same) { calculations.saveDraft(sheetInput(same)) }
            assertEquals(same, failure.name)
        }

        assertEquals(listOf(first), calculations.list().map { it.id })
    }

    @Test
    fun `a calculated one cannot take a used name either, new or over a draft, and a refused write leaves the row as it was`() {
        calculated(sheetInput("Кронштейн"))
        assertFailsWith<DuplicateNameException> { calculated(sheetInput("кронштейн")) }
        val draft = calculations.saveDraft(sheetInput("Рама"))
        val before = row(draft)
        clock.advance(Duration.ofHours(1))

        assertFailsWith<DuplicateNameException> { calculations.saveDraft(sheetInput("Кронштейн ", quantity = 4), draft) }
        assertFailsWith<DuplicateNameException> { calculated(sheetInput("КРОНШТЕЙН"), draft) }

        assertEquals(before, row(draft))
        assertEquals(2, calculations.list().size)
    }

    @Test
    fun `a calculation may be written again under its own name, in another letter case too`() {
        val id = calculations.saveDraft(sheetInput("Кронштейн"))

        calculations.saveDraft(sheetInput("Кронштейн", quantity = 2), id)
        calculations.saveDraft(sheetInput("КРОНШТЕЙН"), id)
        assertEquals("КРОНШТЕЙН", nameOf(id))
        calculated(sheetInput("Кронштейн", quantity = 3), id)

        assertEquals("Кронштейн", nameOf(id))
        assertEquals(1, calculations.list().size)
    }

    @Test
    fun `the name is unique across both kinds`() {
        calculated(quickInput("Лист"))
        val full = calculations.saveDraft(sheetInput("Кронштейн"))

        assertFailsWith<DuplicateNameException> { calculations.saveDraft(sheetInput("лист")) }
        assertFailsWith<DuplicateNameException> { calculations.saveDraft(quickInput("КРОНШТЕЙН")) }
        assertFailsWith<DuplicateNameException> { calculated(quickInput("Кронштейн ")) }

        assertEquals(2, calculations.list().size)
        assertEquals(CalculationKind.FULL, assertNotNull(calculations.get(full)).kind)
    }

    @Test
    fun `a blank name is no name, so any number of drafts may have it`() {
        val a = calculations.saveDraft(sheetInput(""))
        val b = calculations.saveDraft(sheetInput("  "))
        val c = calculations.saveDraft(quickInput(""))
        calculations.saveDraft(sheetInput("", quantity = 2), a)

        assertEquals(setOf(a, b, c), calculations.list().map { it.id }.toSet())
        assertFalse(calculations.isNameTaken("", null))
        assertFalse(calculations.isNameTaken("   ", null))
    }

    @Test
    fun `isNameTaken answers the live check of the editor`() {
        val id = calculated(sheetInput("Кронштейн"))

        assertTrue(calculations.isNameTaken("Кронштейн", null))
        assertTrue(calculations.isNameTaken("кронштейн", null))
        assertTrue(calculations.isNameTaken("  КРОНШТЕЙН ", UUID.randomUUID()))
        // The calculation's own name is not taken from it.
        assertFalse(calculations.isNameTaken("Кронштейн", id))
        assertFalse(calculations.isNameTaken("кронштейн", id))
        // Only the whole name counts, not a part of it.
        assertFalse(calculations.isNameTaken("Кронш", null))
        assertFalse(calculations.isNameTaken("Кронштейн 2", null))
        assertFalse(calculations.isNameTaken("Рама", null))
    }

    @Test
    fun `names doubled before the rule are left as they are, and writing one of them needs a name of its own`() {
        val a = insertLegacy("Рама")
        val b = insertLegacy("Рама")

        assertEquals(listOf("Рама", "Рама"), calculations.list().map { it.name })
        assertFailsWith<DuplicateNameException> { calculations.saveDraft(sheetInput("рама")) }
        assertFailsWith<DuplicateNameException> { calculations.saveDraft(sheetInput("Рама", quantity = 2), a) }

        calculations.saveDraft(sheetInput("Рама стальная", quantity = 2), a)
        // The other one is the only «Рама» now.
        calculations.saveDraft(sheetInput("Рама", quantity = 3), b)
        assertEquals(setOf("Рама", "Рама стальная"), calculations.list().map { it.name }.toSet())
    }

    @Test
    fun `copies are numbered from 2 and a number is reused once it is free`() {
        val source = calculated(sheetInput("Кронштейн", quantity = 7))

        val second = calculations.copy(source)
        val third = calculations.copy(source)
        assertEquals("Кронштейн (2)", nameOf(second))
        assertEquals("Кронштейн (3)", nameOf(third))
        // A copy of a copy goes on from the base: (2) and (3) are taken, so it is (4).
        val fourth = calculations.copy(second)
        assertEquals("Кронштейн (4)", nameOf(fourth))

        calculations.delete(third)
        assertEquals("Кронштейн (3)", nameOf(calculations.copy(fourth)))
        assertEquals(setOf("Кронштейн", "Кронштейн (2)", "Кронштейн (3)", "Кронштейн (4)"), calculations.list().map { it.name }.toSet())
    }

    @Test
    fun `a copy is named in its input as well, and keeps the rest of the input`() {
        val input = sheetInput("Кронштейн", quantity = 7, counterparty = "ООО «Вектор»", discountPercent = "10")
        val source = calculated(input)

        val copy = assertNotNull(calculations.get(calculations.copy(source)))

        assertEquals(input.copy(name = "Кронштейн (2)"), copy.input)
        assertEquals("Кронштейн (2)", row(copy.id).name)
        assertEquals("кронштейн (2)", row(copy.id).name_search)
        assertEquals("ООО «Вектор»", copy.counterparty)
        assertEquals("ооо «вектор»", row(copy.id).counterparty_search)
    }

    @Test
    fun `the number a copy takes is free in any letter case`() {
        val source = calculations.saveDraft(sheetInput("Кронштейн"))
        calculations.saveDraft(sheetInput("КРОНШТЕЙН (2)"))

        assertEquals("Кронштейн (3)", nameOf(calculations.copy(source)))
    }

    @Test
    fun `a name that ends in a number is not a copy number`() {
        val source = calculations.saveDraft(sheetInput("Деталь 2"))

        assertEquals("Деталь 2 (2)", nameOf(calculations.copy(source)))
        assertEquals("Деталь 2 (3)", nameOf(calculations.copy(source)))
    }

    @Test
    fun `a copy of a simplified calculation is named the same way`() {
        val source = calculated(quickInput("Лист"))

        val copy = assertNotNull(calculations.get(calculations.copy(source))).asQuick()

        assertEquals("Лист (2)", copy.name)
        assertEquals(quickInput("Лист (2)"), copy.input)
    }

    @Test
    fun `revisions are numbered from 2 after the base of their source, whatever name the caller gives the input`() {
        val source = calculated(sheetInput("Кронштейн"))

        val first = revision(source, sheetInput("Кронштейн"))
        val second = revision(source, sheetInput("Другое имя из формы"))
        val draft = calculations.createRevisionDraft(source)
        // A revision of a revision goes on from the base too: №2, №3 and №4 are taken.
        val fifth = revision(first, sheetInput("Кронштейн №2"))

        assertEquals("Кронштейн №2", nameOf(first))
        assertEquals("Кронштейн №3", nameOf(second))
        assertEquals("Кронштейн №4", nameOf(draft))
        assertEquals("Кронштейн №5", nameOf(fifth))
        for (id in listOf(first, second, draft, fifth)) {
            assertEquals(nameOf(id), row(id).name, "the input and the column agree")
            assertEquals(nameOf(id).lowercase(), row(id).name_search)
        }
        assertEquals(sheetInput("Кронштейн №3"), assertNotNull(calculations.get(second)).input)
    }

    @Test
    fun `a revision of a simplified calculation is named the same way`() {
        val source = calculated(quickInput("Лист"))
        val input = quickInput("Лист")

        val first = calculations.createRevision(source, input, book, book.quickPriced(input))
        val draft = calculations.createRevisionDraft(source)

        assertEquals("Лист №2", assertNotNull(calculations.get(first)).asQuick().input.name)
        assertEquals("Лист №3", assertNotNull(calculations.get(draft)).asQuick().input.name)
    }

    @Test
    fun `a name that ends in a number gets a revision number after it`() {
        val source = calculated(sheetInput("Деталь 2"))

        assertEquals("Деталь 2 №2", nameOf(revision(source, sheetInput("Деталь 2"))))
    }

    @Test
    fun `a revision draft is untouched while it has the name the program gave it, and a typed name makes it a change`() {
        val source = calculated(sheetInput("Кронштейн"))
        val automatic = calculations.createRevisionDraft(source)
        val renamed = calculations.createRevisionDraft(source)
        calculations.saveDraft(sheetInput("Опора"), renamed)
        val revisionOfSource = revision(source, sheetInput("Кронштейн"))
        val ofRevision = calculations.createRevisionDraft(revisionOfSource)

        assertEquals("Кронштейн №2", nameOf(automatic))
        assertEquals("Кронштейн №3", nameOf(revisionOfSource), "№3 was free again once the draft was renamed")
        assertEquals("Кронштейн №4", nameOf(ofRevision))

        assertTrue(calculations.deleteUntouchedRevisionDraft(automatic))
        assertFalse(calculations.deleteUntouchedRevisionDraft(renamed), "the name was typed")
        assertTrue(calculations.deleteUntouchedRevisionDraft(ofRevision), "its source is itself a revision, the base is the same")
        assertEquals(setOf(source, revisionOfSource, renamed), calculations.list().map { it.id }.toSet())
    }
}
