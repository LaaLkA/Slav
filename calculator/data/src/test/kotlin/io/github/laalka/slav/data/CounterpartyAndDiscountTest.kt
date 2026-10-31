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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The counterparty and the discount of a calculation, full and simplified: stored, searched, sorted and read back. */
class CounterpartyAndDiscountTest {
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

    private fun names(query: String) = calculations.list(query = query).map { it.name }.toSet()

    private fun row(id: UUID) = db.database.calculationQueries.selectById(id.toString()).executeAsOne()

    /** A row the way a program from before the two fields left it: JSON without them, and no counterparty text in the columns. */
    private fun insertOld(name: String, inputJson: String, resultJson: String, unitPrice: String, kind: CalculationKind): UUID {
        val id = UUID.randomUUID()
        db.database.calculationQueries.insert(
            id = id.toString(),
            name = name,
            nameSearch = name.lowercase(),
            counterparty = "",
            counterpartySearch = "",
            status = CalculationStatus.CALCULATED.name,
            quantity = 3,
            unitPrice = unitPrice,
            createdAt = clock.millis(),
            updatedAt = clock.millis(),
            snapshotAt = clock.millis(),
            inputJson = inputJson,
            snapshotJson = storageJson.encodeToString(PriceBook.serializer(), book),
            resultJson = resultJson,
            sourceId = null,
            kind = kind.name,
        )
        return id
    }

    /** The JSON as an older program wrote it: the keys of the new fields are gone, and the test sees to it that they were there to go. */
    private fun withoutNewKeys(json: String, vararg keys: String): String =
        keys.fold(json) { text, key ->
            val stripped = text.replace(Regex(",\"$key\":(null|\"[^\"]*\")"), "")
            assertNotEquals(text, stripped, "$key was not in $text")
            stripped
        }

    @Test
    fun `the counterparty is stored with the calculation, read back and listed`() {
        val draft = calculations.saveDraft(sheetInput("Кронштейн", counterparty = "ООО «Вектор»"))
        val calculated = calculated(quickInput("Лист", counterparty = "ИП Орлов"))
        val none = calculations.saveDraft(sheetInput("Рама"))

        assertEquals("ООО «Вектор»", assertNotNull(calculations.get(draft)).counterparty)
        assertEquals("ИП Орлов", assertNotNull(calculations.get(calculated)).counterparty)
        assertEquals("", assertNotNull(calculations.get(none)).counterparty)
        assertEquals("ООО «Вектор»", row(draft).counterparty)
        assertEquals("ооо «вектор»", row(draft).counterparty_search)
        assertEquals("ип орлов", row(calculated).counterparty_search)
        assertEquals("", row(none).counterparty_search)
        assertEquals(
            mapOf("Кронштейн" to "ООО «Вектор»", "Лист" to "ИП Орлов", "Рама" to ""),
            calculations.list().associate { it.name to it.counterparty },
        )
        assertEquals(listOf("ООО «Вектор»"), calculations.list(query = "кронштейн").map { it.counterparty })
    }

    @Test
    fun `a search finds the counterparty in any letter case, Cyrillic included, as well as the name`() {
        calculations.saveDraft(sheetInput("Кронштейн", counterparty = "ООО «Вектор»"))
        calculated(sheetInput("Рама", counterparty = "ИП Орлов"))
        calculated(quickInput("Орловская стойка", counterparty = "ЗАО Север"))
        calculations.saveDraft(sheetInput("Лист"))

        assertEquals(setOf("Кронштейн"), names("ВЕКТОР"))
        assertEquals(setOf("Кронштейн"), names("  ооо «вект"))
        assertEquals(setOf("Рама", "Орловская стойка"), names("ОРЛОВ"), "one by its counterparty, the other by its name")
        assertEquals(setOf("Орловская стойка"), names("север"), "a quick calculation is found by its counterparty too")
        assertEquals(setOf("Кронштейн", "Рама", "Орловская стойка", "Лист"), names(""))
        assertEquals(emptySet(), names("Вектор Орлов"))
    }

    @Test
    fun `the search text is literal in the counterparty as well`() {
        calculations.saveDraft(sheetInput("Кронштейн", counterparty = "Скидка 100%"))
        calculations.saveDraft(sheetInput("Рама", counterparty = "ООО Ромашка"))

        assertEquals(setOf("Кронштейн"), names("%"))
        assertEquals(emptySet(), names("_"))
    }

    @Test
    fun `a changed counterparty is searched by its new text only`() {
        val id = calculations.saveDraft(sheetInput("Кронштейн", counterparty = "ООО «Вектор»"))

        calculations.saveDraft(sheetInput("Кронштейн", counterparty = "ИП Орлов"), id)

        assertEquals(emptySet(), names("вектор"))
        assertEquals(setOf("Кронштейн"), names("орлов"))
        assertEquals("ип орлов", row(id).counterparty_search)
        calculated(sheetInput("Кронштейн", counterparty = "Север"), id)
        assertEquals(emptySet(), names("орлов"))
        assertEquals(setOf("Кронштейн"), names("север"))
    }

    @Test
    fun `the list sorts by the counterparty the Russian way, in either direction`() {
        // Code-point order would put the capitals first and Ё before А.
        listOf("ёж", "Альфа", "яблоко", "Бета", "").forEachIndexed { index, counterparty ->
            calculations.saveDraft(sheetInput("Расчёт $index", counterparty = counterparty))
        }

        fun sorted(descending: Boolean) =
            calculations.list(sort = CalculationSort(CalculationSortColumn.COUNTERPARTY, descending)).map { it.counterparty }

        val ascending = sorted(descending = false)
        assertEquals(listOf("", "Альфа", "Бета", "ёж", "яблоко"), ascending)
        assertEquals(ascending.reversed(), sorted(descending = true))
    }

    @Test
    fun `a counterparty with equal values keeps the stable order of the list`() {
        val first = calculations.saveDraft(sheetInput("Первый", counterparty = "Вектор"))
        clock.advance(Duration.ofMinutes(1))
        val second = calculations.saveDraft(sheetInput("Второй", counterparty = "ВЕКТОР"))

        // Equal in the collation, so the most recently changed comes first.
        assertEquals(
            listOf(second, first),
            calculations.list(sort = CalculationSort(CalculationSortColumn.COUNTERPARTY, descending = false)).map { it.id },
        )
    }

    @Test
    fun `a discount is stored in the result, the unit price column holds the price after it`() {
        // 7 950,50 a piece; 7,5 % is 596,2875, half-up 596,29; the price is 7 354,21; a run of 3 is 22 062,63.
        val input = sheetInput("Кронштейн", quantity = 3, discountPercent = "7.5")
        val id = calculated(input)

        val stored = assertNotNull(calculations.get(id))
        val result = assertNotNull(stored.result)
        assertEquals(book.priced(input), result)
        assertEquals(dec("7.5"), result.discountPercent)
        assertEquals("7950.50", result.subtotal.amount.toPlainString())
        assertEquals("596.29", result.discount.amount.toPlainString())
        assertEquals("7354.21", assertNotNull(stored.unitPrice).amount.toPlainString())
        assertEquals("7354.21", row(id).unit_price)
        assertTrue("\"discountPercent\":\"7.5\"" in assertNotNull(row(id).result_json), "the percent is in the stored result")
        val summary = calculations.list().single()
        assertEquals("7354.21", summary.unitPrice?.amount?.toPlainString())
        assertEquals("22062.63", summary.total?.amount?.toPlainString())
        assertEquals(input, stored.input)
    }

    @Test
    fun `a discount of a simplified calculation is stored the same way`() {
        // 5 750,50; 5 % is 287,525, half-up 287,53; the price is 5 462,97, which is also the total.
        val input = quickInput("Лист", discountPercent = "5")
        val id = calculated(input)

        val stored = assertNotNull(calculations.get(id)).asQuick()
        val result = assertNotNull(stored.result)
        assertEquals(book.quickPriced(input), result)
        assertEquals(dec("5"), result.discountPercent)
        assertEquals("287.53", result.discount.amount.toPlainString())
        assertEquals("5462.97", assertNotNull(stored.unitPrice).amount.toPlainString())
        assertEquals("5462.97", row(id).unit_price)
        assertTrue("\"discountPercent\":\"5\"" in assertNotNull(row(id).result_json))
        assertEquals("5462.97", calculations.list().single().total?.amount?.toPlainString())
    }

    @Test
    fun `a calculation without a discount stores none, and its price is the subtotal`() {
        val id = calculated(sheetInput("Кронштейн"))

        val result = assertNotNull(assertNotNull(calculations.get(id)).result)
        assertNull(result.discountPercent)
        assertEquals("0.00", result.discount.amount.toPlainString())
        assertEquals("7950.50", result.unitPrice.amount.toPlainString())
        assertEquals("7950.50", row(id).unit_price)
    }

    @Test
    fun `a result and an input stored before discounts existed read as having none`() {
        val full = sheetInput("Кронштейн", quantity = 3)
        val fullResult = book.priced(full)
        val oldFull = insertOld(
            name = "Кронштейн",
            inputJson = withoutNewKeys(storageJson.encodeToString(CalculationInput.serializer(), full), "counterparty", "discountPercent"),
            resultJson = withoutNewKeys(storageJson.encodeToString(StoredResult.serializer(), StoredResult.of(fullResult)), "discountPercent"),
            unitPrice = fullResult.unitPrice.amount.toPlainString(),
            kind = CalculationKind.FULL,
        )
        val quick = quickInput("Лист")
        val quickResult = book.quickPriced(quick)
        val oldQuick = insertOld(
            name = "Лист",
            inputJson = withoutNewKeys(storageJson.encodeToString(QuickCalculationInput.serializer(), quick), "counterparty", "discountPercent"),
            resultJson = withoutNewKeys(storageJson.encodeToString(StoredQuickResult.serializer(), StoredQuickResult.of(quickResult)), "discountPercent"),
            unitPrice = quickResult.unitPrice.amount.toPlainString(),
            kind = CalculationKind.QUICK,
        )

        val storedFull = assertNotNull(calculations.get(oldFull))
        assertEquals(full, storedFull.input)
        assertEquals("", storedFull.counterparty)
        assertEquals(fullResult, storedFull.result)
        assertNull(storedFull.result?.discountPercent)
        assertEquals("0.00", storedFull.result?.discount?.amount?.toPlainString())
        assertEquals(fullResult.unitPrice, storedFull.unitPrice)

        val storedQuick = assertNotNull(calculations.get(oldQuick)).asQuick()
        assertEquals(quick, storedQuick.input)
        assertEquals(quickResult, storedQuick.result)
        assertNull(storedQuick.result?.discountPercent)
        assertEquals("0.00", storedQuick.result?.discount?.amount?.toPlainString())
        assertEquals(quickResult.unitPrice, storedQuick.unitPrice)

        // They list and search as before.
        assertEquals(setOf("Кронштейн", "Лист"), calculations.list().map { it.name }.toSet())
        assertEquals(listOf("", ""), calculations.list().map { it.counterparty })
    }

    @Test
    fun `a copy and a revision carry the counterparty and the discount`() {
        val input = sheetInput("Кронштейн", counterparty = "ООО «Вектор»", discountPercent = "10")
        val source = calculated(input)
        val result = assertNotNull(assertNotNull(calculations.get(source)).result)

        val copy = assertNotNull(calculations.get(calculations.copy(source)))
        val draft = assertNotNull(calculations.get(calculations.createRevisionDraft(source)))
        val revision = assertNotNull(calculations.get(calculations.createRevision(source, input, book, result)))

        for (carried in listOf(copy, draft, revision)) {
            assertEquals("ООО «Вектор»", carried.counterparty)
            assertEquals(dec("10"), carried.input.discountPercent)
        }
        assertEquals(result, revision.result)
        assertEquals(result.unitPrice, revision.unitPrice)
        assertEquals(setOf("Кронштейн", "Кронштейн (2)", "Кронштейн №2", "Кронштейн №3"), calculations.list().map { it.name }.toSet())
    }
}
