package io.github.laalka.slav.app.icons

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The first bytes of every PNG.
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

private fun committed(name: String): ByteArray {
    val file = File(System.getProperty("user.dir"), "icons/$name")
    assertTrue(file.isFile, "$file is not in the repository: run ./gradlew :app:generateIcons")
    return file.readBytes()
}

private fun decodePng(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "not a PNG")

/** The pictures of an .ico by their side: a PNG one is decoded, a bitmap one is read from its BGRA rows, bottom row first. */
private fun icoPictures(bytes: ByteArray): Map<Int, BufferedImage> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(0, buffer.getShort(0).toInt(), "reserved")
    assertEquals(1, buffer.getShort(2).toInt(), "type: an icon, not a cursor")
    val count = buffer.getShort(4).toInt()
    return (0 until count).associate { index ->
        val entry = 6 + 16 * index
        val side = (buffer.get(entry).toInt() and 0xFF).let { if (it == 0) 256 else it }
        assertEquals(side, (buffer.get(entry + 1).toInt() and 0xFF).let { if (it == 0) 256 else it }, "the picture is square")
        assertEquals(1, buffer.getShort(entry + 4).toInt(), "planes")
        assertEquals(32, buffer.getShort(entry + 6).toInt(), "bits per pixel")
        val size = buffer.getInt(entry + 8)
        val offset = buffer.getInt(entry + 12)
        val data = bytes.copyOfRange(offset, offset + size)
        side to if (data.take(4).toByteArray().contentEquals(PNG_SIGNATURE)) decodePng(data) else decodeDib(data, side)
    }
}

private fun decodeDib(data: ByteArray, side: Int): BufferedImage {
    val dib = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(40, dib.getInt(0), "the size of the bitmap header")
    assertEquals(side, dib.getInt(4), "width")
    assertEquals(2 * side, dib.getInt(8), "height: the picture and its mask")
    assertEquals(32, dib.getShort(14).toInt(), "bits per pixel")
    assertEquals(0, dib.getInt(16), "compression")
    val mask = (side + 31) / 32 * 4 * side
    assertEquals(40 + 4 * side * side + mask, data.size, "the size of the picture and its mask")
    return BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (row in 0 until side) {
            for (x in 0 until side) image.setRGB(x, side - 1 - row, dib.getInt(40 + 4 * (row * side + x)))
        }
    }
}

/** An .icns by the type of its entries; each is a PNG. */
private fun icnsEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    assertEquals("icns", String(bytes, 0, 4, Charsets.US_ASCII), "magic")
    assertEquals(bytes.size, buffer.getInt(4), "the length of the file")
    val entries = mutableListOf<Pair<String, ByteArray>>()
    var offset = 8
    while (offset < bytes.size) {
        val length = buffer.getInt(offset + 4)
        entries += String(bytes, offset, 4, Charsets.US_ASCII) to bytes.copyOfRange(offset + 8, offset + length)
        offset += length
    }
    assertEquals(bytes.size, offset, "the entries fill the file")
    return entries
}

/**
 * How far two pictures are from each other: the share of pixels whose channels differ by more than 24. The edges of the
 * letter are anti-aliased and another machine may round them differently; a changed logo moves far more than that.
 */
private fun difference(a: BufferedImage, b: BufferedImage): Double {
    assertEquals(a.width to a.height, b.width to b.height, "the size")
    var different = 0
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            val far = (0..24 step 8).any { shift -> abs(((p ushr shift) and 0xFF) - ((q ushr shift) and 0xFF)) > 24 }
            if (far) different++
        }
    }
    return different.toDouble() / (a.width * a.height)
}

private const val TOLERATED = 0.005

/** The committed icons are the logo as the program draws it now; `./gradlew :app:generateIcons` redraws them. */
class IconFilesTest {
    @Test
    fun `Slav ico holds 16, 32, 48 and 256 and each is the logo`() {
        val pictures = icoPictures(committed("Slav.ico"))
        assertEquals(listOf(16, 32, 48, 256), pictures.keys.toList())
        pictures.forEach { (side, image) ->
            assertTrue(difference(image, renderLogo(side)) <= TOLERATED, "the ${side}px picture of Slav.ico is not the logo: run ./gradlew :app:generateIcons")
        }
    }

    @Test
    fun `the small pictures of Slav ico are bitmaps and the 256 one a PNG, as Windows reads them`() {
        val bytes = committed("Slav.ico")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val kinds = (0 until 4).map { index ->
            val offset = buffer.getInt(6 + 16 * index + 12)
            if (bytes.copyOfRange(offset, offset + 4).contentEquals(PNG_SIGNATURE)) "png" else "bitmap"
        }
        assertEquals(listOf("bitmap", "bitmap", "bitmap", "png"), kinds)
    }

    @Test
    fun `Slav icns holds every size of macOS and each is the logo with the margin of the system's icons`() {
        val entries = icnsEntries(committed("Slav.icns"))
        assertEquals(ICNS_ENTRIES.map { it.first }, entries.map { it.first })
        entries.zip(ICNS_ENTRIES).forEach { (entry, expected) ->
            val image = decodePng(entry.second)
            assertEquals(expected.second to expected.second, image.width to image.height, "${entry.first} is ${expected.second}px")
            assertTrue(difference(image, renderLogo(expected.second, MACOS_MARGIN)) <= TOLERATED, "${entry.first} of Slav.icns is not the logo: run ./gradlew :app:generateIcons")
        }
        // The margin: 100 of 1024 pixels are empty on each side, the plate starts after them.
        val biggest = decodePng(entries.first { it.first == "ic10" }.second)
        assertEquals(0, biggest.getRGB(90, 512) ushr 24, "the margin is transparent")
        assertEquals(255, biggest.getRGB(110, 512) ushr 24, "the plate starts after the margin")
    }

    @Test
    fun `Slav png is 512 pixels and the logo filling its canvas`() {
        val image = decodePng(committed("Slav.png"))
        assertEquals(PNG_SIZE to PNG_SIZE, image.width to image.height)
        assertTrue(difference(image, renderLogo(PNG_SIZE)) <= TOLERATED, "Slav.png is not the logo: run ./gradlew :app:generateIcons")
        assertEquals(255, image.getRGB(2, PNG_SIZE / 2) ushr 24, "the logo fills the canvas")
    }

    @Test
    fun `the generator writes what is committed`() {
        // The writers are the code the task runs: what they write now is what the repository holds.
        val fresh = icoPictures(icoBytes())
        val kept = icoPictures(committed("Slav.ico"))
        assertEquals(kept.keys, fresh.keys)
        fresh.forEach { (side, image) -> assertTrue(difference(image, kept.getValue(side)) <= TOLERATED, "the ${side}px picture of Slav.ico is out of date") }
        val freshEntries = icnsEntries(icnsBytes())
        val keptEntries = icnsEntries(committed("Slav.icns"))
        assertEquals(keptEntries.map { it.first }, freshEntries.map { it.first })
        freshEntries.zip(keptEntries).forEach { (now, before) ->
            assertTrue(difference(decodePng(now.second), decodePng(before.second)) <= TOLERATED, "${now.first} of Slav.icns is out of date")
        }
        assertTrue(difference(decodePng(linuxPngBytes()), decodePng(committed("Slav.png"))) <= TOLERATED, "Slav.png is out of date")
    }
}
