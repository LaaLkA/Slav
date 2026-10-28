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
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.RoundTubeSize
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
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
            listOf(OperationKind.LASER_SHEET, OperationKind.HOLES, OperationKind.BENDING),
            editor.part().operations.map { it.ref.kind() },
        )

        editor.onKindChange(part, SortamentKind.ROUND_TUBE)
        editor.selectSize(part, "57 × 3")

        assertEquals(
            listOf(OperationKind.TUBE_LASER, OperationKind.HOLES),
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
    }

    @Test
    fun `welding and painting cannot be checked on a part, they are operations of the product`() = runTest {
        val editor = editor()
        val part = editor.part().id

        editor.setChecked(LineRef.OfPart(part, OperationKind.WELDING), true)
        editor.setChecked(LineRef.OfPart(part, OperationKind.PAINTING), true)

        assertTrue(editor.part().operations.none { it.checked })
        editor.flush()
        assertEquals(emptyList(), deps.calculations.list(), "nothing was changed, nothing is saved")
        assertEquals(
            listOf(OperationKind.WELDING, OperationKind.PAINTING),
            editor.state.value.productOperations.map { (it.ref as LineRef.OfProduct).kind },
        )
    }

    @Test
    fun `a narrow hole is only warned about, by the part thickness`() = runTest {
        val editor = editor()
        editor.fillSheet(thickness = "2")
        val part = editor.part().id
        val holes = LineRef.OfPart(part, OperationKind.HOLES)
        editor.setChecked(holes, true)
        val row = editor.holes().rows.single().id
        editor.onRowLengthChange(holes, row, "1,5")

        assertEquals("Диаметр меньше толщины детали (2 мм)", editor.holes().rows.single().warning)
        assertFalse(editor.holes().bothWallsAvailable)

        editor.selectSize(part, "1")

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
        assertEquals(ShapeKind.RECTANGLE, part.shape)
        assertEquals("1000", part.dimension(DimensionField.LENGTH).text)
        assertEquals("1000", part.dimension(DimensionField.WIDTH).text)
        val laser = assertIs<OperationParams.LaserSheet>(reopened.operation(OperationKind.LASER_SHEET).params)
        assertEquals(CutMode.CONTOUR, laser.mode)
        assertEquals("4000", laser.contourLength.text)
        assertEquals("3", laser.pierces.text)
        val bend = assertIs<OperationParams.Bending>(reopened.operation(OperationKind.BENDING).params).rows.single()
        assertEquals("80" to "4", bend.length.text to bend.count.text)
        val welding = assertIs<OperationParams.AssemblyWelding>(reopened.product(OperationKind.WELDING).params)
        assertEquals("полуавтомат", welding.weldType.selected)
        assertEquals("Сталь", welding.group.selected)
        assertEquals("2", welding.thickness.selected)
        assertEquals("40", welding.seamLength.text)
        assertTrue(state.canCalculate)
        assertEquals(1, deps.calculations.list().size)
    }

    @Test
    fun `an unfinished draft keeps what was filled and loads back with the rest empty`() = runTest {
        val editor = editor()
        val part = editor.part().id
        editor.onKindChange(part, SortamentKind.ANGLE)
        editor.onDimensionChange(part, DimensionField.LENGTH, "750")
        editor.onQuantityChange("")
        editor.flush()

        val reopened = editor(onlyStored().id).state.value
        assertEquals("", reopened.quantity.text)
        assertEquals(REQUIRED, reopened.quantity.error)
        assertEquals(SortamentKind.ANGLE, reopened.parts.single().kind)
        assertNull(reopened.parts.single().grade.selected)
        assertNull(reopened.parts.single().size.selected)
        assertEquals("750", reopened.parts.single().dimension(DimensionField.LENGTH).text)
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
        assertEquals(REQUIRED, part.dimension(DimensionField.LENGTH).error)
        assertEquals(REQUIRED, part.dimension(DimensionField.WIDTH).error)

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
        assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(saved.unitPrice).amount))
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
        assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(recalculated.unitPrice).amount))
        assertEquals(5, recalculated.quantity)
        assertEquals(PriceBook.default(), recalculated.snapshot)

        val copy = deps.calculations.copy(id)
        val copyEditor = editor(copy)
        assertEquals("Цены — текущие настройки", copyEditor.state.value.priceSource)
        copyEditor.calculate()
        assertEquals(0, BigDecimal("15340.00").compareTo(assertNotNull(stored(copy).unitPrice).amount))
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
        editor.onDimensionChange(copy.id, DimensionField.LENGTH, "500")
        assertEquals("1000", editor.state.value.parts.first().dimension(DimensionField.LENGTH).text)
        editor.flush()
        val input: CalculationInput = onlyStored().input
        assertEquals(2, input.parts.size)
    }

    // Sheet shapes

    @Test
    fun `a sheet offers four shapes and each asks only for its own dimensions`() = runTest {
        val editor = editor()
        val part = editor.part().id
        fun shown() = editor.part().dimensions.map { it.field }

        assertEquals(ShapeKind.RECTANGLE, editor.part().shape)
        assertEquals(listOf(DimensionField.LENGTH, DimensionField.WIDTH), shown())
        editor.onShapeChange(part, ShapeKind.CIRCLE)
        assertEquals(listOf(DimensionField.RADIUS), shown())
        editor.onShapeChange(part, ShapeKind.RING)
        assertEquals(listOf(DimensionField.OUTER_RADIUS, DimensionField.INNER_RADIUS), shown())
        editor.onShapeChange(part, ShapeKind.FREE)
        assertEquals(listOf(DimensionField.AREA, DimensionField.PERIMETER), shown())
        assertEquals(listOf("Площадь, мм²", "Периметр реза, мм"), editor.part().dimensions.map { it.field.label })
        assertEquals(
            listOf("Прямоугольник / полоса", "Круг", "Кольцо", "Произвольная"),
            ShapeKind.entries.map { it.title },
        )

        editor.onKindChange(part, SortamentKind.ANGLE)
        assertNull(editor.part().shape, "only a sheet has a shape")
        assertEquals(listOf(DimensionField.LENGTH), shown())
    }

    @Test
    fun `the dimensions of a shape that is not chosen never block the calculation`() = runTest {
        val editor = editor()
        editor.onNameChange("Диск")
        val part = editor.part().id
        editor.onGradeChange(part, "Ст3")
        editor.selectSize(part, "2")
        editor.onShapeChange(part, ShapeKind.CIRCLE)
        // The rectangle's length and width are empty and hidden; the radius is required.
        assertEquals(REQUIRED, editor.part().dimension(DimensionField.RADIUS).error)
        assertFalse(editor.state.value.canCalculate)

        editor.onDimensionChange(part, DimensionField.RADIUS, "50")

        assertTrue(editor.state.value.canCalculate)
        editor.calculate()
        val sheet = assertIs<Material.Sheet>(stored(calculated.single()).input.parts.single().material)
        assertEquals(SheetShape.Circle(dec("50")), sheet.shape)
    }

    @Test
    fun `a ring needs an inner radius smaller than the outer one`() = runTest {
        val editor = editor()
        val part = editor.part().id
        editor.onShapeChange(part, ShapeKind.RING)
        editor.onDimensionChange(part, DimensionField.OUTER_RADIUS, "50")
        fun inner() = editor.part().dimension(DimensionField.INNER_RADIUS)

        assertEquals(REQUIRED, inner().error)
        editor.onDimensionChange(part, DimensionField.INNER_RADIUS, "50")
        assertEquals(INNER_NOT_SMALLER, inner().error)
        editor.onDimensionChange(part, DimensionField.INNER_RADIUS, "60")
        assertEquals(INNER_NOT_SMALLER, inner().error)
        assertNull(editor.part().dimension(DimensionField.OUTER_RADIUS).error)
        editor.onDimensionChange(part, DimensionField.INNER_RADIUS, "20")
        assertNull(inner().error)
        editor.onDimensionChange(part, DimensionField.INNER_RADIUS, "49,5")
        assertNull(inner().error)
    }

    @Test
    fun `every shape is stored as a draft and loads back as it was typed`() = runTest {
        val editor = editor()
        editor.onNameChange("Формы")
        fun typed(part: Int, shape: ShapeKind, vararg values: Pair<DimensionField, String>) {
            if (part > 0) editor.addPart()
            val id = editor.part(part).id
            editor.onShapeChange(id, shape)
            values.forEach { (field, text) -> editor.onDimensionChange(id, field, text) }
        }
        typed(0, ShapeKind.RECTANGLE, DimensionField.LENGTH to "300", DimensionField.WIDTH to "200")
        typed(1, ShapeKind.CIRCLE, DimensionField.RADIUS to "50")
        typed(2, ShapeKind.RING, DimensionField.OUTER_RADIUS to "50", DimensionField.INNER_RADIUS to "20")
        typed(3, ShapeKind.FREE, DimensionField.AREA to "12000,5", DimensionField.PERIMETER to "500")
        editor.flush()

        assertEquals(
            listOf(
                SheetShape.Rectangle(dec("300"), dec("200")),
                SheetShape.Circle(dec("50")),
                SheetShape.Ring(dec("50"), dec("20")),
                SheetShape.Free(dec("12000.5"), dec("500")),
            ),
            onlyStored().input.parts.map { assertIs<Material.Sheet>(it.material).shape },
        )
        val reopened = editor(onlyStored().id).state.value.parts
        assertEquals(
            listOf(ShapeKind.RECTANGLE, ShapeKind.CIRCLE, ShapeKind.RING, ShapeKind.FREE),
            reopened.map { it.shape },
        )
        assertEquals(
            listOf(listOf("300", "200"), listOf("50"), listOf("50", "20"), listOf("12000,5", "500")),
            reopened.map { part -> part.dimensions.map { it.value.text } },
        )
    }

    @Test
    fun `leaving a sheet for a tube drops the shape and the sheet dimensions, the length stays`() = runTest {
        val editor = editor()
        val part = editor.part().id
        editor.onShapeChange(part, ShapeKind.RING)
        editor.onDimensionChange(part, DimensionField.OUTER_RADIUS, "50")
        editor.onDimensionChange(part, DimensionField.LENGTH, "700")

        editor.onKindChange(part, SortamentKind.ROUND_TUBE)
        editor.onKindChange(part, SortamentKind.SHEET)

        assertEquals(ShapeKind.RECTANGLE, editor.part().shape)
        assertEquals(
            listOf("700", ""),
            editor.part().dimensions.map { it.value.text },
            "the length is the one dimension a sheet shares with a tube",
        )
    }

    @Test
    fun `the laser by shape is the default mode and prices the cut of the chosen shape`() = runTest {
        val editor = editor()
        editor.fillSheet(thickness = "4")
        val part = editor.part().id
        editor.onShapeChange(part, ShapeKind.RING)
        editor.onDimensionChange(part, DimensionField.OUTER_RADIUS, "50")
        editor.onDimensionChange(part, DimensionField.INNER_RADIUS, "20")
        editor.setChecked(LineRef.OfPart(part, OperationKind.LASER_SHEET), true)

        assertEquals(CutMode.BY_SHAPE, assertIs<OperationParams.LaserSheet>(editor.operation(OperationKind.LASER_SHEET).params).mode)
        assertEquals(listOf("По форме", "Длина контура"), CutMode.entries.map { it.title })
        assertTrue(editor.state.value.canCalculate)
        editor.calculate()

        val saved = stored(calculated.single())
        assertEquals(SheetCutMode.ByShape, assertIs<PartOperation.LaserSheet>(saved.input.parts.single().operations.single()).mode)
        // Ring R = 50, r = 20 at 4 mm: 2 × π × 70 = 439.82… mm = 43.98… cm × 40 + 2 pierces × 10 = 1779.29.
        assertEquals("1779.29", assertNotNull(saved.result).parts.single().operations.single().cost.amount.toPlainString())
    }

    // Mass from the drawing

    @Test
    fun `the mass from the drawing replaces the computed mass, says so, and drives the price`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()
        val part = editor.part().id
        assertEquals("16 кг", editor.part().mass)
        assertFalse(editor.part().massFromDrawing)
        assertEquals("", editor.part().massOverride.text)

        editor.onMassOverrideChange(part, "10")

        assertEquals("10 кг", editor.part().mass)
        assertTrue(editor.part().massFromDrawing)
        editor.calculate()
        val saved = stored(calculated.single())
        assertEquals(0, dec("10").compareTo(assertNotNull(saved.input.parts.single().massOverrideKg)))
        val result = assertNotNull(saved.result)
        assertEquals(0, dec("10").compareTo(result.parts.single().massKg))
        assertEquals(0, dec("10").compareTo(result.unitMassKg))
        // 10 kg × 200 ₽/kg = 2000.00 instead of 3200.00: 2000 + 8000 + 0 + 100 + 40 = 10140.00.
        assertEquals("2000.00", result.parts.single().material.cost.amount.toPlainString())
        assertEquals("10140.00", result.unitPrice.amount.toPlainString())

        val reopened = editor(saved.id).state.value.parts.single()
        assertEquals("10", reopened.massOverride.text)
        assertEquals("10 кг", reopened.mass)
    }

    @Test
    fun `the mass from the drawing is optional for every kind, shows without a complete material, and a wrong one blocks`() = runTest {
        val editor = editor()
        val part = editor.part().id
        editor.onKindChange(part, SortamentKind.I_BEAM)

        // Nothing is chosen yet: no mass, and no error for the optional field.
        assertNull(editor.part().mass)
        assertNull(editor.part().massOverride.error)
        editor.onMassOverrideChange(part, "7,5")
        assertEquals("7,5 кг", editor.part().mass)
        assertTrue(editor.part().massFromDrawing)

        editor.onMassOverrideChange(part, "abc")
        assertNotNull(editor.part().massOverride.error)
        assertNull(editor.part().mass)
        assertFalse(editor.part().massFromDrawing)
        editor.onMassOverrideChange(part, "0")
        assertNotNull(editor.part().massOverride.error, "zero is not a mass")
        editor.onMassOverrideChange(part, "")
        assertNull(editor.part().massOverride.error)
    }

    // Product operations

    @Test
    fun `assembly welding asks for type, group, thickness and one seam length`() = runTest {
        val editor = editor()
        editor.fillSheet(thickness = "2")
        editor.setChecked(LineRef.OfProduct(OperationKind.WELDING), true)

        val welding = assertIs<OperationParams.AssemblyWelding>(editor.product(OperationKind.WELDING).params)
        assertEquals("полуавтомат", welding.weldType.selected)
        assertEquals(NOT_CHOSEN, welding.group.error)
        assertEquals(NOT_CHOSEN, welding.thickness.error)
        assertEquals(REQUIRED, welding.seamLength.error)
        assertFalse(editor.state.value.canCalculate)

        editor.onAssemblyWeldTypeChange("аргон")
        editor.onAssemblyGroupChange("Сталь")
        editor.onAssemblyThicknessChange(dec("3"))
        editor.onSeamLengthChange("561,5")

        val filled = assertIs<OperationParams.AssemblyWelding>(editor.product(OperationKind.WELDING).params)
        assertEquals("аргон" to "Сталь", filled.weldType.selected to filled.group.selected)
        assertEquals("3", filled.thickness.selected)
        assertNull(filled.seamLength.error)
        assertTrue(editor.state.value.canCalculate)
        editor.flush()
        assertEquals(
            ProductOperation.AssemblyWelding("аргон", "Сталь", dec("3"), dec("561.5")),
            onlyStored().input.productOperations.single(),
        )
    }

    @Test
    fun `product painting shows the area with the minimum applied and the hint from the settings`() = runTest {
        val editor = editor()
        fun addFree(part: Int, areaMm2: String) {
            if (part > 0) editor.addPart()
            val id = editor.part(part).id
            editor.onGradeChange(id, "Ст3")
            editor.selectSize(id, "2")
            editor.onShapeChange(id, ShapeKind.FREE)
            editor.onDimensionChange(id, DimensionField.AREA, areaMm2)
            editor.onDimensionChange(id, DimensionField.PERIMETER, "1000")
        }
        // 2 × 24 000 + 1000 × 2 = 0.05 m² and 2 × 149 000 + 1000 × 2 = 0.30 m².
        addFree(0, "24000")
        addFree(1, "149000")
        editor.setChecked(LineRef.OfProduct(OperationKind.PAINTING), true)
        fun painting() = assertIs<OperationParams.Painting>(editor.product(OperationKind.PAINTING).params)

        assertEquals("0,4 м²", painting().autoArea, "0.05 counts as 0.1: 0.1 + 0.30")
        assertEquals("детали меньше 0,1 м² считаются как 0,1 м²", painting().minAreaHint)

        updateSettings { it.copy(general = it.general.copy(minPaintAreaPerPartM2 = dec("0.5"))) }
        editor.resume()

        assertEquals("1 м²", painting().autoArea, "0.5 + 0.5")
        assertEquals("детали меньше 0,5 м² считаются как 0,5 м²", painting().minAreaHint)

        editor.onNameChange("Покраска")
        editor.calculate()
        // 1 m² × 450 ₽/m² = 450.00.
        assertEquals("450.00", assertNotNull(stored(calculated.single()).result).productLines.single().cost.amount.toPlainString())
    }

    @Test
    fun `product painting waits for the dimensions of every part and takes an area override`() = runTest {
        val editor = editor()
        editor.setChecked(LineRef.OfProduct(OperationKind.PAINTING), true)
        fun painting() = assertIs<OperationParams.Painting>(editor.product(OperationKind.PAINTING).params)
        assertNull(painting().autoArea)

        editor.fillSheet(thickness = "2")
        // 2 × (1000 × 1000 + 1000 × 2 + 1000 × 2) = 2.008 m².
        assertEquals("2,008 м²", painting().autoArea)

        editor.onAreaOverrideChange(LineRef.OfProduct(OperationKind.PAINTING), "3")
        editor.flush()
        assertEquals(ProductOperation.Painting(dec("3")), onlyStored().input.productOperations.single())
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

fun PartView.dimension(field: DimensionField): FieldView = dimensions.single { it.field == field }.value

fun EditorModel.operation(kind: OperationKind, part: Int = 0): OperationView =
    part(part).operations.single { (it.ref as LineRef.OfPart).kind == kind }

fun EditorModel.product(kind: OperationKind): OperationView =
    state.value.productOperations.single { (it.ref as LineRef.OfProduct).kind == kind }

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
    onDimensionChange(part, DimensionField.LENGTH, "1000")
    onDimensionChange(part, DimensionField.WIDTH, "1000")
}

/**
 * The golden case: sheet Ст3 2 mm 1000 × 1000, laser contour 4000 mm with 3 pierces, 4 bends of
 * 80 mm, and the assembly welding of a 40 mm seam (semi-automatic, steel, 2 mm) — 16,00 kg and
 * 11 340,00 on the default prices.
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
    setChecked(LineRef.OfProduct(OperationKind.WELDING), true)
    onAssemblyGroupChange("Сталь")
    onAssemblyThicknessChange(dec("2"))
    onSeamLengthChange("40")
}
