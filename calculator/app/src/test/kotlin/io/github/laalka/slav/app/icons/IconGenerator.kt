package io.github.laalka.slav.app.icons

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.laalka.slav.app.ui.SlavLogoPainter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.Path

/** The sizes of the icon of Windows: Explorer, the taskbar and the start menu pick the one they need. */
internal val ICO_SIZES = listOf(16, 32, 48, 256)

/** Where the big icon of Linux and the preview of the others come from. */
internal const val PNG_SIZE = 512

// The share of the canvas a macOS icon leaves empty on each side: the icons of the system sit on a grid in which the
// rounded square is 824 of 1024 pixels, so a logo that fills the canvas looks too big in the Dock.
internal const val MACOS_MARGIN = 100.0 / 1024

/** The entries of an .icns file: its type code and the side of the picture in pixels (a `@2x` icon is twice its name). */
internal val ICNS_ENTRIES = listOf(
    "icp4" to 16, "icp5" to 32, "icp6" to 64,
    "ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024,
    "ic11" to 32, "ic12" to 64, "ic13" to 256, "ic14" to 512,
)

/**
 * The logo as the program draws it ([SlavLogoPainter], the icon of its window), [side] pixels wide, with [margin] (a share
 * of [side]) of transparent canvas around it, as straight-alpha ARGB.
 */
internal fun renderLogo(side: Int, margin: Double = 0.0): BufferedImage {
    val bitmap = ImageBitmap(side, side)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
        inset((side * margin).toFloat()) {
            with(SlavLogoPainter) { draw(size = size) }
        }
    }
    // Skia keeps premultiplied alpha; the files want straight alpha. Java2D converts when it draws.
    return BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB).also { straight ->
        straight.createGraphics().apply {
            drawImage(bitmap.toAwtImage(), 0, 0, null)
            dispose()
        }
    }
}

internal fun pngBytes(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { out ->
    check(ImageIO.write(image, "png", out)) { "no PNG writer" }
}.toByteArray()

/**
 * `Slav.ico`: the sizes of [ICO_SIZES]. The small ones are the classic 32-bit bitmaps every version of Windows reads;
 * the 256 one is a PNG, which is how Windows Vista and later take that size.
 */
internal fun icoBytes(): ByteArray {
    val pictures = ICO_SIZES.map { side ->
        val image = renderLogo(side)
        side to if (side >= 256) pngBytes(image) else dibBytes(image)
    }
    val header = 6 + 16 * pictures.size
    val out = ByteBuffer.allocate(header + pictures.sumOf { it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
    out.putShort(0).putShort(1).putShort(pictures.size.toShort())
    var offset = header
    for ((side, data) in pictures) {
        // A side of 256 is written as 0.
        out.put((side % 256).toByte()).put((side % 256).toByte()).put(0).put(0)
        out.putShort(1).putShort(32).putInt(data.size).putInt(offset)
        offset += data.size
    }
    pictures.forEach { out.put(it.second) }
    return out.array()
}

/** An icon picture as the Windows resource wants it: a bitmap header, the pixels as BGRA from the bottom row up, and an empty transparency mask. */
private fun dibBytes(image: BufferedImage): ByteArray {
    val side = image.width
    val mask = (side + 31) / 32 * 4 * side
    val out = ByteBuffer.allocate(40 + 4 * side * side + mask).order(ByteOrder.LITTLE_ENDIAN)
    // The height of an icon bitmap counts the mask as well, so it is twice the picture's.
    out.putInt(40).putInt(side).putInt(2 * side).putShort(1).putShort(32).putInt(0).putInt(4 * side * side + mask)
    out.putInt(0).putInt(0).putInt(0).putInt(0)
    for (y in side - 1 downTo 0) {
        for (x in 0 until side) out.putInt(image.getRGB(x, y))
    }
    return out.array()
}

/** `Slav.icns`: every size of [ICNS_ENTRIES], with the margin of the system's icons, as PNG. */
internal fun icnsBytes(): ByteArray {
    val pictures = ICNS_ENTRIES.map { (type, side) -> type to pngBytes(renderLogo(side, MACOS_MARGIN)) }
    val out = ByteBuffer.allocate(8 + pictures.sumOf { 8 + it.second.size }).order(ByteOrder.BIG_ENDIAN)
    out.put("icns".toByteArray(Charsets.US_ASCII)).putInt(out.capacity())
    for ((type, data) in pictures) {
        out.put(type.toByteArray(Charsets.US_ASCII)).putInt(8 + data.size).put(data)
    }
    return out.array()
}

/** `Slav.png`: the icon of Linux, [PNG_SIZE] pixels. */
internal fun linuxPngBytes(): ByteArray = pngBytes(renderLogo(PNG_SIZE))

/** Writes the three icon files into the folder given as the only argument; the Gradle task `generateIcons` runs this. */
fun main(args: Array<String>) {
    val folder: Path = Path(args.single())
    Files.createDirectories(folder)
    Files.write(folder.resolve("Slav.ico"), icoBytes())
    Files.write(folder.resolve("Slav.icns"), icnsBytes())
    Files.write(folder.resolve("Slav.png"), linuxPngBytes())
}
