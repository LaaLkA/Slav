package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.InterferingClock
import io.github.laalka.slav.app.RecordingOpener
import io.github.laalka.slav.app.calculations.DiscountLines
import io.github.laalka.slav.app.calculations.PriceSummary
import io.github.laalka.slav.app.calculations.SummaryLine
import io.github.laalka.slav.app.calculations.quickGoldenInput
import io.github.laalka.slav.app.calculations.saveQuickCalculated
import io.github.laalka.slav.app.calculations.updateSettings
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.editor.NAME_TAKEN
import io.github.laalka.slav.app.settings.NOT_A_DISCOUNT
import io.github.laalka.slav.app.settings.NOT_A_NUMBER
import io.github.laalka.slav.data.AppDatabase
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.DatabaseFactory
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's changes to the simplified editor: no run size, the counterparty, the discount with the
 * price in the bottom bar, a name that is unique, and a calculated record edited in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickOwnerEditsTest {
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

    private fun TestScope.editor(id: UUID? = null, inPlace: Boolean = false) = QuickEditorModel(
        deps.calculations, deps.settings, deps.attachments, deps.fileOpener, backgroundScope, id,
        onCalculated = { calculated += it },
        inPlace = inPlace,
    )

    private fun stored(id: UUID): StoredCalculation.Quick = assertIs<StoredCalculation.Quick>(deps.calculations.get(id))

    private fun TestScope.golden(name: String = "Лист на раскрой"): QuickEditorModel = editor().also { it.fillQuickGoldenCase(name) }

    // ---- no run size, counterparty and discount ---------------------------------------------------

    @Test
    fun `a simplified calculation is saved with a run size of 1 and the editor has nothing to change it with`() = runTest {
        val editor = golden()

        editor.calculate()

        val saved = stored(calculated.single())
        assertEquals(1, saved.input.quantity)
        assertEquals(1, saved.quantity)
        assertEquals(0, BigDecimal("5400.00").compareTo(assertNotNull(saved.unitPrice).amount))
        assertEquals(0, BigDecimal("5400.00").compareTo(saved.unitPrice?.times(saved.quantity)?.amount), "the total is the unit price")
        assertEquals(0, editor.state.value.missingCount)
    }

    @Test
    fun `the counterparty is optional, stored without its outer spaces and shown again when the draft is reopened`() = runTest {
        val editor = golden()
        assertEquals("", editor.state.value.counterparty.text)

        editor.onCounterpartyChange("  ООО Ромашка  ")
        assertEquals(0, editor.state.value.missingCount, "the counterparty never stops «Рассчитать»")
        editor.flush()

        val draft = deps.calculations.list().single()
        assertEquals("ООО Ромашка", stored(draft.id).input.counterparty)
        assertEquals("ООО Ромашка", editor(draft.id).state.value.counterparty.text)
    }

    @Test
    fun `the discount is optional, takes a comma, and is refused unless it is above 0 and below 100`() = runTest {
        val editor = golden()
        val state = { editor.state.value }

        for ((typed, error) in listOf(
            "0" to NOT_A_DISCOUNT,
            "100" to NOT_A_DISCOUNT,
            "abc" to NOT_A_NUMBER,
            "-5" to NOT_A_NUMBER,
        )) {
            editor.onDiscountChange(typed)
            assertEquals(error, state().discount.error, typed)
            assertEquals(1, state().missingCount, typed)
            assertFalse(state().canCalculate, typed)
        }
        for (typed in listOf("", "10", "12,5", "12.5", "99,99")) {
            editor.onDiscountChange(typed)
            assertNull(state().discount.error, typed)
            assertEquals(0, state().missingCount, typed)
        }

        editor.onDiscountChange("12,5")
        editor.flush()
        val id = deps.calculations.list().single().id
        assertEquals(0, BigDecimal("12.5").compareTo(stored(id).input.discountPercent))
        assertEquals("12,5", editor(id).state.value.discount.text)
        editor.onDiscountChange("150")
        editor.flush()
        assertNull(stored(id).input.discountPercent, "a refused discount is not kept")
    }

    @Test
    fun `the bottom bar prices the input as it stands, with no run total, and the discount lines only when there is a discount`() = runTest {
        val editor = editor()
        assertNull(editor.state.value.summary, "an empty editor")
        editor.fillQuickGoldenCase()

        // The sheet and the laser time are what the sum before the discount is made of: 4 800 + 600 = 5 400.
        val lines = listOf(SummaryLine("Материал", "4 800,00 ₽"), SummaryLine("Резка (время)", "600,00 ₽"))
        assertEquals(PriceSummary(discount = null, unitPrice = "5 400,00 ₽", total = null, lines = lines), editor.state.value.summary)

        editor.onDiscountChange("10")
        assertEquals(
            PriceSummary(DiscountLines("5 400,00 ₽", "Скидка (10 %)", "−540,00 ₽"), "4 860,00 ₽", total = null, lines = lines),
            editor.state.value.summary,
        )

        editor.onDiscountChange("abc")
        assertNull(editor.state.value.summary, "a refused discount")
        editor.onDiscountChange("10")
        deps.updateSettings { it.copy(laserCutPerMinute = null) }
        editor.resume()
        assertNull(editor.state.value.summary, "no laser rate")

        deps.updateSettings { it.copy(laserCutPerMinute = BigDecimal("80")) }
        editor.resume()
        // 4 800 for the material and 12 min × 80 ₽/min for the cut, 10 % off.
        assertEquals("5 184,00 ₽", editor.state.value.summary?.unitPrice)
        editor.calculate()
        assertEquals(0, BigDecimal("5184.00").compareTo(assertNotNull(stored(calculated.single()).unitPrice).amount))
    }

    // ---- unique names ---------------------------------------------------------------------------

    @Test
    fun `a name another calculation has is flagged as it is typed, in any letter case, and blocks Рассчитать`() = runTest {
        deps.saveQuickCalculated(quickGoldenInput("Лист на раскрой"))
        val editor = golden()

        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        assertEquals(1, editor.state.value.missingCount)
        assertFalse(editor.state.value.canCalculate)
        assertNull(editor.state.value.summary)
        editor.calculate()
        assertEquals(emptyList(), calculated)
        assertEquals(1, deps.calculations.list().size)

        editor.onNameChange(" ЛИСТ НА РАСКРОЙ ")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        editor.onNameChange("Лист на раскрой 2")
        assertNull(editor.state.value.name.error)
        editor.calculate()
        assertEquals("Лист на раскрой 2", stored(calculated.single()).name)
    }

    @Test
    fun `an autosave under a taken name writes nothing and throws nothing, the error stays, and the next valid name is saved`() = runTest {
        deps.saveQuickCalculated(quickGoldenInput("Лист на раскрой"))
        val editor = golden()

        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds + 1)
        runCurrent()
        editor.flush()
        editor.leave()

        assertEquals(1, deps.calculations.list().size)
        assertEquals(NAME_TAKEN, editor.state.value.name.error)

        editor.onNameChange("Лист на раскрой 2")
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(2, deps.calculations.list().size)
        assertNull(editor.state.value.name.error)
    }

    @Test
    fun `a name taken between the check and the write is shown like any other taken name, for the calculation and for the autosave`() = runTest {
        val database = DatabaseFactory(AppPaths(temp.newFolder("race").toPath())).open().also(toClose::add)
        val clock = InterferingClock()
        val attachments = AttachmentRepository(database, clock)
        val calculations = CalculationRepository(database, clock, attachments)
        val rival = CalculationRepository(database)
        val editor = QuickEditorModel(
            calculations, SettingsRepository(database), attachments, RecordingOpener(), backgroundScope,
            id = null, onCalculated = { calculated += it },
        )

        editor.onNameChange("Рама")
        assertNull(editor.state.value.name.error)
        clock.before = { rival.saveDraft(quickGoldenInput("Рама")) }
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(listOf("Рама"), calculations.list().map { it.name }, "only the rival's")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)

        editor.fillQuickGoldenCase("Опора")
        assertNull(editor.state.value.name.error)
        clock.before = { rival.saveDraft(quickGoldenInput("Опора")) }
        editor.calculate()
        assertEquals(emptyList(), calculated, "the calculation was not saved, and nothing crashed")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        assertFalse(editor.state.value.canCalculate)

        editor.onNameChange("Опора 2")
        assertNull(editor.state.value.name.error)
        editor.calculate()
        assertEquals(1, calculated.size)
    }

    // ---- editing a calculated record in place ----------------------------------------------------

    @Test
    fun `an editor in place is open on a calculated record with its stored input, and refuses a draft`() = runTest {
        val id = deps.saveQuickCalculated(quickGoldenInput().copy(counterparty = "ООО Ромашка", discountPercent = BigDecimal("10")))
        deps.updateSettings { it.copy(laserCutPerMinute = BigDecimal("80")) }

        val editor = editor(id, inPlace = true)

        val state = editor.state.value
        assertTrue(state.inPlace)
        assertFalse(state.isNew)
        assertEquals("Лист на раскрой", state.name.text)
        assertEquals("ООО Ромашка", state.counterparty.text)
        assertEquals("10", state.discount.text)
        assertEquals("Цены — текущие настройки", state.priceSource, "an edit in place is priced on the current settings")
        assertEquals("5 184,00 ₽", state.summary?.unitPrice)
        assertEquals(id, editor.inPlaceId)
        assertFalse(editor.discardsOnLeave)
        val draft = deps.calculations.saveDraft(quickGoldenInput("Черновик"))
        assertFailsWith<IllegalArgumentException> { editor(draft, inPlace = true) }
        assertFailsWith<IllegalArgumentException> { editor(null, inPlace = true) }
    }

    @Test
    fun `nothing is written while a record is edited in place, and leaving drops the changes`() = runTest {
        val id = deps.saveQuickCalculated()
        val before = stored(id)
        val editor = editor(id, inPlace = true)

        editor.onNameChange("Лист с припуском")
        editor.onCounterpartyChange("ООО Ромашка")
        editor.onDiscountChange("10")
        editor.onSheetAreaChange("2")
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds * 10)
        runCurrent()
        editor.flush()

        assertEquals(before, stored(id), "no autosave and no flush: the row is byte for byte as it was")
        assertEquals(listOf(id), deps.calculations.list().map { it.id })
        assertTrue(editor.discardsOnLeave)

        editor.leave()
        assertEquals(before, stored(id))

        editor.onNameChange(before.name)
        editor.onCounterpartyChange("")
        editor.onDiscountChange("")
        editor.onSheetAreaChange("1,5")
        assertFalse(editor.discardsOnLeave, "typed back, nothing is left to discard")
    }

    @Test
    fun `Рассчитать writes the record over itself on the current prices, keeping its id and its date`() = runTest {
        val id = deps.saveQuickCalculated()
        deps.calculations.setStatus(id, CalculationStatus.SENT)
        val before = stored(id)
        deps.updateSettings { it.copy(laserCutPerMinute = BigDecimal("80")) }
        val editor = editor(id, inPlace = true)

        editor.onNameChange("Лист с припуском")
        editor.onCounterpartyChange("ООО Ромашка")
        editor.onDiscountChange("10")
        editor.calculate()

        assertEquals(listOf(id), calculated)
        assertEquals(listOf(id), deps.calculations.list().map { it.id }, "no new row")
        val after = stored(id)
        assertEquals("Лист с припуском", after.name)
        assertEquals("ООО Ромашка", after.counterparty)
        assertEquals(CalculationStatus.CALCULATED, after.status)
        assertEquals(deps.settings.load(), after.snapshot)
        // 4 800 + 12 × 80 = 5 760 on the current prices, 10 % off.
        assertEquals(0, BigDecimal("5184.00").compareTo(assertNotNull(after.unitPrice).amount))
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(1, after.quantity)
        assertFalse(editor.discardsOnLeave)
    }

    @Test
    fun `an edit in place keeps its own name, refuses another's, and a missing price leaves the record as it was`() = runTest {
        val id = deps.saveQuickCalculated(quickGoldenInput("Лист"))
        deps.saveQuickCalculated(quickGoldenInput("Опора"))
        val before = stored(id)
        val editor = editor(id, inPlace = true)
        assertNull(editor.state.value.name.error)

        editor.onNameChange("опора")
        assertEquals(NAME_TAKEN, editor.state.value.name.error)
        editor.calculate()
        assertEquals(emptyList(), calculated)
        assertEquals(before, stored(id))

        editor.onNameChange("Лист")
        deps.updateSettings { it.copy(laserCutPerMinute = null) }
        editor.onSheetAreaChange("2")
        editor.calculate()
        assertEquals(emptyList(), calculated)
        assertTrue(assertNotNull(editor.state.value.calcErrorsHint).endsWith("Расчёт не изменён."))
        assertEquals(before, stored(id))
    }

    @Test
    fun `leaving an edit in place of a version made from another calculation keeps it, changed or not`() = runTest {
        val first = deps.saveQuickCalculated(quickGoldenInput("Лист"))
        val source = stored(first)
        val version = deps.calculations.createRevision(first, quickGoldenInput("Лист"), source.snapshot!!, source.result!!, source.snapshotAt)
        val before = stored(version)
        assertEquals(first, before.sourceId)

        editor(version, inPlace = true).leave()
        assertEquals(before, stored(version))

        val changed = editor(version, inPlace = true)
        changed.onSheetAreaChange("3")
        changed.leave()
        assertEquals(before, stored(version))
    }

    @Test
    fun `a drawing attached while a record is edited in place belongs to it at once`() = runTest {
        val id = deps.saveQuickCalculated()
        val editor = editor(id, inPlace = true)
        val file = temp.newFile("plan.pdf").also { it.writeBytes(byteArrayOf(1, 2, 3)) }.toPath()

        editor.attachments.attach(file)

        assertEquals(listOf("plan.pdf"), deps.attachments.list(id).map { it.fileName })
        assertEquals(listOf(id), deps.calculations.list().map { it.id })
    }
}
