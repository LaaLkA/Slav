package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.laalka.slav.app.attachments.AttachmentTags
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
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

    private fun png(name: String): Path = temp.newFolder("incoming-$name").toPath().resolve(name).also {
        assertTrue(ImageIO.write(BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", it.toFile()))
    }

    private fun pdf(name: String): Path = temp.newFolder("incoming-$name").toPath().resolve(name).also { target ->
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.save(target.toFile())
        }
    }

    // The thumbnail is clickable, which merges what is inside it into one node: its picture or icon is found in the unmerged tree.
    private fun partsOf(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true)

    /** The thumbnails are made in the background: waits until the node is on the screen. */
    private fun awaitNode(tag: String) = compose.waitUntil(timeoutMillis = 15_000) { partsOf(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the card attaches a drawing, opens it and removes it with no password`() {
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
            compose.waitForIdle()
            compose.onAllNodesWithText("пароль", substring = true, ignoreCase = true).assertCountEquals(0)
            compose.onAllNodesWithTag("${AttachmentTags.row(0)}/name").assertCountEquals(0)
            assertFalse(stored.exists())
            assertEquals(emptyList(), deps.attachments.list(id))
            assertEquals(listOf(id), deps.calculations.list().map { it.id }, "the calculation is still the only one")
        }
    }

    @Test
    fun `a row shows the picture of a png and of a pdf, a document icon for a damaged file, and a click on it opens the file`() {
        deps().use { deps ->
            val id = deps.saveCalculated()
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.row(0)).performClick()
            compose.waitForIdle()

            picker.next = png("scheme.png")
            click(AttachmentTags.ATTACH)
            picker.next = pdf("plan.pdf")
            click(AttachmentTags.ATTACH)
            picker.next = drawing("broken.png", size = 400)
            click(AttachmentTags.ATTACH)

            for (row in 0..1) {
                awaitNode("${AttachmentTags.row(row)}/thumbnail/image")
                partsOf("${AttachmentTags.row(row)}/thumbnail/icon").assertCountEquals(0)
            }
            awaitNode("${AttachmentTags.row(2)}/thumbnail/icon")
            partsOf("${AttachmentTags.row(2)}/thumbnail/image").assertCountEquals(0)
            node("${AttachmentTags.row(2)}/name").assertTextEquals("broken.png")

            click("${AttachmentTags.row(0)}/thumbnail")
            click("${AttachmentTags.row(1)}/thumbnail")
            compose.waitForIdle()
            val stored = deps.attachments.list(id).map(deps.attachments::pathOf)
            assertEquals(stored.take(2), opener.opened, "the thumbnail opens the file like «Открыть»")
        }
    }

    @Test
    fun `the editor of a new calculation attaches a drawing and removes it without a password`() {
        deps().use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, picker) } }
            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
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
            compose.onAllNodesWithTag("${AttachmentTags.row(0)}/name").assertCountEquals(0)
            assertFalse(stored.exists())
        }
    }
}
