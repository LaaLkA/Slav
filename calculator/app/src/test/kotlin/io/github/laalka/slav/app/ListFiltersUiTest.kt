package io.github.laalka.slav.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test

/** The filters of the list as the user sees them: the pills of the status, with «Все» for no status chosen, and the count under the title. */
class ListFiltersUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun deps() = Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed())

    @Test
    fun `«Все» is chosen while no status is, a status shows only its calculations, and «Все» shows every status again`() {
        deps().use { deps ->
            val sent = deps.saveCalculated(goldenInput("Опора"))
            deps.calculations.setStatus(sent, CalculationStatus.SENT)
            deps.saveCalculated(goldenInput("Стойка"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            compose.onNodeWithTag(CalculationTags.STATUS_ALL).assertIsSelected()
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).assertIsNotSelected()
            compose.onNodeWithTag(CalculationTags.COUNT).assertTextEquals("2 расчёта")
            compose.onAllNodesWithTag(CalculationTags.row(1)).assertCountEquals(1)

            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalculationTags.STATUS_ALL).assertIsNotSelected()
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).assertIsSelected()
            compose.onNodeWithTag(CalculationTags.COUNT).assertTextEquals("Найдено: 1")
            compose.onAllNodesWithTag(CalculationTags.row(0)).assertCountEquals(1)
            compose.onAllNodesWithTag(CalculationTags.row(1)).assertCountEquals(0)

            // Choosing the status again takes it back; with none chosen «Все» is the chosen pill.
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalculationTags.STATUS_ALL).assertIsSelected()

            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).performClick()
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.CALCULATED)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalculationTags.COUNT).assertTextEquals("Найдено: 2")
            compose.onNodeWithTag(CalculationTags.STATUS_ALL).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalculationTags.STATUS_ALL).assertIsSelected()
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).assertIsNotSelected()
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.CALCULATED)).assertIsNotSelected()
            compose.onNodeWithTag(CalculationTags.COUNT).assertTextEquals("2 расчёта")
        }
    }

    @Test
    fun `«Все» leaves the search as it is, while the filters are cleared by «Сбросить фильтры»`() {
        deps().use { deps ->
            deps.saveCalculated(goldenInput("Опора"))
            deps.saveCalculated(goldenInput("Стойка"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            compose.onNodeWithTag(CalculationTags.SEARCH).performTextInput("Оп")
            compose.onNodeWithTag(CalculationTags.status(CalculationStatus.SENT)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalculationTags.STATUS_ALL).performClick()
            compose.waitForIdle()

            // Only the status was let go: the search still narrows the list to the one calculation.
            compose.onNodeWithTag(CalculationTags.COUNT).assertTextEquals("Найдено: 1")
            compose.onNodeWithTag(CalculationTags.CLEAR_FILTERS).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalculationTags.COUNT).assertTextEquals("2 расчёта")
        }
    }
}
