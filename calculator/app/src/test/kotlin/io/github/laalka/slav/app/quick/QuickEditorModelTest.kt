package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.DrawingPicker
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.CardModel
import io.github.laalka.slav.app.calculations.quickGoldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveQuickCalculated
import io.github.laalka.slav.app.calculations.updateSettings
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.settings.REQUIRED
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
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
import kotlin.io.path.writeBytes
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

@OptIn(ExperimentalCoroutinesApi::class)
class QuickEditorModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private val calculated = mutableListOf<UUID>()

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() = deps.close()

    private fun TestScope.editor(id: UUID? = null) =
        QuickEditorModel(deps.calculations, deps.settings, deps.attachments, deps.fileOpener, backgroundScope, id, onCalculated = { calculated += it })

    private fun stored(id: UUID): StoredCalculation.Quick = assertIs<StoredCalculation.Quick>(deps.calculations.get(id))

    private fun onlyStored(): StoredCalculation.Quick = stored(deps.calculations.list().single().id)

    private val QuickEditorModel.view: QuickEditorState get() = state.value

    @Test
    fun `a new editor is empty, asks for every field and shows no mass`() = runTest {
        val editor = editor()

        val state = editor.view
        assertTrue(state.isNew)
        assertEquals("Цены — текущие настройки", state.priceSource)
        assertEquals("₽", state.currency)
        assertEquals("1", state.quantity.text)
        assertEquals(listOf("Ст3"), state.grade.options)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "8", "10", "12", "14", "16", "18", "20"), state.thickness.options)
        assertNull(state.mass)
        // The name, the grade, the thickness, the area and the time stop «Рассчитать»; the run size is already 1.
        assertEquals(5, state.missingCount)
        assertFalse(state.canCalculate)
        assertEquals(REQUIRED, state.name.error)
        assertEquals("Выберите", state.grade.error)
        assertEquals("Выберите", state.thickness.error)
        assertEquals(REQUIRED, state.sheetArea.error)
        assertEquals(REQUIRED, state.cutTime.error)
        editor.calculate()
        assertEquals(emptyList(), calculated, "nothing is calculated while a field is missing")
        assertEquals(emptyList(), deps.calculations.list())
    }

    @Test
    fun `the mass follows the grade, the thickness and the area as they are filled`() = runTest {
        val editor = editor()
        editor.onGradeChange("Ст3")
        editor.onThicknessChange(BigDecimal("2"))
        assertNull(editor.view.mass, "no area yet")

        editor.onSheetAreaChange("1,5")
        // 1.5 m² × 0.002 m × 8000 kg/m³.
        assertEquals("24 кг", editor.view.mass)

        editor.onSheetAreaChange("0,25")
        assertEquals("4 кг", editor.view.mass)
        editor.onThicknessChange(BigDecimal("3"))
        assertEquals("6 кг", editor.view.mass)
        editor.onSheetAreaChange("abc")
        assertNull(editor.view.mass)
        assertEquals("Не число", editor.view.sheetArea.error)
        editor.onSheetAreaChange("0")
        assertEquals("Больше нуля", editor.view.sheetArea.error)
    }

    @Test
    fun `the golden quick case is calculated and saved as 5 400,00, 54 000,00 for a run of 10`() = runTest {
        val editor = editor()
        editor.fillQuickGoldenCase()
        assertEquals("24 кг", editor.view.mass)
        assertEquals(0, editor.view.missingCount)
        assertTrue(editor.view.canCalculate)

        editor.calculate()

        val id = calculated.single()
        val saved = stored(id)
        assertEquals(CalculationKind.QUICK, saved.kind)
        assertEquals(CalculationStatus.CALCULATED, saved.status)
        assertEquals(quickGoldenInput(quantity = 10), saved.input)
        assertEquals("5400.00", saved.unitPrice?.amount?.toPlainString())
        assertEquals("54000.00", saved.unitPrice?.times(saved.quantity)?.amount?.toPlainString())
        assertEquals(deps.settings.load(), saved.snapshot)
        assertEquals(listOf(id), deps.calculations.list().map { it.id }, "the draft became the calculation, no second row")
    }

    @Test
    fun `manual extras on both lines are part of the price`() = runTest {
        val editor = editor()
        editor.fillQuickGoldenCase()
        editor.onExtraAmountChange(QuickLine.MATERIAL, "200,50")
        editor.onExtraCommentChange(QuickLine.MATERIAL, "Доставка листа")
        editor.onExtraAmountChange(QuickLine.CUT, "150")
        editor.onExtraAmountChange(QuickLine.CUT, "abc")
        assertEquals("Не число", editor.view.cutExtra.amount.error)
        assertFalse(editor.view.canCalculate)
        editor.onExtraAmountChange(QuickLine.CUT, "150")
        editor.onExtraCommentChange(QuickLine.CUT, "Снятие фаски")

        editor.calculate()

        val saved = stored(calculated.single())
        assertEquals(ManualExtra(Money(BigDecimal("200.50")), "Доставка листа"), saved.input.materialExtra)
        assertEquals(ManualExtra(Money(BigDecimal("150")), "Снятие фаски"), saved.input.cutExtra)
        assertEquals("5000.50", saved.result?.material?.cost?.amount?.toPlainString())
        assertEquals("750.00", saved.result?.cut?.cost?.amount?.toPlainString())
        assertEquals("5750.50", saved.unitPrice?.amount?.toPlainString())
    }

    @Test
    fun `typing is saved as a draft after the delay, with the unfilled fields stored as zeros`() = runTest {
        val editor = editor()
        editor.onNameChange("Лист")
        editor.onGradeChange("Ст3")
        editor.onSheetAreaChange("1,5")

        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(emptyList(), deps.calculations.list(), "not yet")
        advanceTimeBy(2)
        runCurrent()

        val draft = onlyStored()
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals("Лист", draft.name)
        assertEquals("Ст3", draft.input.grade)
        assertEquals(0, BigDecimal("1.5").compareTo(draft.input.sheetAreaM2))
        assertEquals(0, BigDecimal.ZERO.compareTo(draft.input.thicknessMm))
        assertEquals(0, BigDecimal.ZERO.compareTo(draft.input.cutTimeMin))
        assertNull(draft.result)
        assertNull(draft.snapshot)
        assertEquals(CalculationKind.QUICK, deps.calculations.list().single().kind)
    }

    @Test
    fun `leaving writes the pending draft at once, and an editor opened on it shows what was typed`() = runTest {
        val editor = editor()
        editor.onNameChange("Лист")
        editor.onQuantityChange("4")
        editor.onThicknessChange(BigDecimal("3"))
        editor.onSheetAreaChange("2,5")
        editor.onExtraAmountChange(QuickLine.CUT, "80")
        editor.onExtraCommentChange(QuickLine.CUT, "Зачистка")

        editor.leave()

        val id = deps.calculations.list().single().id
        val again = editor(id).view
        assertFalse(again.isNew)
        assertEquals("Лист", again.name.text)
        assertEquals("4", again.quantity.text)
        assertNull(again.grade.selected)
        assertEquals("3", again.thickness.selected)
        assertEquals("2,5", again.sheetArea.text)
        assertEquals("", again.cutTime.text)
        assertEquals("80", again.cutExtra.amount.text)
        assertEquals("Зачистка", again.cutExtra.comment.text)
        assertEquals("", again.materialExtra.amount.text)
    }

    @Test
    fun `an editor that changed nothing writes nothing`() = runTest {
        val editor = editor()
        editor.leave()
        editor.fillQuickGoldenCase()
        editor.onNameChange("")
        editor.flush()
        assertEquals(1, deps.calculations.list().size)

        val before = onlyStored()
        editor(before.id).also { it.leave() }
        assertEquals(before, onlyStored())
    }

    @Test
    fun `a missing laser rate is listed as a missing price and the input stays a draft`() = runTest {
        deps.updateSettings { it.copy(laserCutPerMinute = null) }
        val editor = editor()
        editor.fillQuickGoldenCase()

        editor.calculate()

        assertEquals(emptyList(), calculated)
        assertEquals(listOf("Не задана цена: Лазер, ₽/мин"), editor.view.calcErrors.map { it.message })
        assertEquals("Заполните эти цены в «Настройках» и нажмите «Рассчитать» ещё раз. Расчёт сохранён как черновик.", editor.view.calcErrorsHint)
        assertEquals(CalculationStatus.DRAFT, onlyStored().status)

        // The rate is filled in the settings: the error goes away on the way back, and the calculation goes through.
        deps.updateSettings { it.copy(laserCutPerMinute = BigDecimal("50")) }
        editor.resume()
        assertEquals(emptyList(), editor.view.calcErrors)
        editor.calculate()
        assertEquals("5400.00", stored(calculated.single()).unitPrice?.amount?.toPlainString())
    }

    @Test
    fun `the missing rate is named with the currency of the settings`() = runTest {
        deps.updateSettings { book -> book.copy(laserCutPerMinute = null, general = book.general.copy(currencySymbol = "руб.")) }
        val editor = editor()
        editor.fillQuickGoldenCase()

        editor.calculate()

        assertEquals(listOf("Не задана цена: Лазер, руб./мин"), editor.view.calcErrors.map { it.message })
    }

    @Test
    fun `an unknown grade and a missing price per kg are listed together with the missing rate`() = runTest {
        deps.updateSettings { book ->
            book.copy(
                laserCutPerMinute = null,
                materialPricesPerKg = book.materialPricesPerKg.filterNot { it.thicknessMm.compareTo(BigDecimal("3")) == 0 },
            )
        }
        val editor = editor()
        editor.fillQuickGoldenCase()
        editor.onThicknessChange(BigDecimal("3"))

        editor.calculate()

        assertEquals(
            listOf("Не задана цена: Лазер, ₽/мин", "Нет цены в таблице «Цены материала»: строка «Ст3», столбец «3»"),
            editor.view.calcErrors.map { it.message },
        )
        assertEquals(CalculationStatus.DRAFT, onlyStored().status)

        // The grade is gone from the settings after the draft was typed.
        deps.updateSettings { book -> book.copy(grades = emptyList()) }
        editor.resume()
        editor.calculate()
        assertTrue("Марки «Ст3» нет в настройках" in editor.view.calcErrors.map { it.message })
        // With no grade to choose from, the grade field says where to add one.
        assertEquals(emptyList(), editor.view.grade.options)
    }

    @Test
    fun `an editing of a calculated record is a revision draft on its snapshot, and leaving it untouched drops it`() = runTest {
        val source = deps.saveQuickCalculated(quickGoldenInput(quantity = 10))
        val snapshot = stored(source).snapshot
        // Prices change after the calculation: the revision keeps the old ones.
        deps.updateSettings { it.copy(laserCutPerMinute = BigDecimal("80")) }
        val card = CardModel(deps.calculations, deps.settings, deps.adminPassword, deps.attachments, deps.fileOpener, source, onRevision = {}, onDeleted = {})
        val draft = card.edit()

        val untouched = editor(draft)
        assertTrue(untouched.view.priceSource.startsWith("Цены — снимок от "))
        untouched.leave()
        assertNull(deps.calculations.get(draft), "an untouched revision draft is dropped silently")

        val revision = card.edit()
        val editor = editor(revision)
        editor.onExtraAmountChange(QuickLine.CUT, "100")
        editor.calculate()

        val saved = stored(calculated.single())
        assertEquals(source, saved.sourceId)
        assertEquals(snapshot, saved.snapshot, "the old prices, not the 80 ₽/min of today")
        assertEquals("5500.00", saved.unitPrice?.amount?.toPlainString())
        assertEquals("5400.00", stored(source).unitPrice?.amount?.toPlainString(), "the source stays as it was")
    }

    @Test
    fun `a drawing attached in a new editor saves the calculation as a draft first`() = runTest {
        val file = temp.newFile("plan.pdf").toPath().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val editor = editor()
        editor.onNameChange("Лист")

        editor.attachments.attach(file)

        val draft = onlyStored()
        assertEquals("Лист", draft.name)
        assertEquals(listOf("plan.pdf"), deps.attachments.list(draft.id).map { it.fileName })
        assertEquals(listOf("plan.pdf"), editor.attachments.state.value.rows.map { it.fileName })
        // The drawing goes with a copy of the calculation.
        assertEquals(1, deps.attachments.list(deps.calculations.copy(draft.id)).size)
    }

    @Test
    fun `an editor of one kind refuses a calculation of the other`() = runTest {
        val full = deps.saveCalculated(goldenInput())
        val quick = deps.saveQuickCalculated()

        assertFailsWith<IllegalArgumentException> { editor(deps.calculations.copy(full)) }
        assertFailsWith<IllegalArgumentException> {
            EditorModel(
                deps.calculations, deps.settings, deps.attachments, deps.partTemplates, deps.fileOpener, backgroundScope,
                deps.calculations.copy(quick), onCalculated = {},
            )
        }
        assertFailsWith<IllegalArgumentException> { editor(quick) }
        assertNotNull(deps.calculations.get(full))
    }
}

/** The golden quick case: Ст3, 2 mm, 1.5 m², 12 min — 24 kg and 5 400,00 on the default prices. */
fun QuickEditorModel.fillQuickGoldenCase(name: String = "Лист на раскрой", quantity: String = "10") {
    onNameChange(name)
    onQuantityChange(quantity)
    onGradeChange("Ст3")
    onThicknessChange(BigDecimal("2"))
    onSheetAreaChange("1,5")
    onCutTimeChange("12")
}
