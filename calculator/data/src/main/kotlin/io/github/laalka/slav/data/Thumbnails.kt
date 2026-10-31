package io.github.laalka.slav.data

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small pictures of drawings: an image file, or the first page of a PDF, scaled down to fit a
 * square. Everything here is plain AWT, so it runs without the UI.
 */
internal object Thumbnails {
    // A PDF page is drawn at this resolution and then scaled down; a page so large that this would
    // pass MAX_PAGE_PX is drawn at the lower resolution that does not.
    private const val PDF_DPI = 40f
    private const val PDF_POINTS_PER_INCH = 72f
    private const val MAX_PAGE_PX = 2048

    /**
     * The picture of [file], at most [maxPx] on its longer side, the aspect kept and never scaled up;
     * null when the file cannot be decoded: damaged, of another kind than its extension says, a PDF
     * that asks for a password, or not there.
     */
    fun render(file: Path, maxPx: Int): BufferedImage? {
        require(maxPx > 0) { "maxPx must be positive, was $maxPx" }
        return try {
            val picture = if (file.extension.lowercase() == "pdf") firstPage(file) else image(file, maxPx)
            picture?.let { scaledDown(it, maxPx) }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // The decoders of damaged files fail with whatever is at hand: IllegalArgument, ArrayIndexOutOfBounds, ...
            null
        } catch (_: StackOverflowError) {
            // A page tree that refers to itself; a picture is not worth the program.
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * [image] scaled so that its longer side is at most [maxPx], in steps of at most one half so that
     * no source pixel is skipped; always as ARGB, whatever the decoder made. A smaller image is only
     * converted.
     */
    fun scaledDown(image: BufferedImage, maxPx: Int): BufferedImage {
        val longer = max(image.width, image.height)
        val ratio = if (longer <= maxPx) 1.0 else maxPx.toDouble() / longer
        val targetWidth = max(1, (image.width * ratio).roundToInt())
        val targetHeight = max(1, (image.height * ratio).roundToInt())
        var current = image
        while (current.width > targetWidth || current.height > targetHeight) {
            current = resized(current, max(targetWidth, current.width / 2), max(targetHeight, current.height / 2))
        }
        return if (current.type == BufferedImage.TYPE_INT_ARGB) current else resized(current, current.width, current.height)
    }

    private fun resized(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return result
    }

    /**
     * Decodes [file] keeping only every n-th pixel when it is much larger than [maxPx], so a huge
     * picture costs the memory of a small one. Null when no decoder knows the file.
     */
    private fun image(file: Path, maxPx: Int): BufferedImage? {
        val input = ImageIO.createImageInputStream(file.toFile()) ?: return null
        input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, false, true)
                val step = max(1, max(reader.getWidth(0), reader.getHeight(0)) / (2 * maxPx))
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                return reader.read(0, parameters)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun firstPage(file: Path): BufferedImage? =
        Loader.loadPDF(file.toFile()).use { document ->
            if (document.numberOfPages == 0) return null
            val page = document.getPage(0).cropBox
            val longer = max(page.width, page.height)
            if (longer <= 0f) return null
            val renderer = PDFRenderer(document).apply { isSubsamplingAllowed = true }
            renderer.renderImageWithDPI(0, min(PDF_DPI, MAX_PAGE_PX * PDF_POINTS_PER_INCH / longer))
        }
}
