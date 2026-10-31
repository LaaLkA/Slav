package io.github.laalka.slav.app.attachments

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.RecordingOpener
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.data.AppPaths
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The thumbnails of the «Чертежи» block: made in the background, shown when ready, and never in the way of the rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentThumbnailsModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private lateinit var incoming: Path
    private lateinit var calculation: UUID

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed(), fileOpener = RecordingOpener())
        incoming = temp.newFolder("incoming").toPath()
        calculation = deps.calculations.saveDraft(goldenInput())
    }

    @AfterTest
    fun close() = deps.close()

    private fun png(name: String, width: Int, height: Int): Path {
        val picture = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        picture.createGraphics().apply {
            color = Color.RED
            fillRect(0, 0, width, height)
            dispose()
        }
        return incoming.resolve(name).also { assertTrue(ImageIO.write(picture, "png", it.toFile())) }
    }

    private fun garbage(name: String): Path = incoming.resolve(name).also { it.writeBytes(ByteArray(500) { i -> (i * 11).toByte() }) }

    /**
     * The model of the drawings of [calculation]; its pictures are made when the test lets the scheduler run.
     * Not the background scope: [advanceUntilIdle] leaves the tasks of that one alone.
     */
    private fun TestScope.model(): AttachmentsModel =
        AttachmentsModel(
            deps.attachments,
            RecordingOpener(),
            calculationId = { calculation },
            storedCalculationId = { calculation },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun AttachmentsModel.thumbnailOf(index: Int): DrawingThumbnail? = state.value.rows[index].let { state.value.thumbnails[it.id] }

    @Test
    fun `the rows come first and each thumbnail follows once its picture is made`() = runTest {
        val model = model()

        model.attach(png("wide.png", 400, 200))
        model.attach(png("tall.png", 100, 400))

        assertEquals(listOf("wide.png", "tall.png"), model.state.value.rows.map { it.fileName }, "the list does not wait for the pictures")
        assertEquals(emptyMap(), model.state.value.thumbnails)

        advanceUntilIdle()

        val wide = assertIs<DrawingThumbnail.Ready>(model.thumbnailOf(0)).image
        val tall = assertIs<DrawingThumbnail.Ready>(model.thumbnailOf(1)).image
        assertEquals(160 to 80, wide.width to wide.height)
        assertEquals(40 to 160, tall.width to tall.height)
    }

    @Test
    fun `the drawings that are there when the block opens get their pictures too`() = runTest {
        deps.attachments.add(calculation, png("plan.png", 300, 300))

        val model = model()
        assertEquals(emptyMap(), model.state.value.thumbnails)
        advanceUntilIdle()

        assertEquals(160, assertIs<DrawingThumbnail.Ready>(model.thumbnailOf(0)).image.width)
    }

    @Test
    fun `a file that cannot be decoded gets no picture, only the mark that it has none`() = runTest {
        val model = model()

        model.attach(garbage("broken.png"))
        model.attach(garbage("broken.pdf"))
        model.attach(png("fine.png", 200, 200))
        advanceUntilIdle()

        assertEquals(listOf("broken.png", "broken.pdf", "fine.png"), model.state.value.rows.map { it.fileName })
        assertEquals(DrawingThumbnail.Unavailable, model.thumbnailOf(0))
        assertEquals(DrawingThumbnail.Unavailable, model.thumbnailOf(1))
        assertIs<DrawingThumbnail.Ready>(model.thumbnailOf(2))
        assertEquals(null, model.state.value.message, "nothing is said to the user")
    }

    @Test
    fun `reading the list again keeps the pictures that are made and does not decode the files a second time`() = runTest {
        val model = model()
        model.attach(png("plan.png", 400, 400))
        advanceUntilIdle()
        val first = assertIs<DrawingThumbnail.Ready>(model.thumbnailOf(0))
        // Were the file decoded again now, it would be found damaged.
        val attachment = deps.attachments.list(calculation).single()
        deps.attachments.pathOf(attachment).writeBytes(ByteArray(10))

        model.reload()
        advanceUntilIdle()

        assertSame(first, model.thumbnailOf(0))
    }

    @Test
    fun `a drawing removed before its picture was made leaves no picture behind`() = runTest {
        val model = model()
        model.attach(png("plan.png", 400, 400))
        model.attach(png("kept.png", 400, 400))
        val removed = model.state.value.rows.first()

        model.remove(removed.id)
        advanceUntilIdle()

        assertEquals(listOf("kept.png"), model.state.value.rows.map { it.fileName })
        assertEquals(setOf(model.state.value.rows.single().id), model.state.value.thumbnails.keys)
    }

    @Test
    fun `removing a drawing removes its picture from the state and its saved thumbnail from the disk`() = runTest {
        val model = model()
        model.attach(png("plan.png", 400, 400))
        advanceUntilIdle()
        val attachment = deps.attachments.list(calculation).single()
        val saved = deps.attachments.pathOf(attachment).resolveSibling(".thumbs").resolve("${attachment.storedName}.png")
        assertTrue(saved.exists(), "made and saved")

        model.remove(attachment.id)

        assertEquals(emptyMap(), model.state.value.thumbnails)
        assertFalse(saved.exists())
    }
}
