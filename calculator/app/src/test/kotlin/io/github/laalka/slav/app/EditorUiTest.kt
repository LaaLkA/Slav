package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.editor.INNER_NOT_SMALLER
import io.github.laalka.slav.app.settings.REQUIRED
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class EditorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    /** Most of the editor is below the fold; a node of its scrolling body is scrolled into view before it is used. */
    private fun type(tag: String, text: String) {
        node(tag).performScrollTo().performTextReplacement(text)
    }

    private fun click(tag: String) {
        node(tag).performScrollTo().performClick()
    }

    private fun choose(tag: String, option: String) {
        click(tag)
        node("$tag/$option").performClick()
    }

    @Test
    fun `the golden case entered through the editor shows 11 340,00 and 113 400,00 on the card`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            type(EditorTags.NAME, "Кронштейн")
            type(EditorTags.QUANTITY, "10")
            choose("part/0/grade", "Ст3")
            choose("part/0/size", "2")
            type("part/0/length", "1000")
            type("part/0/width", "1000")
            click("part/0/op/LASER_SHEET/check")
            click("part/0/op/LASER_SHEET/mode/CONTOUR")
            type("part/0/op/LASER_SHEET/contour-length", "4000")
            type("part/0/op/LASER_SHEET/pierces", "3")
            click("part/0/op/BENDING/check")
            type("part/0/op/BENDING/rows/0/length", "80")
            type("part/0/op/BENDING/rows/0/count", "4")
            click("product/op/WELDING/check")
            choose("product/op/WELDING/group", "Сталь")
            choose("product/op/WELDING/thickness", "2")
            type("product/op/WELDING/seam-length", "40")
            compose.waitForIdle()
            node("part/0/mass").assertTextEquals("16 кг")

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_NAME).assertTextEquals("Кронштейн")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextContains("11 340,00", substring = true)
            node(CalculationTags.CARD_TOTAL).assertTextContains("113 400,00", substring = true)
            val saved = deps.calculations.list().single()
            assertEquals(CalculationStatus.CALCULATED, saved.status)
            assertEquals(10, saved.quantity)
            assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(saved.unitPrice).amount))
        }
    }

    @Test
    fun `a ring sheet shows its computed mass and offers no weight from the drawing, and its inner radius is checked`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            choose("part/0/grade", "Ст3")
            choose("part/0/size", "2")
            click("part/0/shape/RING")
            type("part/0/outer-radius", "50")
            type("part/0/inner-radius", "20")
            compose.waitForIdle()
            // π × (50² − 20²) × 2 mm = 13 194.69 mm³ = 1.319e-5 m³; × 8000 kg/m³ = 0.1056 kg.
            node("part/0/mass").assertTextEquals("0,106 кг")
            compose.onAllNodesWithTag("part/0/mass-hint").assertCountEquals(0)

            // A new sheet is set by its dimensions or by its weight; the weight from the drawing is not offered on top.
            compose.onAllNodesWithTag("part/0/mass-override").assertCountEquals(0)

            // A ring whose inner radius is not smaller than the outer one is flagged under the inner field.
            compose.onAllNodesWithText(INNER_NOT_SMALLER, useUnmergedTree = true).assertCountEquals(0)
            type("part/0/inner-radius", "60")
            compose.waitForIdle()
            compose.onAllNodesWithText(INNER_NOT_SMALLER, useUnmergedTree = true).assertCountEquals(1)
        }
    }

    @Test
    fun `the mass from the drawing of a long product replaces its mass with a hint`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            click("part/0/kind/ANGLE")
            choose("part/0/grade", "Ст3")
            choose("part/0/size", "50×50×5")
            type("part/0/length", "2000")
            compose.waitForIdle()
            // 3.77 kg/m × 2 m = 7.54 kg.
            node("part/0/mass").assertTextEquals("7,54 кг")
            compose.onAllNodesWithTag("part/0/mass-hint").assertCountEquals(0)

            type("part/0/mass-override", "0,5")
            compose.waitForIdle()
            node("part/0/mass").assertTextEquals("0,5 кг")
            node("part/0/mass-hint").assertTextEquals("по чертежу")
        }
    }

    @Test
    fun `a sheet set by weight has no length and width, its perimeter is asked only for the cut by shape, and it calculates`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            type(EditorTags.NAME, "Пластина")
            type(EditorTags.QUANTITY, "10")
            choose("part/0/grade", "Ст3")
            choose("part/0/size", "2")
            // A rectangle asks for its length and width: two fields missing.
            compose.onNodeWithText("Заполните выделенные поля: 2").assertExists()

            click("part/0/shape/BY_MASS")
            compose.waitForIdle()
            compose.onAllNodesWithTag("part/0/length").assertCountEquals(0)
            compose.onAllNodesWithTag("part/0/width").assertCountEquals(0)
            compose.onAllNodesWithTag("part/0/mass-override").assertCountEquals(0)
            // Only the weight is missing now; the length and the width are not counted.
            compose.onNodeWithText("Заполните выделенные поля: 1").assertExists()

            type("part/0/weight", "16")
            compose.waitForIdle()
            node("part/0/mass").assertTextEquals("16 кг")
            node("part/0/mass-hint").assertTextEquals("по весу")
            compose.onNodeWithText("Все обязательные поля заполнены").assertExists()

            // The laser starts «По форме»: the cut is as long as the perimeter, so the perimeter is flagged until it is typed.
            click("part/0/op/LASER_SHEET/check")
            compose.waitForIdle()
            compose.onAllNodesWithText(REQUIRED, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithText("Заполните выделенные поля: 1").assertExists()
            type("part/0/perimeter", "3000")
            compose.waitForIdle()
            compose.onAllNodesWithText(REQUIRED, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("Все обязательные поля заполнены").assertExists()

            // «Длина контура» needs no perimeter, only its own length.
            click("part/0/op/LASER_SHEET/mode/CONTOUR")
            type("part/0/op/LASER_SHEET/contour-length", "4000")
            type("part/0/op/LASER_SHEET/pierces", "3")
            compose.waitForIdle()
            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_NAME).assertTextEquals("Пластина")
            // 16 kg × 200 ₽/kg = 3200.00; 4000 mm = 400 cm × 20 ₽/cm = 8000.00; 3 pierces × 0 = 0 → 11 200,00; × 10 = 112 000,00.
            node(CalculationTags.CARD_UNIT_PRICE).assertTextContains("11 200,00", substring = true)
            node(CalculationTags.CARD_TOTAL).assertTextContains("112 000,00", substring = true)
            val saved = assertNotNull(deps.calculations.get(deps.calculations.list().single().id))
            assertEquals(CalculationStatus.CALCULATED, saved.status)
            val sheet = assertIs<Material.Sheet>(saved.input.parts.single().material)
            assertEquals(SheetShape.ByMass(BigDecimal("16"), BigDecimal("3000")), sheet.shape)
        }
    }

    @Test
    fun `a saved sheet with a weight from the drawing shows that field with its value, a new sheet does not`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            val legacy = goldenInput().parts.single().copy(massOverrideKg = BigDecimal("10"))
            deps.saveCalculated(goldenInput("Старый расчёт").copy(parts = listOf(legacy)))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.row(0)).performClick()
            node(CalculationTags.CARD_EDIT).performClick()
            compose.waitForIdle()

            node("part/0/mass-override").performScrollTo().assertTextContains("10", substring = true)
            node("part/0/mass").assertTextEquals("10 кг")
            node("part/0/mass-hint").assertTextEquals("по чертежу")
            // Cleared, the field goes and the computed mass of the dimensions is back.
            type("part/0/mass-override", "")
            compose.waitForIdle()
            compose.onAllNodesWithTag("part/0/mass-override").assertCountEquals(0)
            node("part/0/mass").assertTextEquals("16 кг")

            click(EditorTags.ADD_PART)
            compose.waitForIdle()
            compose.onAllNodesWithTag("part/1/mass-override").assertCountEquals(0)
        }
    }

    @Test
    fun `input typed in the editor is stored as a draft when the editor is left`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            type(EditorTags.NAME, "Рама")
            choose("part/0/grade", "Ст3")
            type("part/0/length", "600")
            // Straight away, well within the autosave delay.
            node(NAV_CALCULATIONS_TAG).performClick()
            compose.waitForIdle()

            val draft = assertNotNull(deps.calculations.get(deps.calculations.list().single().id))
            assertEquals(CalculationStatus.DRAFT, draft.status)
            assertEquals("Рама", draft.name)
            val sheet = assertIs<Material.Sheet>(draft.input.parts.single().material)
            assertEquals("Ст3", sheet.grade)
            assertEquals(0, BigDecimal("600").compareTo(assertIs<SheetShape.Rectangle>(sheet.shape).lengthMm))
        }
    }
}
