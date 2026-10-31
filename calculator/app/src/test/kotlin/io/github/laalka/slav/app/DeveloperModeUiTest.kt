package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.WRONG_PASSWORD
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.quick.QuickTags
import io.github.laalka.slav.app.settings.GeneralTags
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeveloperModeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun open(deps: Deps) {
        compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
    }

    private fun openGeneral() {
        node(NAV_SETTINGS_TAG).performClick()
        node(SettingsTab.GENERAL.testTag).performClick()
    }

    private fun toggleSwitch() {
        node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().performClick()
    }

    private fun startEditor(kind: CalculationKind) {
        node(NAV_CALCULATIONS_TAG).performClick()
        node(CalculationTags.NEW).performClick()
        node(CalculationTags.newOf(kind)).performClick()
        compose.waitForIdle()
    }

    private fun closeEditor() {
        node(EditorTags.BACK).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the button is absent with developer mode off in both editors, and a wrong password keeps it off`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed()).use { deps ->
            open(deps)
            startEditor(CalculationKind.FULL)
            node(EditorTags.FILL_RANDOM).assertDoesNotExist()
            closeEditor()
            startEditor(CalculationKind.QUICK)
            node(QuickTags.FILL_RANDOM).assertDoesNotExist()
            closeEditor()

            openGeneral()
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOff()
            toggleSwitch()
            node(GeneralTags.DEVELOPER_PASSWORD).assertExists()
            node(GeneralTags.DEVELOPER_PASSWORD).performTextReplacement("not-the-password")
            node(GeneralTags.DEVELOPER_CONFIRM).performClick()
            compose.waitForIdle()

            compose.onNodeWithText(WRONG_PASSWORD).assertExists()
            node(GeneralTags.DEVELOPER_PASSWORD).assertExists()
            assertFalse(deps.preferences.developerMode.value)
            node(GeneralTags.DEVELOPER_CANCEL).performClick()
            compose.waitForIdle()
            node(GeneralTags.DEVELOPER_PASSWORD).assertDoesNotExist()
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOff()

            startEditor(CalculationKind.FULL)
            node(EditorTags.FILL_RANDOM).assertDoesNotExist()
        }
    }

    @Test
    fun `with the right password the button appears and fills each editor with a calculation that calculates`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed()).use { deps ->
            open(deps)
            openGeneral()
            toggleSwitch()
            node(GeneralTags.DEVELOPER_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(GeneralTags.DEVELOPER_CONFIRM).performClick()
            compose.waitForIdle()

            node(GeneralTags.DEVELOPER_PASSWORD).assertDoesNotExist()
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOn()
            assertTrue(deps.preferences.developerMode.value)

            startEditor(CalculationKind.FULL)
            node(EditorTags.CALCULATE).assertIsNotEnabled()
            node(EditorTags.FILL_RANDOM).assertExists().performClick()
            compose.waitForIdle()
            node(EditorTags.NAME).assertTextContains("Тест", substring = true)
            node(EditorTags.CALCULATE).assertIsEnabled().performClick()
            compose.waitForIdle()
            node(CalculationTags.CARD_NAME).assertTextContains("Тест", substring = true)

            startEditor(CalculationKind.QUICK)
            node(QuickTags.FILL_RANDOM).assertExists().performClick()
            compose.waitForIdle()
            node(QuickTags.NAME).assertTextContains("Тест", substring = true)
            node(EditorTags.CALCULATE).assertIsEnabled().performClick()
            compose.waitForIdle()
            node(CalculationTags.CARD_NAME).assertTextContains("Тест", substring = true)

            val saved = deps.calculations.list()
            assertEquals(listOf(CalculationKind.FULL, CalculationKind.QUICK), saved.map { it.kind }.sortedBy { it.ordinal })
            assertTrue(saved.all { it.status == CalculationStatus.CALCULATED && it.name.startsWith("Тест ") }, saved.toString())
            assertTrue(saved.all { it.counterparty.startsWith("ООО «") }, saved.toString())
        }
    }

    @Test
    fun `switching it off asks no password and takes the button away again`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed()).use { deps ->
            deps.preferences.setDeveloperMode(true)
            open(deps)
            startEditor(CalculationKind.FULL)
            node(EditorTags.FILL_RANDOM).assertExists()
            closeEditor()

            openGeneral()
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOn()
            toggleSwitch()
            compose.waitForIdle()

            node(GeneralTags.DEVELOPER_PASSWORD).assertDoesNotExist()
            node(GeneralTags.DEVELOPER_SWITCH).performScrollTo().assertIsOff()
            assertFalse(deps.preferences.developerMode.value)
            startEditor(CalculationKind.FULL)
            node(EditorTags.FILL_RANDOM).assertDoesNotExist()
        }
    }
}
