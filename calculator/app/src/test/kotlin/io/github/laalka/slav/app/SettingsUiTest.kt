package io.github.laalka.slav.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.settings.GroupTable
import io.github.laalka.slav.app.settings.PriceCell
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.settings.testTag
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.priceFor
import io.github.laalka.slav.data.AppPaths
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SettingsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a price typed into the laser 2 mm cell is saved in the repository`() {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            compose.onNodeWithTag(NAV_SETTINGS_TAG).performClick()
            compose.onNodeWithTag(SettingsTab.OPERATION_PRICES.testTag).performClick()
            val cell = PriceCell.Group(GroupTable.LASER_SHEET_CUT, "Сталь", BigDecimal("2"))
            compose.onNodeWithTag(cell.testTag()).performTextReplacement("37,5")
            compose.waitForIdle()

            compose.onNodeWithTag(cell.testTag()).assertTextContains("37,5")
            val saved = assertNotNull(deps.settings.load().laserSheetCutPerCm.priceFor("Сталь", BigDecimal("2")))
            assertEquals(0, BigDecimal("37.5").compareTo(saved), "saved $saved")
        }
    }

    private object NoFilePicker : FilePicker {
        override fun chooseExportTarget(suggestedName: String): Path? = error("The test opens no file dialogs")

        override fun chooseImportSource(): Path? = error("The test opens no file dialogs")
    }
}
