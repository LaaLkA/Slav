package io.github.laalka.slav.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A save dialog that hands out [next] and remembers what it was asked; the other dialogs are not opened. */
private class ArchivePicker(var next: Path? = null) : FilePicker {
    var asked: List<String>? = null

    override fun chooseSaveTarget(title: String, suggestedName: String, extension: String): Path? {
        asked = listOf(title, suggestedName, extension)
        return next
    }

    override fun chooseImportSource(): Path? = error("The test opens no import dialog")

    override fun chooseDrawing(): Path? = error("The test opens no drawing dialog")
}

class ArchiveUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun deps() = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())

    private fun node(tag: String) = compose.onNodeWithTag(tag)

    private fun check(row: Int) = node("${CalculationTags.row(row)}/check")

    @Test
    fun `rows get check boxes in the selection mode, and the button counts the checked ones`() {
        deps().use { deps ->
            for (name in listOf("Кронштейн", "Опора", "Рама")) deps.saveCalculated(goldenInput(name))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, ArchivePicker()) } }

            compose.onAllNodesWithTag("${CalculationTags.row(0)}/check", useUnmergedTree = true).assertCountEquals(0)
            node(CalculationTags.SELECT).performClick()
            compose.waitForIdle()

            node(CalculationTags.ARCHIVE).assertTextContains("В архив (0)")
            node(CalculationTags.ARCHIVE).assertIsNotEnabled()
            (0..2).forEach { check(it).assertIsOff() }

            node(CalculationTags.SELECT_ALL).performClick()
            compose.waitForIdle()
            (0..2).forEach { check(it).assertIsOn() }
            node(CalculationTags.ARCHIVE).assertTextContains("В архив (3)")
            node(CalculationTags.ARCHIVE).assertIsEnabled()

            node(CalculationTags.SELECT_ALL).performClick()
            compose.waitForIdle()
            (0..2).forEach { check(it).assertIsOff() }

            check(1).performClick()
            node(CalculationTags.row(2)).performClick()
            compose.waitForIdle()
            check(0).assertIsOff()
            check(1).assertIsOn()
            check(2).assertIsOn()
            node(CalculationTags.ARCHIVE).assertTextContains("В архив (2)")
            node(CalculationTags.CARD_BACK).assertDoesNotExist()

            node(CalculationTags.SELECT_CANCEL).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag("${CalculationTags.row(0)}/check", useUnmergedTree = true).assertCountEquals(0)
            node(CalculationTags.SELECT).assertExists()
        }
    }

    @Test
    fun `archiving the checked rows asks the file, then the password, and deletes them only with a right one`() {
        deps().use { deps ->
            for (name in listOf("Кронштейн", "Опора", "Рама")) deps.saveCalculated(goldenInput(name))
            val target = temp.newFolder("out").toPath().resolve("a.zip")
            val picker = ArchivePicker(target)
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.SELECT).performClick()
            check(0).performClick()
            check(1).performClick()
            compose.waitForIdle()

            node(CalculationTags.ARCHIVE).performClick()
            compose.waitForIdle()

            assertEquals(listOf("Отправить в архив", "slav-archive-${LocalDate.now()}.zip", ".zip"), picker.asked)
            node(CalculationTags.ARCHIVE_PASSWORD).assertExists()
            assertFalse(target.exists(), "nothing is written before the password")

            node(CalculationTags.ARCHIVE_PASSWORD).performTextReplacement("not-the-password")
            node(CalculationTags.ARCHIVE_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Неверный пароль").assertExists()
            assertEquals(3, deps.calculations.list().size)
            assertFalse(target.exists())

            node(CalculationTags.ARCHIVE_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(CalculationTags.ARCHIVE_CONFIRM).performClick()
            compose.waitForIdle()

            assertTrue(target.exists())
            assertEquals(listOf(target), target.parent.listDirectoryEntries())
            assertEquals(1, deps.calculations.list().size)
            node(CalculationTags.MESSAGE).assertExists()
            compose.onNodeWithText("В архив отправлено расчётов: 2 — $target").assertExists()
            node(CalculationTags.ARCHIVE_PASSWORD).assertDoesNotExist()
            node(CalculationTags.SELECT).assertExists()
            check(0).assertDoesNotExist()
        }
    }

    @Test
    fun `a save dialog that is cancelled asks no password and changes nothing`() {
        deps().use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн"))
            val picker = ArchivePicker(next = null)
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.SELECT).performClick()
            node(CalculationTags.SELECT_ALL).performClick()

            node(CalculationTags.ARCHIVE).performClick()
            compose.waitForIdle()

            assertNotNull(picker.asked, "the save dialog was opened")
            node(CalculationTags.ARCHIVE_PASSWORD).assertDoesNotExist()
            assertEquals(1, deps.calculations.list().size)
            node(CalculationTags.ARCHIVE).assertTextContains("В архив (1)")
        }
    }

    @Test
    fun `В архив on the card writes the file and goes back to the list with the message`() {
        deps().use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн"))
            val target = temp.newFolder("out").toPath().resolve("card.zip")
            val picker = ArchivePicker(target)
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()

            // Like the other actions of the card, the button has an icon before its label; the button merges its children, so the icon is looked for in the unmerged tree.
            compose.onNode(
                hasTestTag(CalculationTags.CARD_ARCHIVE_ICON) and hasAnyAncestor(hasTestTag(CalculationTags.CARD_ARCHIVE)),
                useUnmergedTree = true,
            ).assertExists()

            node(CalculationTags.CARD_ARCHIVE).performClick()
            compose.waitForIdle()
            node(CalculationTags.ARCHIVE_PASSWORD).performTextReplacement("not-the-password")
            node(CalculationTags.ARCHIVE_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Неверный пароль").assertExists()
            node(CalculationTags.CARD_NAME).assertExists()
            assertEquals(1, deps.calculations.list().size)
            assertFalse(target.exists())

            node(CalculationTags.ARCHIVE_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(CalculationTags.ARCHIVE_CONFIRM).performClick()
            compose.waitForIdle()

            assertTrue(target.exists())
            assertEquals(0, deps.calculations.list().size)
            node(CalculationTags.CARD_NAME).assertDoesNotExist()
            node(CalculationTags.EMPTY).assertExists()
            compose.onNodeWithText("В архив отправлено расчётов: 1 — $target").assertExists()
        }
    }

    @Test
    fun `the status that was called Архив is called Завершён`() {
        deps().use { deps ->
            deps.saveCalculated(goldenInput("Кронштейн"))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, ArchivePicker()) } }

            node(CalculationTags.status(CalculationStatus.ARCHIVED)).assertTextContains("Завершён")
            compose.onAllNodesWithText("Архив").assertCountEquals(0)
            assertEquals("ARCHIVED", CalculationStatus.ARCHIVED.name, "rows already stored hold the enum name")
            assertNull(CalculationStatus.entries.firstOrNull { it.title == "Архив" })
        }
    }
}
