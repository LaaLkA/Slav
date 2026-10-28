package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.editor.INNER_NOT_SMALLER
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.AppPaths
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
    fun `a ring sheet shows its mass, and the mass from the drawing replaces it with a hint`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            choose("part/0/grade", "Ст3")
            choose("part/0/size", "2")
            click("part/0/shape/RING")
            type("part/0/outer-radius", "50")
            type("part/0/inner-radius", "20")
            compose.waitForIdle()
            // π × (50² − 20²) × 2 mm = 13 194.69 mm³ = 1.319e-5 m³; × 8000 kg/m³ = 0.1056 kg.
            node("part/0/mass").assertTextEquals("0,106 кг")
            compose.onAllNodesWithTag("part/0/mass-hint").assertCountEquals(0)

            type("part/0/mass-override", "0,5")
            compose.waitForIdle()
            node("part/0/mass").assertTextEquals("0,5 кг")
            node("part/0/mass-hint").assertTextEquals("по чертежу")

            // A ring whose inner radius is not smaller than the outer one is flagged under the inner field.
            compose.onAllNodesWithText(INNER_NOT_SMALLER, useUnmergedTree = true).assertCountEquals(0)
            type("part/0/inner-radius", "60")
            compose.waitForIdle()
            compose.onAllNodesWithText(INNER_NOT_SMALLER, useUnmergedTree = true).assertCountEquals(1)
        }
    }

    @Test
    fun `input typed in the editor is stored as a draft when the editor is left`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
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
