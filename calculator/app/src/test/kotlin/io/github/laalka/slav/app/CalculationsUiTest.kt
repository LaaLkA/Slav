package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CalculationsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    /** A cell of a list row: the clickable row merges its cells' semantics into its own. */
    private fun cell(row: Int, name: String): SemanticsNodeInteraction =
        compose.onNodeWithTag("${CalculationTags.row(row)}/$name", useUnmergedTree = true)

    private fun deps() = Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed())

    @Test
    fun `the golden card shows 113 400,00 for a run of 10 as a new version, and the original keeps 11 340,00`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput(quantity = 1))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            cell(0, "name").assertTextEquals("Кронштейн")
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()
            node(CalculationTags.CARD_UNIT_PRICE).assertTextEquals("11 340,00 ₽")
            node(CalculationTags.CARD_TOTAL).assertTextEquals("11 340,00 ₽")
            node(CalculationTags.CARD_QUANTITY).assertTextEquals("1")

            node(CalculationTags.CARD_QUANTITY_CHANGE).performClick()
            node(CalculationTags.CARD_QUANTITY_FIELD).performTextReplacement("10")
            node(CalculationTags.CARD_QUANTITY_CONFIRM).performClick()
            compose.waitForIdle()

            // The new version is open; it is a second row, the first one is untouched.
            node(CalculationTags.CARD_TOTAL).assertTextEquals("113 400,00 ₽")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextEquals("11 340,00 ₽")
            node(CalculationTags.CARD_RUN_MASS).assertTextEquals("160 кг")
            node(CalculationTags.CARD_SOURCE).assertExists()
            val original = assertNotNull(deps.calculations.get(id))
            assertEquals(1, original.quantity)
            assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(original.unitPrice).amount))
            assertEquals(2, deps.calculations.list().size)

            // Back on the list, both rows are there with their own totals.
            node(CalculationTags.CARD_BACK).performClick()
            compose.waitForIdle()
            val totals = deps.calculations.list().associate { it.id to it.total?.amount?.toPlainString() }
            assertEquals(setOf("11340.00", "113400.00"), totals.values.toSet())
            assertEquals(2, totals.size)
        }
    }

    @Test
    fun `Изменить on the card opens a draft copy, and going back drops it while the original stays`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Кронштейн"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_EDIT).performClick()
            compose.waitForIdle()
            node(EditorTags.NAME).assertTextContains("Кронштейн")
            assertEquals(2, deps.calculations.list().size)
            val draft = deps.calculations.list().single { it.status == CalculationStatus.DRAFT }
            assertEquals(id, assertNotNull(deps.calculations.get(draft.id)).sourceId)

            node(EditorTags.BACK).performClick()
            compose.waitForIdle()

            assertEquals(listOf(id), deps.calculations.list().map { it.id })
            assertEquals(CalculationStatus.CALCULATED, assertNotNull(deps.calculations.get(id)).status)
        }
    }

    @Test
    fun `the list searches, deletes after confirmation and opens a copy in the editor`() {
        deps().use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн"))
            deps.saveCalculated(goldenInput("Опора"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.SEARCH).performTextReplacement("ОПОР")
            compose.waitForIdle()
            compose.onAllNodesWithTag(CalculationTags.row(1)).assertCountEquals(0)
            cell(0, "name").assertTextEquals("Опора")

            node("${CalculationTags.row(0)}/menu").performClick()
            node("${CalculationTags.row(0)}/delete").performClick()
            // A wrong password keeps the calculation and says so under the field.
            node(CalculationTags.DELETE_PASSWORD).performTextReplacement("not-the-password")
            node(CalculationTags.DELETE_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Неверный пароль").assertExists()
            assertEquals(2, deps.calculations.list().size)

            node(CalculationTags.DELETE_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(CalculationTags.DELETE_CONFIRM).performClick()
            compose.waitForIdle()
            node(CalculationTags.NO_MATCHES).assertExists()
            assertEquals(listOf("Кронштейн"), deps.calculations.list().map { it.name })

            node(CalculationTags.CLEAR_FILTERS).performClick()
            node("${CalculationTags.row(0)}/menu").performClick()
            node("${CalculationTags.row(0)}/copy").performClick()
            compose.waitForIdle()

            node(EditorTags.NAME).assertTextContains("Кронштейн")
            val copy = deps.calculations.list().single { it.status == CalculationStatus.DRAFT }
            assertNull(assertNotNull(deps.calculations.get(copy.id)).snapshot)
        }
    }
}
