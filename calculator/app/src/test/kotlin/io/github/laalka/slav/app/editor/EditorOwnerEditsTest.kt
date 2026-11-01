package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.InterferingClock
import io.github.laalka.slav.app.RecordingOpener
import io.github.laalka.slav.app.calculations.DiscountLines
import io.github.laalka.slav.app.calculations.PriceSummary
import io.github.laalka.slav.app.calculations.SummaryLine
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.calculations.setSt3TwoMillimetrePrice
import io.github.laalka.slav.app.settings.NOT_A_DISCOUNT
import io.github.laalka.slav.app.settings.NOT_A_NUMBER
import io.github.laalka.slav.app.settings.REQUIRED
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.data.AppDatabase
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.DatabaseFactory
import io.github.laalka.slav.data.PartTemplateRepository
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.StoredCalculation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.util.UUID
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
 * The owner's changes to the full editor: the counterparty, the discount with the price in the bottom
 * bar, a name that is unique, and a calculated record edited in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorOwnerEditsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private val calculated = mutableListOf<UUID>()
    private val toClose = mutableListOf<AppDatabase>()

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() {
        deps.close()
        toClose.forEach(AppDatabase::close)
    }

    private fun TestScope.editor(id: UUID? = null, inPlace: Boolean = false) = EditorModel(
        deps.calculations, deps.settings, deps.attachments, deps.partTemplates, deps.fileOpener, backgroundScope, id,
        onCalculated = { calculated += it },
        inPlace = inPlace,
    )

    private fun stored(id: UUID): StoredCalculation.Full = assertIs<StoredCalculation.Full>(deps.calculations.get(id))

    private fun TestScope.golden(name: String = "Кронштейн"): EditorModel = editor().also {
        it.fillGoldenCase()
        it.onNameChange(name)
    }

    // ---- counterparty and discount --------------------------------------------------------------

    @Test
    fun `the counterparty is optional, stored without its outer spaces and shown again when the draft is reopened`() = runTest {
        val editor = golden()
        assertEquals("", editor.state.value.counterparty.text)
        assertEquals(0, editor.state.value.missingCount)

        editor.onCounterpartyChange("  ООО Ромашка  ")
        assertEquals(0, editor.state.value.missingCount, "the counterparty never stops «Рассчитать»")
        editor.flush()

        val draft = deps.calculations.list().single()
        assertEquals("ООО Ромашка", stored(draft.id).input.counterparty)
        assertEquals("ООО Ромашка", editor(draft.id).state.value.counterparty.text)

        editor.calculate()
        assertEquals("ООО Ромашка", stored(calculated.single()).counterparty)
    }

    @Test
    fun `the discount is optional, takes a comma, and is refused unless it is above 0 and below 100`() = runTest {
        val editor = golden()
        val state = { editor.state.value }
        assertEquals("", state().discount.text)
        assertNull(state().discount.error)

        for ((typed, error) in listOf(
            "0" to NOT_A_DISCOUNT,
            "100" to NOT_A_DISCOUNT,
            "100,5" to NOT_A_DISCOUNT,
            "abc" to NOT_A_NUMBER,
            "-5" to NOT_A_NUMBER,
        )) {
            editor.onDiscountChange(typed)
            assertEquals(error, state().discount.error, typed)
            assertEquals(1, state().missingCount, typed)
            assertFalse(state().canCalculate, typed)
        }
        for (typed in listOf("", "10", "12,5", "12.5", "0,01", "99,99")) {
            editor.onDiscountChange(typed)
            assertNull(state().discount.error, typed)
            assertEquals(0, state().missingCount, typed)
        }
    }

    @Test
    fun `a draft stores a valid discount and none for a refused one`() = runTest {
        val editor = golden()

        editor.onDiscountChange("12,5")
        editor.flush()
        val id = deps.calculations.list().single().id
        assertEquals(0, BigDecimal("12.5").compareTo(stored(id).input.discountPercent))
        assertEquals("12,5", editor(id).state.value.discount.text, "the comma is back when the draft is reopened")

        editor.onDiscountChange("150")
        editor.flush()
        assertNull(stored(id).input.discountPercent)
        assertEquals("", editor(id).state.value.discount.text)
    }

    @Test
    fun `the bottom bar prices the input as it stands, with the discount lines only when there is a discount`() = runTest {
        val editor = golden()
        editor.onQuantityChange("10")

        // The lines are what the sum before the discount is made of: 3 200 + 8 000 + 100 + 40 = 11 340.
        val lines = listOf(
            SummaryLine("Материал", "3 200,00 ₽"),
            SummaryLine("Лазер: рез листа", "8 000,00 ₽"),
            SummaryLine("Гибка", "100,00 ₽"),
            SummaryLine("Сварка сборки (полуавтомат)", "40,00 ₽"),
        )
        assertEquals(PriceSummary(discount = null, unitPrice = "11 340,00 ₽", total = "113 400,00 ₽", lines = lines), editor.state.value.summary)

        editor.onDiscountChange("10")
        assertEquals(
            PriceSummary(DiscountLines("11 340,00 ₽", "Скидка (10 %)", "−1 134,00 ₽"), "10 206,00 ₽", "102 060,00 ₽", lines),
            editor.state.value.summary,
        )

        // The price follows the settings the editor is calculated on, and the card shows what the bar showed.
        editor.calculate()
        val saved = stored(calculated.single())
        assertEquals(0, BigDecimal("10206.00").compareTo(assertNotNull(saved.unitPrice).amount))
        assertEquals(0, BigDecimal("1134.00").compareTo(assertNotNull(saved.result).discount.amount))
    }

    @Test
    fun `the lines of the price are summed over the parts, a kind of operation being one line`() = runTest {
        val editor = golden()
        editor.copyPart(editor.state.value.parts.single().id)

        val summary = assertNotNull(editor.state.value.summary)
        assertEquals(2, editor.state.value.parts.size)
        // Two equal parts: every line of one part twice, the operation of the product once.
        assertEquals(
            listOf(
                SummaryLine("Материал", "6 400,00 ₽"),
                SummaryLine("Лазер: рез листа", "16 000,00 ₽"),
                SummaryLine("Гибка", "200,00 ₽"),
                SummaryLine("Сварка сборки (полуавтомат)", "40,00 ₽"),
            ),
            summary.lines,
        )
    }

    @Test
    fun `the bottom bar shows no price while a field is missing or a price is not filled in`() = runTest {
        val editor = editor()
        assertNull(editor.state.value.summary, "an empty editor")

        editor.fillGoldenCase()
        assertNotNull(editor.state.value.summary)

        editor.onDiscountChange("abc")
        assertNull(editor.state.value.summary, "a refused discount")
        editor.onDiscountChange("")
        assertNotNull(editor.state.value.summary)

        deps.settings.save(deps.settings.load().copy(laserCutPerMinute = null, materialPricesPerKg = emptyList()))
        editor.resume()
        assertNull(editor.state.value.summary, "no price for the material")
        assertEquals(0, editor.state.value.missingCount, "the fields themselves are filled in")
    }

    // ---- unique names ---------------------------------------------------------------------------

    @Test
    fun `a name another calculation has is flagged as it is typed, in any letter case, and blocks Рассчитать`() = runTest {
        deps.saveCalculated(goldenInput("Кронштейн"))
        val editor = editor()
        editor.fillGoldenCase()

        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        assertEquals(1, editor.state.value.missingCount)
        assertFalse(editor.state.value.canCalculate)
        assertNull(editor.state.value.summary, "no price for a calculation that cannot be calculated")

        editor.calculate()
        assertEquals(emptyList(), calculated, "Рассчитать does nothing while the name is taken")
        assertEquals(1, deps.calculations.list().size)

        editor.onNameChange("  кРоНшТеЙн ")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        editor.onNameChange("Кронштейн 2")
        assertNull(editor.state.value.name.error)
        assertTrue(editor.state.value.canCalculate)
        editor.calculate()
        assertEquals("Кронштейн 2", stored(calculated.single()).name)
    }

    @Test
    fun `a blank name is missing, not taken, and the own name of an open draft is not a duplicate`() = runTest {
        val draft = deps.calculations.saveDraft(goldenInput("Кронштейн"))
        deps.calculations.saveDraft(goldenInput(""))
        val editor = editor(draft)

        assertNull(editor.state.value.name.error, "its own name")
        editor.onNameChange("")
        assertEquals(REQUIRED, editor.state.value.name.error)
        editor.onNameChange("Кронштейн")
        assertNull(editor.state.value.name.error)
        editor.calculate()
        assertEquals(listOf(draft), calculated, "the draft became the calculation")
    }

    @Test
    fun `an autosave under a taken name writes nothing and throws nothing, the error stays, and the next valid name is saved`() = runTest {
        deps.saveCalculated(goldenInput("Кронштейн"))
        val editor = editor()
        editor.fillGoldenCase()

        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds + 1)
        runCurrent()
        editor.flush()
        editor.leave()

        assertEquals(1, deps.calculations.list().size, "nothing was saved under the taken name")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)

        editor.onNameChange("Кронштейн 2")
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(setOf("Кронштейн", "Кронштейн 2"), deps.calculations.list().map { it.name }.toSet())
        assertNull(editor.state.value.name.error)
    }

    @Test
    fun `a name taken between the check and the write is shown like any other taken name, for the calculation and for the autosave`() = runTest {
        val database = DatabaseFactory(AppPaths(temp.newFolder("race").toPath())).open().also(toClose::add)
        val clock = InterferingClock()
        val attachments = AttachmentRepository(database, clock)
        val calculations = CalculationRepository(database, clock, attachments)
        val rival = CalculationRepository(database)
        val editor = EditorModel(
            calculations, SettingsRepository(database), attachments, PartTemplateRepository(database), RecordingOpener(), backgroundScope,
            id = null, onCalculated = { calculated += it },
        )

        // The autosave: «Рама» is free when typed, and taken by the time the draft is written.
        editor.onNameChange("Рама")
        assertNull(editor.state.value.name.error)
        clock.before = { rival.saveDraft(goldenInput("Рама")) }
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(listOf("Рама"), calculations.list().map { it.name }, "only the rival's")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)

        // «Рассчитать»: the same on the price, which is saved over nothing.
        editor.fillGoldenCase()
        editor.onNameChange("Опора")
        assertNull(editor.state.value.name.error)
        assertTrue(editor.state.value.canCalculate)
        clock.before = { rival.saveDraft(goldenInput("Опора")) }
        editor.calculate()
        assertEquals(emptyList(), calculated, "the calculation was not saved, and nothing crashed")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        assertFalse(editor.state.value.canCalculate)
        assertEquals(setOf("Рама", "Опора"), calculations.list().map { it.name }.toSet())

        // Another name clears it.
        editor.onNameChange("Опора 2")
        assertNull(editor.state.value.name.error)
        editor.calculate()
        assertEquals(1, calculated.size)
    }

    // ---- editing a calculated record in place ----------------------------------------------------

    @Test
    fun `an editor in place is open on a calculated record with its stored input, and refuses a draft`() = runTest {
        val input = goldenInput(quantity = 4).copy(counterparty = "ООО Ромашка", discountPercent = BigDecimal("10"))
        val id = deps.saveCalculated(input)
        deps.setSt3TwoMillimetrePrice("300")

        val editor = editor(id, inPlace = true)

        val state = editor.state.value
        assertTrue(state.inPlace)
        assertFalse(state.isNew)
        assertEquals("Кронштейн", state.name.text)
        assertEquals("ООО Ромашка", state.counterparty.text)
        assertEquals("4", state.quantity.text)
        assertEquals("10", state.discount.text)
        assertEquals(0, state.missingCount)
        assertEquals("Цены — текущие настройки", state.priceSource, "an edit in place is priced on the current settings")
        // 12 940 on the current prices, 10 % off.
        assertEquals("11 646,00 ₽", state.summary?.unitPrice)
        assertEquals(id, editor.inPlaceId)
        assertFalse(editor.discardsOnLeave)

        val draft = deps.calculations.saveDraft(goldenInput("Черновик"))
        assertFailsWith<IllegalArgumentException> { editor(draft, inPlace = true) }
        assertFailsWith<IllegalArgumentException> { editor(null, inPlace = true) }
        assertNull(editor(draft).inPlaceId)
    }

    @Test
    fun `nothing is written while a record is edited in place, and leaving drops the changes`() = runTest {
        val id = deps.saveCalculated(goldenInput(quantity = 4))
        val before = stored(id)
        val editor = editor(id, inPlace = true)

        editor.onNameChange("Кронштейн с ребром")
        editor.onCounterpartyChange("ООО Ромашка")
        editor.onDiscountChange("10")
        editor.onQuantityChange("7")
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds * 10)
        runCurrent()
        editor.flush()

        assertEquals(before, stored(id), "no autosave and no flush: the row is byte for byte as it was")
        assertEquals(listOf(id), deps.calculations.list().map { it.id })
        assertTrue(editor.discardsOnLeave)

        editor.leave()
        assertEquals(before, stored(id))
        assertEquals(listOf(id), deps.calculations.list().map { it.id })
    }

    @Test
    fun `leaving an edit in place of a version made from another calculation keeps it, changed or not`() = runTest {
        val first = deps.saveCalculated(goldenInput("Кронштейн"))
        val source = stored(first)
        val version = deps.calculations.createRevision(first, goldenInput("Кронштейн"), source.snapshot!!, source.result!!, source.snapshotAt)
        val before = stored(version)
        assertEquals(first, before.sourceId)

        editor(version, inPlace = true).leave()
        assertEquals(before, stored(version), "an automatic copy nobody changed is deleted only while it is a draft")

        val changed = editor(version, inPlace = true)
        changed.onQuantityChange("9")
        changed.leave()
        assertEquals(before, stored(version))
        assertEquals(setOf(first, version), deps.calculations.list().map { it.id }.toSet())
    }

    @Test
    fun `typing a change back leaves nothing to discard`() = runTest {
        val id = deps.saveCalculated(goldenInput())
        val editor = editor(id, inPlace = true)

        editor.onQuantityChange("5")
        assertTrue(editor.discardsOnLeave)
        editor.onQuantityChange("1")
        assertFalse(editor.discardsOnLeave)
    }

    @Test
    fun `Рассчитать writes the record over itself on the current prices, keeping its id, its date and its source`() = runTest {
        val first = deps.saveCalculated(goldenInput("Кронштейн"))
        val source = stored(first)
        val revision = deps.calculations.createRevision(first, goldenInput("Кронштейн"), source.snapshot!!, source.result!!, source.snapshotAt)
        deps.calculations.setStatus(revision, CalculationStatus.SENT)
        val before = stored(revision)
        deps.setSt3TwoMillimetrePrice("300")
        val editor = editor(revision, inPlace = true)

        editor.onNameChange("Кронштейн усиленный")
        editor.onCounterpartyChange("ООО Ромашка")
        editor.onQuantityChange("3")
        editor.onDiscountChange("10")
        editor.calculate()

        assertEquals(listOf(revision), calculated, "the card of the same calculation is shown")
        assertEquals(setOf(first, revision), deps.calculations.list().map { it.id }.toSet(), "no new row")
        val after = stored(revision)
        assertEquals("Кронштейн усиленный", after.name)
        assertEquals("ООО Ромашка", after.counterparty)
        assertEquals(3, after.quantity)
        assertEquals(CalculationStatus.CALCULATED, after.status, "back to «Рассчитан», as overwriting does")
        assertEquals(deps.settings.load(), after.snapshot, "the current prices, not the old snapshot")
        assertNotEquals(before.snapshot, after.snapshot)
        // 12 940 on the current prices, 10 % off.
        assertEquals(0, BigDecimal("11646.00").compareTo(assertNotNull(after.unitPrice).amount))
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.sourceId, after.sourceId)
        assertEquals(source, stored(first), "the source calculation is untouched")
        assertFalse(editor.discardsOnLeave, "calculated: nothing is left to discard")
    }

    @Test
    fun `an edit in place keeps its own name and refuses a name another calculation has`() = runTest {
        val id = deps.saveCalculated(goldenInput("Кронштейн"))
        deps.saveCalculated(goldenInput("Опора"))
        val before = stored(id)
        val editor = editor(id, inPlace = true)
        assertNull(editor.state.value.name.error, "its own name is not a duplicate")

        editor.onNameChange("опора")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        editor.calculate()
        assertEquals(emptyList(), calculated)
        assertEquals(before, stored(id))

        editor.onNameChange("Кронштейн")
        editor.calculate()
        assertEquals(listOf(id), calculated)
    }

    @Test
    fun `a missing price stops Рассчитать in place, lists what is missing and leaves the record as it was`() = runTest {
        val id = deps.saveCalculated(goldenInput())
        val before = stored(id)
        deps.settings.save(deps.settings.load().copy(laserCutPerMinute = null, materialPricesPerKg = emptyList()))
        val editor = editor(id, inPlace = true)
        editor.onQuantityChange("2")

        editor.calculate()

        assertEquals(emptyList(), calculated)
        assertTrue(editor.state.value.calcErrors.isNotEmpty())
        assertTrue("Расчёт не изменён." in assertNotNull(editor.state.value.calcErrorsHint), editor.state.value.calcErrorsHint)
        assertEquals(before, stored(id))
        assertIs<CalcOutcome.Failed>(Calculator(deps.settings.load()).calculate(stored(id).input))
    }

    @Test
    fun `the drawings of an edit in place belong to the record at once, and the prices come back from the settings`() = runTest {
        val id = deps.saveCalculated(goldenInput())
        val editor = editor(id, inPlace = true)
        val file = temp.newFile("plan.pdf").also { it.writeBytes(byteArrayOf(1, 2, 3)) }.toPath()

        editor.attachments.attach(file)

        assertEquals(listOf("plan.pdf"), deps.attachments.list(id).map { it.fileName })
        assertEquals(listOf(id), deps.calculations.list().map { it.id })

        deps.setSt3TwoMillimetrePrice("300")
        editor.resume()
        assertEquals("12 940,00 ₽", editor.state.value.summary?.unitPrice)
    }
}
