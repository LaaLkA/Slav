package io.github.laalka.slav.app

import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationSortColumn
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

    /** The text of a node, to compare rows without assuming their order. */
    private fun SemanticsNodeInteraction.fetchText(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

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
    fun `Изменить asks the administrator password, and the right one edits the same calculation in place`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Кронштейн"))
            val before = assertNotNull(deps.calculations.get(id))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_EDIT).performClick()
            // A wrong password says so under the field and opens nothing.
            node(CalculationTags.EDIT_PASSWORD).performTextReplacement("not-the-password")
            node(CalculationTags.EDIT_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Неверный пароль").assertExists()
            node(CalculationTags.CARD_NAME).assertExists()
            compose.onAllNodesWithTag(EditorTags.NAME).assertCountEquals(0)

            node(CalculationTags.EDIT_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(CalculationTags.EDIT_CONFIRM).performClick()
            compose.waitForIdle()

            // The editor is open on the very record; nothing was made or written.
            node(EditorTags.NAME).assertTextContains("Кронштейн")
            compose.onNodeWithText("Изменение расчёта").assertExists()
            node(EditorTags.QUANTITY).performTextReplacement("3")
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            assertEquals(before, deps.calculations.get(id), "nothing is written while a record is edited in place")
            assertEquals(listOf(id), deps.calculations.list().map { it.id })

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            // Back on the card of the same calculation, now with the new run size; still one row.
            node(CalculationTags.CARD_NAME).assertTextEquals("Кронштейн")
            node(CalculationTags.CARD_TOTAL).assertTextEquals("34 020,00 ₽")
            assertEquals(listOf(id), deps.calculations.list().map { it.id })
            assertEquals(3, assertNotNull(deps.calculations.get(id)).quantity)
            assertEquals(before.createdAt, assertNotNull(deps.calculations.get(id)).createdAt)
        }
    }

    @Test
    fun `leaving an edit in place with changes asks to discard them, without changes it just goes back to the card`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Кронштейн"))
            val before = assertNotNull(deps.calculations.get(id))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            node(CalculationTags.row(0)).performClick()
            fun editInPlace() {
                node(CalculationTags.CARD_EDIT).performClick()
                node(CalculationTags.EDIT_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
                node(CalculationTags.EDIT_CONFIRM).performClick()
                compose.waitForIdle()
            }

            // Nothing changed: back goes to the card at once.
            editInPlace()
            node(EditorTags.BACK).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag(EditorTags.LEAVE_CONFIRM).assertCountEquals(0)
            node(CalculationTags.CARD_NAME).assertExists()

            // A change: the question comes first, and «Продолжить редактирование» keeps the editor and what was typed.
            editInPlace()
            node(EditorTags.NAME).performTextReplacement("Кронштейн с ребром")
            node(EditorTags.BACK).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Отменить изменения?").assertExists()
            node(EditorTags.LEAVE_STAY).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag(EditorTags.LEAVE_CONFIRM).assertCountEquals(0)
            node(EditorTags.NAME).assertTextContains("Кронштейн с ребром")

            // Leaving through the rail asks as well; confirming drops the changes and shows the list.
            node(NAV_CALCULATIONS_TAG).performClick()
            compose.waitForIdle()
            node(EditorTags.LEAVE_CONFIRM).performClick()
            compose.waitForIdle()
            cell(0, "name").assertTextEquals("Кронштейн")
            assertEquals(before, deps.calculations.get(id), "the changes were dropped, the row is as it was")
            assertEquals(listOf(id), deps.calculations.list().map { it.id })
        }
    }

    @Test
    fun `Сделать копию и изменить opens a draft copy with a copy number, and going back keeps it while the original stays`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Кронштейн"))
            val before = assertNotNull(deps.calculations.get(id))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_COPY_EDIT).performClick()
            compose.waitForIdle()

            // The copy is named by the program: «Кронштейн (2)». It is a normal draft, saved as it is edited.
            node(EditorTags.NAME).assertTextContains("Кронштейн (2)")
            compose.onAllNodesWithText("Изменение расчёта").assertCountEquals(0)
            val draft = deps.calculations.list().single { it.status == CalculationStatus.DRAFT }
            assertNull(assertNotNull(deps.calculations.get(draft.id)).snapshot)
            assertNull(assertNotNull(deps.calculations.get(draft.id)).sourceId)

            node(EditorTags.BACK).performClick()
            compose.waitForIdle()

            assertEquals(setOf(id, draft.id), deps.calculations.list().map { it.id }.toSet())
            assertEquals(before, deps.calculations.get(id))
        }
    }

    @Test
    fun `the list has a counterparty column that sorts, and the search finds by name and by counterparty`() {
        deps().use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн").copy(counterparty = "ООО Ромашка"))
            deps.saveCalculated(goldenInput("Опора").copy(counterparty = "ИП Иванов"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            compose.onNodeWithText("Контрагент", useUnmergedTree = true).assertExists()
            val byCounterparty = (0..1).associate { cell(it, "name").fetchText() to cell(it, "counterparty").fetchText() }
            assertEquals(mapOf("Кронштейн" to "ООО Ромашка", "Опора" to "ИП Иванов"), byCounterparty)

            node(CalculationTags.sort(CalculationSortColumn.COUNTERPARTY)).performClick()
            compose.waitForIdle()
            cell(0, "counterparty").assertTextEquals("ИП Иванов")
            cell(1, "counterparty").assertTextEquals("ООО Ромашка")
            node(CalculationTags.sort(CalculationSortColumn.COUNTERPARTY)).performClick()
            compose.waitForIdle()
            cell(0, "counterparty").assertTextEquals("ООО Ромашка")

            node(CalculationTags.SEARCH).performTextReplacement("ИВАН")
            compose.waitForIdle()
            compose.onAllNodesWithTag(CalculationTags.row(1)).assertCountEquals(0)
            cell(0, "name").assertTextEquals("Опора")
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

            // A copy is named by the program too: «Кронштейн (2)».
            node(EditorTags.NAME).assertTextContains("Кронштейн (2)")
            val copy = deps.calculations.list().single { it.status == CalculationStatus.DRAFT }
            assertNull(assertNotNull(deps.calculations.get(copy.id)).snapshot)
        }
    }
}
