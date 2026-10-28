package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.QUICK_CHIP_TEXT
import io.github.laalka.slav.app.calculations.QUICK_CUT_TITLE
import io.github.laalka.slav.app.calculations.QUICK_MATERIAL_TITLE
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.quick.QuickTags
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.QuickCalculationInput
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class QuickUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    /** The editor is longer than the window; a node of its scrolling body is scrolled into view before it is used. */
    private fun type(tag: String, text: String) {
        node(tag).performScrollTo().performTextReplacement(text)
    }

    private fun choose(tag: String, option: String) {
        node(tag).performScrollTo().performClick()
        node("$tag/$option").performClick()
    }

    private fun startQuick() {
        node(CalculationTags.NEW).performClick()
        node(CalculationTags.newOf(CalculationKind.QUICK)).performClick()
    }

    @Test
    fun `the golden quick case entered through the quick editor shows 5 400,00 and 54 000,00 on the card`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            startQuick()
            type(QuickTags.NAME, "Лист на раскрой")
            type(QuickTags.QUANTITY, "10")
            choose(QuickTags.GRADE, "Ст3")
            choose(QuickTags.THICKNESS, "2")
            type(QuickTags.SHEET_AREA, "1,5")
            type(QuickTags.CUT_TIME, "12")
            compose.waitForIdle()
            node(QuickTags.MASS).assertTextEquals("24 кг")

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_NAME).assertTextEquals("Лист на раскрой")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextContains("5 400,00", substring = true)
            node(CalculationTags.CARD_TOTAL).assertTextContains("54 000,00", substring = true)
            node(CalculationTags.CARD_UNIT_MASS).assertTextEquals("24 кг")
            compose.onNodeWithText(QUICK_MATERIAL_TITLE).assertExists()
            compose.onNodeWithText(QUICK_CUT_TITLE).assertExists()
            val saved = assertIs<StoredCalculation.Quick>(deps.calculations.get(deps.calculations.list().single().id))
            assertEquals(CalculationStatus.CALCULATED, saved.status)
            assertEquals(10, saved.quantity)
            assertEquals(0, BigDecimal("5400.00").compareTo(assertNotNull(saved.unitPrice).amount))
            assertEquals(
                QuickCalculationInput("Лист на раскрой", 10, "Ст3", BigDecimal("2"), BigDecimal("1.5"), BigDecimal("12")).copy(),
                saved.input,
            )
        }
    }

    @Test
    fun `Новый расчёт offers both kinds, and the list marks the simplified one with a chip`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).assertExists()
            compose.onNodeWithText("Полный расчёт").assertExists()
            compose.onNodeWithText("Упрощённый расчёт").assertExists()
            node(CalculationTags.newOf(CalculationKind.QUICK)).performClick()
            type(QuickTags.NAME, "Лист")
            node(NAV_CALCULATIONS_TAG).performClick()
            compose.waitForIdle()

            // The draft is on the list, newest first, with the chip; the full calculation under it has none.
            // A row merges its cells' semantics into its own, so its cells are looked for in the unmerged tree.
            fun cell(row: Int, name: String) = compose.onNodeWithTag("${CalculationTags.row(row)}/$name", useUnmergedTree = true)
            cell(0, "quick").onChild().assertTextEquals(QUICK_CHIP_TEXT)
            cell(0, "name").assertTextEquals("Лист")
            compose.onAllNodesWithTag("${CalculationTags.row(1)}/quick", useUnmergedTree = true).assertCountEquals(0)
            cell(1, "name").assertTextEquals("Кронштейн")
        }
    }

    @Test
    fun `a missing laser rate is named on the editor and the calculation waits for it`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            deps.settings.save(deps.settings.load().copy(laserCutPerMinute = null))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            startQuick()
            node(EditorTags.CALCULATE).assertIsNotEnabled()
            type(QuickTags.NAME, "Лист")
            choose(QuickTags.GRADE, "Ст3")
            choose(QuickTags.THICKNESS, "2")
            type(QuickTags.SHEET_AREA, "1,5")
            type(QuickTags.CUT_TIME, "12")
            node(EditorTags.CALCULATE).assertIsEnabled().performClick()
            compose.waitForIdle()

            compose.onNodeWithText("Не задана цена: Лазер, ₽/мин", useUnmergedTree = true).assertExists()
            compose.onAllNodesWithText("Расчёт не выполнен — не хватает данных", useUnmergedTree = true).assertCountEquals(1)
            assertEquals(CalculationStatus.DRAFT, deps.calculations.list().single().status)
        }
    }
}
