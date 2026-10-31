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
import io.github.laalka.slav.app.editor.NAME_TAKEN
import io.github.laalka.slav.app.settings.NOT_A_DISCOUNT
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

/** The tag the run size field of the simplified editor had; a simplified calculation has no run size now, so nothing has it. */
private const val REMOVED_QUANTITY_TAG = "quick/quantity"

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
    fun `the golden quick case entered through the quick editor shows 5 400,00 as the price, with no run size anywhere`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            startQuick()
            type(QuickTags.NAME, "Лист на раскрой")
            node(REMOVED_QUANTITY_TAG).assertDoesNotExist()
            choose(QuickTags.GRADE, "Ст3")
            choose(QuickTags.THICKNESS, "2")
            type(QuickTags.SHEET_AREA, "1,5")
            type(QuickTags.CUT_TIME, "12")
            compose.waitForIdle()
            node(QuickTags.MASS).assertTextEquals("24 кг")
            // The bottom bar prices the input as it stands; the price of one piece is the whole price, so there is no run total.
            node(EditorTags.SUMMARY_UNIT_PRICE).assertTextEquals("5 400,00 ₽")
            compose.onAllNodesWithTag(EditorTags.SUMMARY_TOTAL).assertCountEquals(0)
            compose.onAllNodesWithTag(EditorTags.SUMMARY_DISCOUNT).assertCountEquals(0)

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_NAME).assertTextEquals("Лист на раскрой")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextContains("5 400,00", substring = true)
            // A simplified calculation has no run size: the card shows no run size, no run total, no run mass.
            for (tag in listOf(
                CalculationTags.CARD_QUANTITY,
                CalculationTags.CARD_QUANTITY_CHANGE,
                CalculationTags.CARD_TOTAL,
                CalculationTags.CARD_RUN_MASS,
            )) {
                node(tag).assertDoesNotExist()
            }
            node(CalculationTags.CARD_UNIT_MASS).assertTextEquals("24 кг")
            compose.onNodeWithText(QUICK_MATERIAL_TITLE).assertExists()
            compose.onNodeWithText(QUICK_CUT_TITLE).assertExists()
            val saved = assertIs<StoredCalculation.Quick>(deps.calculations.get(deps.calculations.list().single().id))
            assertEquals(CalculationStatus.CALCULATED, saved.status)
            assertEquals(1, saved.quantity)

            // The list shows a dash for the run size, and the total is the unit price.
            node(CalculationTags.CARD_BACK).performClick()
            compose.waitForIdle()
            fun cell(name: String) = compose.onNodeWithTag("${CalculationTags.row(0)}/$name", useUnmergedTree = true)
            cell("quantity").assertTextEquals("—")
            cell("total").assertTextEquals("5 400,00 ₽")
            assertEquals(0, BigDecimal("5400.00").compareTo(assertNotNull(saved.unitPrice).amount))
            assertEquals(
                QuickCalculationInput("Лист на раскрой", 1, "Ст3", BigDecimal("2"), BigDecimal("1.5"), BigDecimal("12")).copy(),
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

    @Test
    fun `the discount of a simplified calculation is checked as it is typed, priced in the bottom bar and shown on the card`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            startQuick()
            type(QuickTags.NAME, "Лист на раскрой")
            type(QuickTags.COUNTERPARTY, "ООО Ромашка")
            choose(QuickTags.GRADE, "Ст3")
            choose(QuickTags.THICKNESS, "2")
            type(QuickTags.SHEET_AREA, "1,5")
            type(QuickTags.CUT_TIME, "12")

            // 0, 100 and text are refused under the field and keep «Рассчитать» disabled; blank is no discount.
            for (bad in listOf("0", "100", "150")) {
                type(QuickTags.DISCOUNT, bad)
                compose.onNodeWithText(NOT_A_DISCOUNT, useUnmergedTree = true).assertExists()
                node(EditorTags.CALCULATE).assertIsNotEnabled()
            }
            type(QuickTags.DISCOUNT, "abc")
            compose.onNodeWithText("Не число", useUnmergedTree = true).assertExists()
            node(EditorTags.CALCULATE).assertIsNotEnabled()
            compose.onAllNodesWithTag(EditorTags.SUMMARY_UNIT_PRICE).assertCountEquals(0)

            // A decimal comma is accepted: 5 400,00 less 10 % is 540,00 off.
            type(QuickTags.DISCOUNT, "10")
            node(EditorTags.CALCULATE).assertIsEnabled()
            node(EditorTags.SUMMARY_SUBTOTAL).assertTextEquals("5 400,00 ₽")
            node(EditorTags.SUMMARY_DISCOUNT).assertTextEquals("−540,00 ₽")
            node(EditorTags.SUMMARY_UNIT_PRICE).assertTextEquals("4 860,00 ₽")
            compose.onNodeWithText("Скидка (10 %)", useUnmergedTree = true).assertExists()
            type(QuickTags.DISCOUNT, "12,5")
            node(EditorTags.SUMMARY_DISCOUNT).assertTextEquals("−675,00 ₽")
            type(QuickTags.DISCOUNT, "10")

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_COUNTERPARTY).assertTextEquals("Контрагент: ООО Ромашка")
            node(CalculationTags.CARD_SUBTOTAL).assertTextEquals("5 400,00 ₽")
            node(CalculationTags.CARD_DISCOUNT).assertTextEquals("−540,00 ₽")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextEquals("4 860,00 ₽")
            val saved = assertIs<StoredCalculation.Quick>(deps.calculations.get(deps.calculations.list().single().id))
            assertEquals("ООО Ромашка", saved.input.counterparty)
            assertEquals(0, BigDecimal("10").compareTo(saved.input.discountPercent))
        }
    }

    @Test
    fun `a name another calculation has is flagged as it is typed, in any letter case, and blocks Рассчитать`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            startQuick()
            choose(QuickTags.GRADE, "Ст3")
            choose(QuickTags.THICKNESS, "2")
            type(QuickTags.SHEET_AREA, "1,5")
            type(QuickTags.CUT_TIME, "12")

            type(QuickTags.NAME, " кронштейн ")
            compose.waitForIdle()

            compose.onNodeWithText(NAME_TAKEN, useUnmergedTree = true).assertExists()
            node(EditorTags.CALCULATE).assertIsNotEnabled()
            compose.onAllNodesWithTag(EditorTags.SUMMARY_UNIT_PRICE).assertCountEquals(0)

            type(QuickTags.NAME, "Кронштейн 2")
            compose.waitForIdle()

            compose.onAllNodesWithText(NAME_TAKEN, useUnmergedTree = true).assertCountEquals(0)
            node(EditorTags.CALCULATE).assertIsEnabled().performClick()
            compose.waitForIdle()
            node(CalculationTags.CARD_NAME).assertTextEquals("Кронштейн 2")
        }
    }
}
