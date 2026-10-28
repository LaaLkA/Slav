package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.attachments.AttachmentTags
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.WRONG_PASSWORD
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val opener = RecordingOpener()
    private val picker = DrawingPicker()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun click(tag: String) {
        node(tag).performScrollTo().performClick()
    }

    private fun deps() = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed(), fileOpener = opener)

    private fun drawing(name: String, size: Int): Path = temp.newFolder("incoming-$name").toPath().resolve(name).also { it.writeBytes(ByteArray(size)) }

    @Test
    fun `the card attaches a drawing, opens it and removes it only with the administrator password`() {
        deps().use { deps ->
            val id = deps.saveCalculated()
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag("${AttachmentTags.row(0)}/name").assertCountEquals(0)
            node(AttachmentTags.EMPTY).assertExists()

            picker.next = drawing("Чертёж.PDF", size = 3000)
            click(AttachmentTags.ATTACH)
            compose.waitForIdle()
            node("${AttachmentTags.row(0)}/name").assertTextEquals("Чертёж.PDF")
            node("${AttachmentTags.row(0)}/size").assertTextEquals("2,9 КБ")
            val stored = deps.attachments.pathOf(deps.attachments.list(id).single())

            click("${AttachmentTags.row(0)}/open")
            compose.waitForIdle()
            assertEquals(listOf(stored), opener.opened)

            click("${AttachmentTags.row(0)}/remove")
            node(AttachmentTags.REMOVE_PASSWORD).performTextReplacement("not-the-password")
            node(AttachmentTags.REMOVE_CONFIRM).performClick()
            compose.waitForIdle()
            node(AttachmentTags.REMOVE_PASSWORD).assertExists()
            compose.onNodeWithText(WRONG_PASSWORD).assertExists()
            node("${AttachmentTags.row(0)}/name").assertTextEquals("Чертёж.PDF")
            assertTrue(stored.exists(), "a wrong password keeps the file")

            node(AttachmentTags.REMOVE_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
            node(AttachmentTags.REMOVE_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag(AttachmentTags.REMOVE_PASSWORD).assertCountEquals(0)
            compose.onAllNodesWithTag("${AttachmentTags.row(0)}/name").assertCountEquals(0)
            assertFalse(stored.exists())
            assertEquals(emptyList(), deps.attachments.list(id))
        }
    }

    @Test
    fun `the editor of a new calculation attaches a drawing and removes it without a password`() {
        deps().use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.NEW).performClick()
            compose.waitForIdle()

            picker.next = drawing("scheme.png", size = 500)
            click(AttachmentTags.ATTACH)
            compose.waitForIdle()
            node("${AttachmentTags.row(0)}/name").assertTextEquals("scheme.png")
            node("${AttachmentTags.row(0)}/size").assertTextEquals("500 Б")
            val draft = deps.calculations.list().single()
            val stored = deps.attachments.pathOf(deps.attachments.list(draft.id).single())
            assertTrue(stored.exists())

            picker.next = drawing("scheme.dwg", size = 10)
            click(AttachmentTags.ATTACH)
            compose.waitForIdle()
            node(AttachmentTags.MESSAGE).assertExists()
            compose.onNodeWithText("scheme.dwg", substring = true).assertExists()
            compose.onAllNodesWithTag(AttachmentTags.row(1) + "/name").assertCountEquals(0)

            click("${AttachmentTags.row(0)}/remove")
            compose.waitForIdle()
            compose.onAllNodesWithTag(AttachmentTags.REMOVE_PASSWORD).assertCountEquals(0)
            compose.onAllNodesWithTag("${AttachmentTags.row(0)}/name").assertCountEquals(0)
            assertFalse(stored.exists())
        }
    }
}
