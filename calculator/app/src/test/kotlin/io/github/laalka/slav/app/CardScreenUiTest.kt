package io.github.laalka.slav.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The title bar and the actions of the redesigned card: the status chip that is the menu of the status, the exports and «Изменить». */
@OptIn(ExperimentalTestApi::class)
class CardScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun deps() = Deps(AppPaths(temp.root.toPath()), appVersion = "test", adminSeed = testAdminSeed())

    /** Offers [target] to every save dialog. */
    private class SavingPicker(private val target: Path) : FilePicker {
        override fun chooseSaveTarget(title: String, suggestedName: String, extension: String): Path = target

        override fun chooseImportSource(): Path? = error("The test opens no import dialog")

        override fun chooseDrawing(): Path? = error("The test opens no drawing dialog")
    }

    private fun open(deps: Deps, id: UUID, picker: FilePicker = NoFilePicker) {
        compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
        compose.onNodeWithTag(CalculationTags.row(deps.calculations.list().indexOfFirst { it.id == id })).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the status is a chip beside the name, and its menu sets one of the statuses the user chooses`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Опора"))
            open(deps, id)

            val name = compose.onNodeWithTag(CalculationTags.CARD_NAME).getBoundsInRoot()
            val chip = compose.onNodeWithTag(CalculationTags.CARD_STATUS).getBoundsInRoot()
            assertTrue(chip.left >= name.right, "the chip is after the name on its line: $name, $chip")
            assertEquals(26.dp, chip.height, "the chip is 26 dp high")
            assertTrue(kotlin.math.abs(((chip.top + chip.bottom) - (name.top + name.bottom)).value) / 2 < 10f, "on the line of the name: $name, $chip")

            compose.onNodeWithTag(CalculationTags.CARD_STATUS).assertTextContains("Рассчитан").assertHasClickAction()
            compose.onNodeWithTag(CalculationTags.CARD_STATUS).performClick()
            compose.onNodeWithTag("${CalculationTags.CARD_STATUS}/${CalculationStatus.IN_WORK.title}").performClick()
            compose.waitForIdle()

            assertEquals(CalculationStatus.IN_WORK, assertNotNull(deps.calculations.get(id)).status)
            compose.onNodeWithTag(CalculationTags.CARD_STATUS).assertTextContains("В работе")
        }
    }

    @Test
    fun `the chip of a draft is a plain chip with no menu, and there is nothing to export`() {
        deps().use { deps ->
            val id = deps.calculations.saveDraft(goldenInput("Черновик"))
            // A row of a draft opens its editor, so the card of a draft is reached by the navigator.
            lateinit var navigator: Navigator
            compose.setContent {
                SlavTheme(darkTheme = false) {
                    navigator = rememberNavigator(deps)
                    App(deps, NoFilePicker, navigator)
                }
            }
            compose.runOnIdle { navigator.navigate(Screen.Card(id)) }
            compose.waitForIdle()

            compose.onNodeWithTag(CalculationTags.CARD_STATUS).assertTextContains("Черновик").assertHasNoClickAction()
            ExportFormat.entries.forEach { compose.onNodeWithTag(CalculationTags.cardExport(it)).assertIsNotEnabled() }
            compose.onNodeWithTag(CalculationTags.CARD_EDIT).assertIsEnabled()
        }
    }

    @Test
    fun `PDF in the title bar writes the file the user chose and says where it went`() = exportsTo(ExportFormat.PDF)

    @Test
    fun `Excel in the title bar writes the file the user chose and says where it went`() = exportsTo(ExportFormat.XLSX)

    private fun exportsTo(format: ExportFormat) {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Опора"))
            val target = temp.newFolder("out").toPath().resolve("Опора${format.extension}")
            open(deps, id, SavingPicker(target))

            compose.onNodeWithTag(CalculationTags.cardExport(format)).assertIsEnabled().performClick()
            compose.waitForIdle()

            assertTrue(target.exists() && target.fileSize() > 0, "$format was not written to $target")
            compose.onNodeWithTag(CalculationTags.CARD_MESSAGE).assertExists()
        }
    }

    @Test
    fun `«Изменить» is the primary button of the title bar, at its right end, over the actions`() {
        deps().use { deps ->
            val id = deps.saveCalculated(goldenInput("Опора"))
            open(deps, id)

            val edit = compose.onNodeWithTag(CalculationTags.CARD_EDIT).getBoundsInRoot()
            val pdf = compose.onNodeWithTag(CalculationTags.cardExport(ExportFormat.PDF)).getBoundsInRoot()
            val excel = compose.onNodeWithTag(CalculationTags.cardExport(ExportFormat.XLSX)).getBoundsInRoot()
            val copy = compose.onNodeWithTag(CalculationTags.CARD_COPY_EDIT).getBoundsInRoot()
            assertTrue(pdf.right <= excel.left && excel.right <= edit.left, "PDF, Excel, «Изменить» in this order: $pdf, $excel, $edit")
            assertEquals(pdf.top, edit.top, "in one row")
            assertTrue(edit.bottom < copy.top, "the title bar is over the list of actions: $edit, $copy")
        }
    }
}
