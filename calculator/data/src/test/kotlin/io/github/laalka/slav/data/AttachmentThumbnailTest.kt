package io.github.laalka.slav.data

import io.github.laalka.slav.core.PriceBook
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The thumbnails of drawings: made from the files, kept next to them, and gone with them. */
class AttachmentThumbnailTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val book = PriceBook.default()
    private val paths by lazy { AppPaths(dir.resolve("data")) }
    private val incoming by lazy { dir.resolve("incoming").createDirectories() }
    private lateinit var db: AppDatabase
    private lateinit var attachments: AttachmentRepository
    private lateinit var calculations: CalculationRepository

    @BeforeTest
    fun open() {
        db = DatabaseFactory(paths, clock).open()
        attachments = AttachmentRepository(db, clock)
        calculations = CalculationRepository(db, clock)
    }

    @AfterTest
    fun close() = db.close()

    private fun added(calculation: UUID, source: Path): Attachment =
        assertIs<AttachResult.Added>(attachments.add(calculation, source)).attachment

    private fun folder(calculation: UUID): Path = paths.attachmentsDir.resolve(calculation.toString())

    private fun cacheOf(attachment: Attachment): Path = folder(attachment.calculationId).resolve(".thumbs").resolve("${attachment.storedName}.png")

    private fun thumbnails(calculation: UUID): List<Path> = folder(calculation).resolve(".thumbs").let { if (it.exists()) it.listDirectoryEntries() else emptyList() }

    /** An image of two halves, [left] and [right], as a file of the format [format] ("png" or "jpg"). */
    private fun image(name: String, width: Int, height: Int, left: Color = Color.RED, right: Color = left, format: String = "png"): Path {
        val picture = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        picture.createGraphics().apply {
            color = left
            fillRect(0, 0, width / 2, height)
            color = right
            fillRect(width / 2, 0, width - width / 2, height)
            dispose()
        }
        return incoming.resolve(name).also { assertTrue(ImageIO.write(picture, format, it.toFile())) }
    }

    /** An A4 PDF with one page for each colour of [pages]; a page is white with its colour in the middle. */
    private fun pdf(name: String, vararg pages: Color): Path {
        val target = incoming.resolve(name)
        PDDocument().use { document ->
            for (color in pages) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.setNonStrokingColor(color)
                    content.addRect(100f, 200f, 395f, 442f)
                    content.fill()
                }
            }
            document.save(target.toFile())
        }
        return target
    }

    private fun garbage(name: String): Path = incoming.resolve(name).also { it.writeBytes(ByteArray(3000) { i -> (i * 7).toByte() }) }

    private fun BufferedImage.rgbAt(x: Int, y: Int): Int = getRGB(x, y) and 0xFFFFFF

    private fun BufferedImage.center(): Int = rgbAt(width / 2, height / 2)

    @Test
    fun `a png is scaled down to the bound with its aspect kept and saved next to the file`() {
        val calculation = UUID.randomUUID()
        val attachment = added(calculation, image("plan.png", 400, 200))

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 100))

        assertEquals(100 to 50, thumbnail.width to thumbnail.height)
        assertEquals(0xFF0000, thumbnail.center())
        val cached = cacheOf(attachment)
        assertEquals(folder(calculation).resolve(".thumbs").resolve("${attachment.id}-plan.png.png"), cached)
        val saved = assertNotNull(ImageIO.read(cached.toFile()), "the cache is a readable PNG")
        assertEquals(100 to 50, saved.width to saved.height)
    }

    @Test
    fun `a tall image is bound by its height`() {
        val attachment = added(UUID.randomUUID(), image("tall.png", 100, 400))

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 80))

        assertEquals(20 to 80, thumbnail.width to thumbnail.height)
    }

    @Test
    fun `an image smaller than the bound is not scaled up`() {
        val attachment = added(UUID.randomUUID(), image("small.png", 30, 20))

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 160))

        assertEquals(30 to 20, thumbnail.width to thumbnail.height)
    }

    @Test
    fun `a large image keeps its picture when only some of its pixels are read`() {
        val attachment = added(UUID.randomUUID(), image("big.png", 3200, 1600, left = Color.RED, right = Color.BLUE))

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 160))

        assertEquals(160 to 80, thumbnail.width to thumbnail.height)
        assertEquals(0xFF0000, thumbnail.rgbAt(thumbnail.width / 4, thumbnail.height / 2))
        assertEquals(0x0000FF, thumbnail.rgbAt(thumbnail.width * 3 / 4, thumbnail.height / 2))
    }

    @Test
    fun `a jpg and a jpeg get thumbnails too`() {
        val calculation = UUID.randomUUID()
        val jpg = added(calculation, image("photo.jpg", 300, 300, format = "jpg"))
        val jpeg = added(calculation, image("PHOTO.JPEG", 300, 150, format = "jpg"))

        val first = assertNotNull(attachments.thumbnail(jpg, 60))
        val second = assertNotNull(attachments.thumbnail(jpeg, 60))

        assertEquals(60 to 60, first.width to first.height)
        assertEquals(60 to 30, second.width to second.height)
        assertTrue(first.center() shr 16 > 0xD0 && first.center() and 0xFF < 0x30, "still red, as far as a jpeg can tell")
    }

    @Test
    fun `the first page of a pdf is drawn at the bound with the aspect of the page`() {
        val attachment = added(UUID.randomUUID(), pdf("plan.pdf", Color.RED, Color.BLUE))

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 100))

        // A4 is 595 × 842 points.
        assertEquals(100, thumbnail.height)
        assertTrue(thumbnail.width in 70..71, "width ${thumbnail.width}")
        assertEquals(0xFF0000, thumbnail.center(), "the first page, not the second")
        assertEquals(0xFFFFFF, thumbnail.rgbAt(1, 1), "the margin of the page is white")
        assertTrue(cacheOf(attachment).isRegularFile())
    }

    @Test
    fun `a pdf page larger than the bound by far is still drawn within it`() {
        val target = incoming.resolve("poster.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle(14_000f, 7_000f)))
            document.save(target.toFile())
        }
        val attachment = added(UUID.randomUUID(), target)

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 160))

        assertEquals(160 to 80, thumbnail.width to thumbnail.height)
    }

    @Test
    fun `a file that cannot be decoded has no thumbnail and leaves no cache`() {
        val calculation = UUID.randomUUID()
        val names = listOf("broken.png", "broken.jpg", "BROKEN.JPEG", "broken.pdf")

        names.forEach { name ->
            val attachment = added(calculation, garbage(name))
            assertNull(attachments.thumbnail(attachment, 100), name)
        }

        assertEquals(emptyList(), thumbnails(calculation))
    }

    @Test
    fun `a truncated image and a pdf with no pages have no thumbnail`() {
        val whole = image("whole.png", 400, 400).readBytes()
        val truncated = incoming.resolve("cut.png").also { it.writeBytes(whole.copyOf(whole.size / 2)) }
        val empty = incoming.resolve("empty.pdf").also { target -> PDDocument().use { it.save(target.toFile()) } }
        val calculation = UUID.randomUUID()

        assertNull(attachments.thumbnail(added(calculation, truncated), 100))
        assertNull(attachments.thumbnail(added(calculation, empty), 100))
        assertNull(attachments.thumbnail(added(calculation, incoming.resolve("zero.png").also { it.createFile() }), 100))
    }

    @Test
    fun `a pdf that asks for a password has no thumbnail`() {
        val target = incoming.resolve("secret.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.protect(StandardProtectionPolicy("owner-password", "user-password", AccessPermission()).apply { encryptionKeyLength = 128 })
            document.save(target.toFile())
        }

        val attachment = added(UUID.randomUUID(), target)

        assertNull(attachments.thumbnail(attachment, 100))
        assertFalse(cacheOf(attachment).exists())
    }

    @Test
    fun `a file that has vanished from the disk has no thumbnail, even when one was saved`() {
        val attachment = added(UUID.randomUUID(), image("plan.png", 200, 200))
        assertNotNull(attachments.thumbnail(attachment, 100))
        assertTrue(cacheOf(attachment).exists())

        attachments.pathOf(attachment).toFile().delete()

        assertNull(attachments.thumbnail(attachment, 100))
    }

    @Test
    fun `a thumbnail already saved is read instead of decoding the file again`() {
        val attachment = added(UUID.randomUUID(), image("plan.png", 400, 400, left = Color.GREEN))
        val first = assertNotNull(attachments.thumbnail(attachment, 100))
        // The stored file is damaged after the first request: only the saved picture can answer now.
        attachments.pathOf(attachment).writeBytes(ByteArray(100))

        val second = assertNotNull(attachments.thumbnail(attachment, 100))

        assertEquals(first.width to first.height, second.width to second.height)
        assertEquals(first.center(), second.center())
    }

    @Test
    fun `a saved thumbnail is scaled down for a smaller bound and made again for a larger one`() {
        val attachment = added(UUID.randomUUID(), image("plan.png", 400, 400))
        assertEquals(50, assertNotNull(attachments.thumbnail(attachment, 50)).width)

        val larger = assertNotNull(attachments.thumbnail(attachment, 100))
        assertEquals(100, larger.width, "the picture saved for 50 would have to be stretched")
        assertEquals(100, assertNotNull(ImageIO.read(cacheOf(attachment).toFile())).width, "and the new one replaces it")

        attachments.pathOf(attachment).writeBytes(ByteArray(100))
        assertEquals(40, assertNotNull(attachments.thumbnail(attachment, 40)).width, "the one saved for 100 is scaled down")
    }

    @Test
    fun `a saved thumbnail that cannot be read is made again`() {
        val attachment = added(UUID.randomUUID(), image("plan.png", 400, 200))
        cacheOf(attachment).also { it.parent.createDirectories() }.writeBytes(ByteArray(64) { 9 })

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 100))

        assertEquals(100 to 50, thumbnail.width to thumbnail.height)
        assertEquals(100, assertNotNull(ImageIO.read(cacheOf(attachment).toFile())).width)
        assertEquals(listOf(cacheOf(attachment)), thumbnails(attachment.calculationId), "no temporary file is left")
    }

    @Test
    fun `a thumbnail that cannot be saved is still returned`() {
        val calculation = UUID.randomUUID()
        val attachment = added(calculation, image("plan.png", 400, 200))
        // A file where the folder of the thumbnails should be.
        folder(calculation).resolve(".thumbs").createFile()

        val thumbnail = assertNotNull(attachments.thumbnail(attachment, 100))

        assertEquals(100 to 50, thumbnail.width to thumbnail.height)
    }

    @Test
    fun `the bound must be positive`() {
        val attachment = added(UUID.randomUUID(), image("plan.png", 40, 40))

        assertFailsWith<IllegalArgumentException> { attachments.thumbnail(attachment, 0) }
        assertFailsWith<IllegalArgumentException> { attachments.thumbnail(attachment, -5) }
    }

    @Test
    fun `removing an attachment removes its thumbnail with it, the others keep theirs`() {
        val calculation = UUID.randomUUID()
        val removed = added(calculation, image("a.png", 200, 200))
        val kept = added(calculation, image("b.png", 200, 200))
        attachments.thumbnail(removed, 100)
        attachments.thumbnail(kept, 100)
        assertTrue(cacheOf(removed).exists() && cacheOf(kept).exists())

        attachments.remove(removed.id)

        assertFalse(cacheOf(removed).exists())
        assertTrue(cacheOf(kept).exists())
        assertEquals(listOf(cacheOf(kept)), thumbnails(calculation))
    }

    @Test
    fun `an attachment whose file cannot be deleted keeps its thumbnail with it`() {
        val calculation = UUID.randomUUID()
        val attachment = added(calculation, image("a.png", 200, 200))
        attachments.thumbnail(attachment, 100)
        // A folder with something in it where the file was: deleting fails the way a file held open does.
        attachments.pathOf(attachment).toFile().delete()
        attachments.pathOf(attachment).createDirectories().resolve("inside").createFile()

        assertFailsWith<java.io.IOException> { attachments.remove(attachment.id) }

        assertEquals(listOf(attachment), attachments.list(calculation))
        assertTrue(cacheOf(attachment).exists())
    }

    @Test
    fun `removing all attachments of a calculation removes the thumbnails with the folder, not anybody else's`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val own = added(first, image("a.png", 200, 200))
        val other = added(second, image("b.png", 200, 200))
        attachments.thumbnail(own, 100)
        attachments.thumbnail(other, 100)

        attachments.removeAll(first)

        assertFalse(folder(first).exists())
        assertTrue(cacheOf(other).exists())
    }

    @Test
    fun `deleting a calculation removes its thumbnails`() {
        val id = calculations.saveCalculated(sheetInput("Кронштейн"), book, book.priced(sheetInput("Кронштейн")))
        val attachment = added(id, image("a.png", 200, 200))
        attachments.thumbnail(attachment, 100)

        calculations.delete(id)

        assertFalse(folder(id).exists())
    }

    @Test
    fun `copyAll copies the files and not the thumbnails, the copy makes its own`() {
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val original = added(source, image("a.png", 400, 200, left = Color.RED, right = Color.BLUE))
        attachments.thumbnail(original, 100)
        assertEquals(1, thumbnails(source).size)

        attachments.copyAll(source, target)

        val copy = attachments.list(target).single()
        assertContentEquals(attachments.pathOf(original).readBytes(), attachments.pathOf(copy).readBytes())
        assertEquals(emptyList(), thumbnails(target))
        assertFalse(folder(target).resolve(".thumbs").exists())
        assertEquals(listOf(attachments.pathOf(copy)), folder(target).listDirectoryEntries())

        val thumbnail = assertNotNull(attachments.thumbnail(copy, 100))
        assertEquals(100 to 50, thumbnail.width to thumbnail.height)
        assertEquals(listOf(cacheOf(copy)), thumbnails(target))
        assertEquals(listOf(cacheOf(original)), thumbnails(source), "the original keeps its own")
    }

    @Test
    fun `a copy of a calculation does not carry the thumbnails of its source`() {
        val id = calculations.saveCalculated(sheetInput("Кронштейн"), book, book.priced(sheetInput("Кронштейн")))
        val attachment = added(id, image("a.png", 200, 200))
        attachments.thumbnail(attachment, 100)

        val copy = calculations.copy(id)

        assertEquals(1, attachments.list(copy).size)
        assertEquals(emptyList(), thumbnails(copy))
        assertEquals(1, thumbnails(id).size)
    }
}
