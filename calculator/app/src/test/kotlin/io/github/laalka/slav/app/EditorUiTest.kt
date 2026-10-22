package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.Material
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
    fun `the spreadsheet golden case entered through the editor shows 11 280,00 on the card`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            type(EditorTags.NAME, "Кронштейн")
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
            click("part/0/op/WELDING/check")
            type("part/0/op/WELDING/rows/0/length", "40")
            compose.waitForIdle()
            node("part/0/mass").assertTextEquals("15,7 кг")

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_NAME).assertTextEquals("Кронштейн")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextContains("11 280,00", substring = true)
            val saved = deps.calculations.list().single()
            assertEquals(CalculationStatus.CALCULATED, saved.status)
            assertEquals(0, BigDecimal("11280.00").compareTo(assertNotNull(saved.unitPrice).amount))
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
            assertEquals(0, BigDecimal("600").compareTo(sheet.lengthMm))
        }
    }
}
