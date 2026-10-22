package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.settings.REQUIRED
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.RoundTubeSize
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.data.AppPaths
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorModelTest {
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
        EditorModel(deps.calculations, deps.settings, backgroundScope, id, onCalculated = { calculated += it })

    private fun stored(id: UUID): StoredCalculation = assertNotNull(deps.calculations.get(id))

    private fun onlyStored(): StoredCalculation = stored(deps.calculations.list().single().id)

    private fun updateSettings(transform: (PriceBook) -> PriceBook) = deps.settings.save(transform(deps.settings.load()))

    @Test
    fun `only the operations of the chosen rolled kind are offered`() = runTest {
        updateSettings { it.copy(sortament = it.sortament.copy(roundTubes = listOf(RoundTubeSize(dec("57"), dec("3"))))) }
        val editor = editor()
        val part = editor.part().id
        editor.setChecked(LineRef.OfPart(part, OperationKind.BENDING), true)

        assertEquals(
            listOf(OperationKind.LASER_SHEET, OperationKind.HOLES, OperationKind.BENDING, OperationKind.WELDING, OperationKind.PAINTING),
            editor.part().operations.map { it.ref.kind() },
        )

        editor.onKindChange(part, SortamentKind.ROUND_TUBE)
        editor.selectSize(part, "57 × 3")

        assertEquals(
            listOf(OperationKind.TUBE_LASER, OperationKind.HOLES, OperationKind.WELDING, OperationKind.PAINTING),
            editor.part().operations.map { it.ref.kind() },
        )
        assertTrue(editor.part().operations.all { it.unavailableHint == null && !it.checked })
        // The bending checked on the sheet is gone from the input, not only from the screen.
        editor.flush()
        assertEquals(emptyList(), onlyStored().input.parts.single().operations)
    }

    @Test
    fun `the tube laser is shown but disabled for a 220 mm tube`() = runTest {
        updateSettings {
            it.copy(sortament = it.sortament.copy(roundTubes = listOf(RoundTubeSize(dec("57"), dec("3")), RoundTubeSize(dec("220"), dec("5")))))
        }
        val editor = editor()
        val part = editor.part().id
        editor.onKindChange(part, SortamentKind.ROUND_TUBE)
        editor.selectSize(part, "57 × 3")
        editor.setChecked(LineRef.OfPart(part, OperationKind.TUBE_LASER), true)
        assertTrue(editor.operation(OperationKind.TUBE_LASER).checked)

        editor.selectSize(part, "220 × 5")

        val tubeLaser = editor.operation(OperationKind.TUBE_LASER)
        assertEquals("сечение больше 200 мм", tubeLaser.unavailableHint)
        assertFalse(tubeLaser.checked)
        assertNull(tubeLaser.params)
        assertEquals("сечение больше 200 мм", editor.operation(OperationKind.HOLES).unavailableHint)
        editor.setChecked(LineRef.OfPart(part, OperationKind.TUBE_LASER), true)
        assertFalse(editor.operation(OperationKind.TUBE_LASER).checked)
        assertNull(editor.operation(OperationKind.WELDING).unavailableHint)
    }

    @Test
    fun `hole depth follows the part thickness and a narrow hole is only warned about`() = runTest {
        val editor = editor()
        editor.fillSheet(thickness = "2")
        val part = editor.part().id
        val holes = LineRef.OfPart(part, OperationKind.HOLES)
        editor.setChecked(holes, true)
        val row = editor.holes().rows.single().id
        editor.onRowLengthChange(holes, row, "1,5")

        assertEquals("2", editor.holes().depth)
        assertEquals("Диаметр меньше толщины детали (2 мм)", editor.holes().rows.single().warning)
        assertFalse(editor.holes().bothWallsAvailable)

        editor.selectSize(part, "1")

        assertEquals("1", editor.holes().depth)
        assertNull(editor.holes().rows.single().warning)
        assertTrue(editor.state.value.canCalculate, "a warning does not block the calculation")
    }

    @Test
    fun `changes are autosaved as a draft and survive leaving the editor`() = runTest {
        val editor = editor()
        editor.onNameChange("Кронштейн")
        advanceTimeBy(EditorModel.AUTOSAVE_DELAY.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(emptyList(), deps.calculations.list(), "saved only after the delay")

        advanceTimeBy(1)
        runCurrent()
        val draft = onlyStored()
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals("Кронштейн", draft.name)

        // Typed just before leaving: the pending save is written on the way out.
        editor.fillGoldenCase()
        assertFalse(editor.leave())

        val reopened = editor(draft.id)
        val state = reopened.state.value
        assertEquals("Кронштейн", state.name.text)
        val part = state.parts.single()
        assertEquals("Ст3", part.grade.selected)
        assertEquals("2", part.size.selected)
        assertEquals("1000", part.length.text)
        assertEquals("1000", part.width?.text)
        val laser = assertIs<OperationParams.LaserSheet>(reopened.operation(OperationKind.LASER_SHEET).params)
        assertEquals(CutMode.CONTOUR, laser.mode)
        assertEquals("4000", laser.contourLength.text)
        assertEquals("3", laser.pierces.text)
        val bend = assertIs<OperationParams.Bending>(reopened.operation(OperationKind.BENDING).params).rows.single()
        assertEquals("80" to "4", bend.length.text to bend.count.text)
        val welding = assertIs<OperationParams.Welding>(reopened.operation(OperationKind.WELDING).params)
        assertEquals("полуавтомат", welding.weldType.selected)
        assertEquals("40", welding.rows.single().length.text)
        assertTrue(state.canCalculate)
        assertEquals(1, deps.calculations.list().size)
    }

    @Test
    fun `an unfinished draft keeps what was filled and loads back with the rest empty`() = runTest {
        val editor = editor()
        val part = editor.part().id
        editor.onKindChange(part, SortamentKind.ANGLE)
        editor.onLengthChange(part, "750")
        editor.onQuantityChange("")
        editor.flush()

        val reopened = editor(onlyStored().id).state.value
        assertEquals("", reopened.quantity.text)
        assertEquals(REQUIRED, reopened.quantity.error)
        assertEquals(SortamentKind.ANGLE, reopened.parts.single().kind)
        assertNull(reopened.parts.single().grade.selected)
        assertNull(reopened.parts.single().size.selected)
        assertEquals("750", reopened.parts.single().length.text)
    }

    @Test
    fun `opening and leaving without a change stores nothing`() = runTest {
        assertFalse(editor().leave())
        assertEquals(emptyList(), deps.calculations.list())
    }

    @Test
    fun `calculate is enabled only when every required field is filled`() = runTest {
        val editor = editor()
        val part = editor.part()
        assertFalse(editor.state.value.canCalculate)
        assertEquals(REQUIRED, editor.state.value.name.error)
        assertEquals(NOT_CHOSEN, part.grade.error)
        assertEquals(NOT_CHOSEN, part.size.error)
        assertEquals(REQUIRED, part.length.error)
        assertEquals(REQUIRED, part.width?.error)

        editor.fillGoldenCase()
        assertTrue(editor.state.value.canCalculate)

        editor.onContourLengthChange(part.id, "")
        assertFalse(editor.state.value.canCalculate)
        assertEquals(1, editor.state.value.missingCount)
        val laser = assertIs<OperationParams.LaserSheet>(editor.operation(OperationKind.LASER_SHEET).params)
        assertEquals(REQUIRED, laser.contourLength.error)

        editor.calculate()
        assertEquals(emptyList(), calculated)
    }

    @Test
    fun `the golden case is calculated on the current prices and saved as calculated`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()

        editor.calculate()

        val id = calculated.single()
        val saved = stored(id)
        assertEquals(CalculationStatus.CALCULATED, saved.status)
        assertEquals(0, BigDecimal("11280.00").compareTo(assertNotNull(saved.unitPrice).amount))
        assertEquals(deps.settings.load(), saved.snapshot)
        val sheet = assertIs<Material.Sheet>(saved.input.parts.single().material)
        assertEquals(0, sheet.thicknessMm.compareTo(dec("2")))
        val laser = assertIs<PartOperation.LaserSheet>(saved.input.parts.single().operations.first())
        assertEquals(SheetCutMode.ContourLength(dec("4000"), 3), laser.mode)
    }

    @Test
    fun `a missing price is listed in readable Russian and the calculation stays a draft`() = runTest {
        updateSettings { book ->
            book.copy(
                laserSheetCutPerCm = book.laserSheetCutPerCm.filterNot { it.thicknessMm.compareTo(dec("2")) == 0 },
                bendingPerBend = book.bendingPerBend.filterNot { it.thicknessMm.compareTo(dec("2")) == 0 },
            )
        }
        val editor = editor()
        editor.fillGoldenCase()

        editor.calculate()

        assertEquals(emptyList(), calculated)
        val errors = editor.state.value.calcErrors
        assertEquals(
            listOf(
                ReadableError(
                    "Нет цены в таблице «Лазер, лист»: строка «Сталь», столбец «2»",
                    MissingCell("Лазер, лист", "Сталь", "2", SettingsTab.OPERATION_PRICES),
                ),
                ReadableError(
                    "Нет цены в таблице «Гибка — Сталь»: строка «2», столбец «до 100»",
                    MissingCell("Гибка — Сталь", "2", "до 100", SettingsTab.OPERATION_PRICES),
                ),
            ),
            errors,
        )
        val draft = onlyStored()
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertNull(draft.result)
        assertEquals("Кронштейн", draft.name)
        assertNotNull(editor.state.value.calcErrorsHint)

        editor.onNameChange("Кронштейн усиленный")
        assertEquals(emptyList(), editor.state.value.calcErrors, "an edit clears the errors of the last press")
    }

    @Test
    fun `editing a calculated calculation recalculates on its snapshot, a copy on the current prices`() = runTest {
        val id = calculateGoldenCase()
        // Laser 2 mm goes from 20 to 30 ₽/cm: 4000 mm of cut costs 4000 more on the current prices.
        updateSettings { book ->
            book.copy(laserSheetCutPerCm = book.laserSheetCutPerCm.map { if (it.thicknessMm.compareTo(dec("2")) == 0) it.copy(price = dec("30")) else it })
        }

        val editor = editor(id)
        assertTrue(editor.state.value.priceSource.startsWith("Цены — снимок от"), editor.state.value.priceSource)
        editor.onQuantityChange("5")
        editor.calculate()

        assertEquals(listOf(id, id), calculated)
        val recalculated = stored(id)
        assertEquals(0, BigDecimal("11280.00").compareTo(assertNotNull(recalculated.unitPrice).amount))
        assertEquals(5, recalculated.quantity)
        assertEquals(PriceBook.default(), recalculated.snapshot)

        val copy = deps.calculations.copy(id)
        val copyEditor = editor(copy)
        assertEquals("Цены — текущие настройки", copyEditor.state.value.priceSource)
        copyEditor.calculate()
        assertEquals(0, BigDecimal("15280.00").compareTo(assertNotNull(stored(copy).unitPrice).amount))
    }

    @Test
    fun `leaving an edited calculated calculation asks, and discarding restores the previous result`() = runTest {
        val id = calculateGoldenCase()
        val before = stored(id)
        val editor = editor(id)
        editor.onNameChange("Кронштейн изменённый")

        assertTrue(editor.leave())
        val draft = stored(id)
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals("Кронштейн изменённый", draft.name)

        editor.discardChanges()

        assertEquals(before, stored(id))
    }

    @Test
    fun `keeping the edit leaves a draft that recalculates on the old snapshot`() = runTest {
        val id = calculateGoldenCase()
        val editor = editor(id)
        editor.onQuantityChange("3")

        assertTrue(editor.leave())

        val draft = stored(id)
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(3, draft.quantity)
        assertEquals(PriceBook.default(), draft.snapshot)
        assertEquals(false, editor(id).leave(), "a draft is left without a question")
    }

    @Test
    fun `an edit undone by hand restores the calculation without asking`() = runTest {
        val id = calculateGoldenCase()
        val before = stored(id)
        val editor = editor(id)
        editor.onNameChange("Кронштейн 2")
        editor.flush()
        editor.onNameChange("Кронштейн")

        assertFalse(editor.leave())

        assertEquals(before, stored(id))
    }

    @Test
    fun `a copied part is a separate part with the same material and operations`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()

        editor.copyPart(editor.part().id)

        val (original, copy) = editor.state.value.parts
        assertEquals("Деталь 1 (копия)", copy.name.text)
        assertEquals(original.size.selected, copy.size.selected)
        assertEquals(original.operations.map { it.checked }, copy.operations.map { it.checked })
        editor.onLengthChange(copy.id, "500")
        assertEquals("1000", editor.state.value.parts.first().length.text)
        editor.flush()
        val input: CalculationInput = onlyStored().input
        assertEquals(2, input.parts.size)
    }

    private fun TestScope.calculateGoldenCase(): UUID {
        val editor = editor()
        editor.fillGoldenCase()
        editor.calculate()
        return calculated.last()
    }
}

fun dec(value: String) = BigDecimal(value)

private fun LineRef.kind(): OperationKind = when (this) {
    is LineRef.OfPart -> kind
    is LineRef.OfProduct -> kind
    is LineRef.MaterialOf -> error("A material line has no operation")
}

fun EditorModel.part(index: Int = 0): PartView = state.value.parts[index]

fun EditorModel.operation(kind: OperationKind, part: Int = 0): OperationView =
    part(part).operations.single { (it.ref as LineRef.OfPart).kind == kind }

fun EditorModel.holes(): OperationParams.Holes = assertIs(operation(OperationKind.HOLES).params)

fun EditorModel.selectSize(part: Long, label: String) {
    val view = state.value.parts.single { it.id == part }
    onSizeChange(part, view.sizeOptions[view.size.options.indexOf(label)])
}

/** A sheet of Ст3, 1000 × 1000, of [thickness]. */
fun EditorModel.fillSheet(thickness: String) {
    onNameChange("Кронштейн")
    val part = part().id
    onGradeChange(part, "Ст3")
    selectSize(part, thickness)
    onLengthChange(part, "1000")
    onWidthChange(part, "1000")
}

/**
 * The golden case of the old spreadsheet: sheet Ст3 2 mm 1000 × 1000, laser contour 4000 mm with
 * 3 pierces, 4 bends of 80 mm, 40 mm of semi-automatic welding — 11 280,00 on the default prices.
 */
fun EditorModel.fillGoldenCase() {
    fillSheet(thickness = "2")
    val part = part().id
    val laser = LineRef.OfPart(part, OperationKind.LASER_SHEET)
    setChecked(laser, true)
    onCutModeChange(part, CutMode.CONTOUR)
    onContourLengthChange(part, "4000")
    onPiercesChange(part, "3")
    val bending = LineRef.OfPart(part, OperationKind.BENDING)
    setChecked(bending, true)
    val bend = assertIs<OperationParams.Bending>(operation(OperationKind.BENDING).params).rows.single().id
    onRowLengthChange(bending, bend, "80")
    onRowCountChange(bending, bend, "4")
    val welding = LineRef.OfPart(part, OperationKind.WELDING)
    setChecked(welding, true)
    val seam = assertIs<OperationParams.Welding>(operation(OperationKind.WELDING).params).rows.single().id
    onRowLengthChange(welding, seam, "40")
}
