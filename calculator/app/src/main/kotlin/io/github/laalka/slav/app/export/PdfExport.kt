package io.github.laalka.slav.app.export

import io.github.laalka.slav.app.calculations.Breakdown
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.app.ui.formatMoney
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.IOException
import java.nio.file.Path

// Noto Sans (SIL Open Font License, fonts/OFL.txt): the standard PDF fonts have no Cyrillic, so
// the font is embedded, as a subset of the glyphs used.
private const val REGULAR_FONT = "/fonts/NotoSans-Regular.ttf"
private const val BOLD_FONT = "/fonts/NotoSans-Bold.ttf"

private const val MARGIN = 56f
private const val ROW_GAP = 10f
private const val GREY = 0.4f

/**
 * The client's copy of a calculation (spec 8.5): its name, the date of its prices, the run size,
 * the unit price and the total, on one A4 page. No breakdown and no company details.
 */
fun writeClientPdf(breakdown: Breakdown, currency: String, target: Path) {
    PDDocument().use { document ->
        val regular = loadFont(document, REGULAR_FONT)
        val bold = loadFont(document, BOLD_FONT)
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        document.documentInformation.title = breakdown.name
        val width = page.mediaBox.width - 2 * MARGIN
        PDPageContentStream(document, page).use { content ->
            val writer = PageWriter(content, left = MARGIN, right = page.mediaBox.width - MARGIN, top = page.mediaBox.height - MARGIN)
            writer.text("Расчёт стоимости", regular, 11f, grey = true)
            writer.gap(6f)
            wrap(breakdown.name.ifBlank { "Без названия" }, bold, 20f, width).forEach { writer.text(it, bold, 20f) }
            writer.gap(4f)
            writer.text("Дата: ${formatDate(breakdown.snapshotDate)}", regular, 11f, grey = true)
            writer.gap(28f)
            writer.row("Тираж", "${breakdown.quantity} шт", regular, regular, 12f)
            writer.row("Цена изделия", formatMoney(breakdown.unitPrice, currency), regular, regular, 12f)
            writer.rule()
            writer.row("Итог", formatMoney(breakdown.total, currency), bold, bold, 14f)
        }
        document.save(target.toFile())
    }
}

private fun loadFont(document: PDDocument, resource: String): PDFont {
    val stream = PageWriter::class.java.getResourceAsStream(resource) ?: throw IOException("Font $resource is missing from the program")
    return stream.use { PDType0Font.load(document, it, true) }
}

/** Lays text out from the top of the page down; [top] is the baseline of the first line. */
private class PageWriter(private val content: PDPageContentStream, private val left: Float, private val right: Float, top: Float) {
    private var y = top

    fun text(text: String, font: PDFont, size: Float, grey: Boolean = false) {
        y -= size
        show(printable(text, font), font, size, left, grey)
        y -= size * 0.35f
    }

    /** [label] on the left, [value] aligned to the right edge. */
    fun row(label: String, value: String, labelFont: PDFont, valueFont: PDFont, size: Float) {
        y -= size
        show(printable(label, labelFont), labelFont, size, left, grey = false)
        val shown = printable(value, valueFont)
        show(shown, valueFont, size, right - width(shown, valueFont, size), grey = false)
        y -= ROW_GAP
    }

    fun rule() {
        y -= 2f
        content.setStrokingColor(GREY, GREY, GREY)
        content.setLineWidth(0.5f)
        content.moveTo(left, y)
        content.lineTo(right, y)
        content.stroke()
        y -= ROW_GAP
    }

    fun gap(points: Float) {
        y -= points
    }

    private fun show(text: String, font: PDFont, size: Float, x: Float, grey: Boolean) {
        content.beginText()
        content.setFont(font, size)
        if (grey) content.setNonStrokingColor(GREY, GREY, GREY) else content.setNonStrokingColor(0f, 0f, 0f)
        content.newLineAtOffset(x, y)
        content.showText(text)
        content.endText()
    }
}

private fun width(text: String, font: PDFont, size: Float): Float = font.getStringWidth(text) / 1000f * size

/** Splits [text] into lines no wider than [maxWidth], at spaces where possible. */
private fun wrap(text: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
    val shown = printable(text, font)
    val lines = mutableListOf<String>()
    var line = ""
    for (word in shown.split(' ').filter { it.isNotEmpty() }) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        when {
            width(candidate, font, size) <= maxWidth -> line = candidate
            width(word, font, size) <= maxWidth -> {
                lines += line
                line = word
            }
            else -> {
                // A word wider than the page is broken where it reaches the edge.
                if (line.isNotEmpty()) lines += line
                line = ""
                for (char in word) {
                    if (line.isNotEmpty() && width(line + char, font, size) > maxWidth) {
                        lines += line
                        line = ""
                    }
                    line += char
                }
            }
        }
    }
    if (line.isNotEmpty()) lines += line
    return lines
}

/**
 * [text] with every character the font has no glyph for replaced by "?", and control characters
 * by spaces: a name may hold anything typed or pasted, and PDFBox refuses to write a missing glyph.
 */
private fun printable(text: String, font: PDFont): String = buildString {
    text.codePoints().forEach { codePoint ->
        val char = String(Character.toChars(codePoint))
        append(
            when {
                Character.isISOControl(codePoint) -> " "
                font.canEncode(char) -> char
                else -> "?"
            },
        )
    }
}

private fun PDFont.canEncode(text: String): Boolean = try {
    encode(text)
    true
} catch (e: IllegalArgumentException) {
    false
}
