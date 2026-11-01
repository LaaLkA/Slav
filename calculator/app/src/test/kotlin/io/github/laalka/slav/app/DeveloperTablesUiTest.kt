package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.calculations.WRONG_PASSWORD
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.settings.GeneralTags
import io.github.laalka.slav.app.settings.PriceCell
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.settings.priceAt
import io.github.laalka.slav.app.settings.priceCells
import io.github.laalka.slav.app.settings.testTag
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.AppPaths
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeveloperTablesUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val fillLabel = "Заполнить таблицы случайными данными"
    private val resetLabel = "Очистить таблицы (исходное состояние)"

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun deps(developerMode: Boolean, book: PriceBook? = null): Deps =
        Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed()).also {
            if (book != null) it.settings.save(book)
            if (developerMode) it.preferences.setDeveloperMode(true)
        }

    private fun open(deps: Deps) {
        compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
    }

    private fun openTab(tab: SettingsTab) {
        node(NAV_SETTINGS_TAG).performClick()
        node(tab.testTag).performClick()
        compose.waitForIdle()
    }

    private fun assertNoDeveloperButtons() {
        node(GeneralTags.FILL_TABLES).assertDoesNotExist()
        node(GeneralTags.RESET_TABLES).assertDoesNotExist()
        compose.onNodeWithText(fillLabel).assertDoesNotExist()
        compose.onNodeWithText(resetLabel).assertDoesNotExist()
    }

    @Test
    fun `the buttons are absent with developer mode off, appear in its card under the switch when it is switched on, and go when it is switched off`() {
        deps(developerMode = false).use { deps ->
            open(deps)
            openTab(SettingsTab.GENERAL)
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOff()
            assertNoDeveloperButtons()

            node(GeneralTags.DEVELOPER_SWITCH).performClick()
            node(GeneralTags.DEVELOPER_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(GeneralTags.DEVELOPER_CONFIRM).performClick()
            compose.waitForIdle()

            // Live: nothing was reopened.
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOn()
            node(GeneralTags.FILL_TABLES).performScrollTo().assertExists()
            node(GeneralTags.RESET_TABLES).assertExists()
            compose.onNodeWithText(fillLabel).assertExists()
            compose.onNodeWithText(resetLabel).assertExists()
            val switch = node(GeneralTags.DEVELOPER_SWITCH).getBoundsInRoot()
            val fill = node(GeneralTags.FILL_TABLES).getBoundsInRoot()
            val reset = node(GeneralTags.RESET_TABLES).getBoundsInRoot()
            assertTrue(fill.top >= switch.bottom, "the fill button is above the switch: $fill, $switch")
            assertTrue(fill.top - switch.bottom < 32.dp, "the fill button is not right under the switch: $fill, $switch")
            assertEquals(fill.top, reset.top, "the two buttons are in one row")
            assertTrue(reset.left >= fill.right, "the reset button is not next to the fill button: $fill, $reset")

            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().performClick()
            compose.waitForIdle()
            node(GeneralTags.DEVELOPER_SWITCH).assertIsOff()
            assertNoDeveloperButtons()
        }
    }

    @Test
    fun `no other tab of the settings has them`() {
        deps(developerMode = true).use { deps ->
            open(deps)
            for (tab in SettingsTab.entries.filter { it != SettingsTab.GENERAL }) {
                openTab(tab)
                assertNoDeveloperButtons()
            }
            openTab(SettingsTab.GENERAL)
            node(GeneralTags.FILL_TABLES).performScrollTo().assertExists()
        }
    }

    @Test
    fun `one press on empty settings fills the four tabs and the card says how much was added`() {
        deps(developerMode = true, book = emptyPriceBook()).use { deps ->
            open(deps)
            openTab(SettingsTab.MATERIALS)
            compose.onAllNodesWithText("Нержавейка").assertCountEquals(0)

            openTab(SettingsTab.GENERAL)
            node(GeneralTags.FILL_TABLES).performScrollTo().performClick()
            compose.waitForIdle()

            val book = deps.settings.load()
            assertEquals(3, book.groups.size)
            assertEquals(4, book.grades.size)
            assertTrue(book.priceCells().all { book.priceAt(it) != null })
            node(GeneralTags.DEVELOPER_NOTICE).performScrollTo().assertTextContains("Добавлено: 4 марки, 85 размеров, ${book.priceCells().size} ", substring = true)

            openTab(SettingsTab.MATERIALS)
            compose.onAllNodesWithText("Нержавейка").onFirst().assertExists()
            compose.onAllNodesWithText("AISI 304").onFirst().assertExists()
            openTab(SettingsTab.MATERIAL_PRICES)
            val material = PriceCell.Material("Ст3", BigDecimal("2"))
            node(material.testTag()).assertTextContains(book.priceAt(material)!!.toInputText())
            openTab(SettingsTab.OPERATION_PRICES)
            node(PriceCell.LaserCutPerMinute.testTag()).performScrollTo().assertTextContains(book.laserCutPerMinute!!.toInputText())
            node(PriceCell.Painting.testTag()).performScrollTo().assertTextContains(book.paintingPerM2!!.toInputText())
        }
    }

    @Test
    fun `the reset asks the password and keeps the tables on a wrong one or a cancel, then returns them to the first-run state`() {
        deps(developerMode = true).use { deps ->
            val id = deps.saveCalculated(goldenInput("Расчёт до сброса"))
            val calculation = deps.calculations.get(id)
            open(deps)
            openTab(SettingsTab.GENERAL)
            node(GeneralTags.FILL_TABLES).performScrollTo().performClick()
            compose.waitForIdle()
            val filled = deps.settings.load()
            assertNotEquals(PriceBook.default(), filled)

            node(GeneralTags.RESET_TABLES).performScrollTo().performClick()
            compose.waitForIdle()
            node(GeneralTags.RESET_PASSWORD).assertExists()
            compose.onNodeWithText("Материалы, сортамент", substring = true).assertExists()
            compose.onNodeWithText("Сохранённые расчёты", substring = true).assertExists()
            assertEquals(filled, deps.settings.load(), "nothing is cleared before the password")

            node(GeneralTags.RESET_PASSWORD).performTextReplacement("not-the-password")
            node(GeneralTags.RESET_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(WRONG_PASSWORD).assertExists()
            node(GeneralTags.RESET_PASSWORD).assertExists()
            assertEquals(filled, deps.settings.load(), "a wrong password clears nothing")

            node(GeneralTags.RESET_CANCEL).performClick()
            compose.waitForIdle()
            node(GeneralTags.RESET_PASSWORD).assertDoesNotExist()
            assertEquals(filled, deps.settings.load(), "a cancel clears nothing")

            node(GeneralTags.RESET_TABLES).performScrollTo().performClick()
            node(GeneralTags.RESET_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(GeneralTags.RESET_CONFIRM).performClick()
            compose.waitForIdle()

            node(GeneralTags.RESET_PASSWORD).assertDoesNotExist()
            assertEquals(PriceBook.default(), deps.settings.load())
            node(GeneralTags.DEVELOPER_NOTICE).performScrollTo().assertTextContains("Таблицы приведены в исходное состояние")
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOn()
            assertEquals(calculation, deps.calculations.get(id), "the calculation is as it was")
            openTab(SettingsTab.MATERIALS)
            compose.onAllNodesWithText("Нержавейка").assertCountEquals(0)
        }
    }
}
