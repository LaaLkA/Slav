package io.github.laalka.slav.app.export

import io.github.laalka.slav.app.calculations.Breakdown
import io.github.laalka.slav.app.calculations.BreakdownLine
import io.github.laalka.slav.app.calculations.breakdown
import io.github.laalka.slav.app.calculations.discountLabel
import io.github.laalka.slav.app.calculations.formatDiscount
import io.github.laalka.slav.app.calculations.manualExtraNote
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.app.ui.formatMeasure
import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.data.StoredCalculation
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

// Noto Sans (SIL Open Font License, fonts/OFL.txt): the standard PDF fonts have no Cyrillic, so
// the font is embedded, as a subset of the glyphs used.
private const val REGULAR_FONT = "/fonts/NotoSans-Regular.ttf"
private const val BOLD_FONT = "/fonts/NotoSans-Bold.ttf"

private const val MARGIN = 56f
private const val ROW_GAP = 10f
private const val GREY = 0.4f

/** The space under a line of text, as a share of its size. */
private const val AFTER_LINE = 0.35f

/** Between a label and its amount, at the least. */
private const val COLUMN_GAP = 16f

/** Under a line of the breakdown, and under the part's own price. */
private const val LINE_GAP = 5f
private const val PART_GAP = 15f

/** The note under a line («в т. ч. доп. операции …») hangs from the line, a little to the right of it. */
private const val NOTE_GAP = 2f
private const val NOTE_INDENT = 12f

private const val FOOTER_SIZE = 9f
private const val FOOTER_BASELINE = 30f

private val PAGE = PDRectangle.A4
private val LEFT = MARGIN
private val RIGHT = PAGE.width - MARGIN
private val WIDTH = RIGHT - LEFT
private val TOP = PAGE.height - MARGIN

/** Writes the client's copy of [breakdown] ([clientPdfBytes]) to [target], replacing the file. */
fun writeClientPdf(breakdown: Breakdown, currency: String, target: Path) {
    // Made whole before the file is touched, so a PDF that cannot be made leaves the old file as it was.
    Files.write(target, clientPdfBytes(breakdown, currency))
}

/**
 * The client's copy of a calculation: its name, the counterparty when there is one, the date of its prices and its
 * status; the run size, the sum before the discount and the discount when there is one, the unit price, the total and
 * the masses; and the full breakdown as the card of the calculation shows it: every part with its mass, its lines and
 * its price, the extras of the lines with their comments, then the operations of the product itself (spec 8.5 left the
 * breakdown out of the PDF; it is in now). A simplified calculation has no run size, so it shows neither the run size
 * nor a total nor a run mass: its unit price is its price. No company details.
 *
 * The pages are A4: a line is never split between two pages, a part goes on over the next one, and every page is
 * numbered «Стр. N из M». The PDF comes as bytes, for a place that is not a file of its own: the archive of calculations.
 */
fun clientPdfBytes(breakdown: Breakdown, currency: String): ByteArray = ByteArrayOutputStream().also { out ->
    PDDocument().use { document ->
        val regular = loadFont(document, REGULAR_FONT)
        val bold = loadFont(document, BOLD_FONT)
        document.documentInformation.title = breakdown.name
        PageWriter(document).use { writer ->
            writer.add(heading(breakdown, regular, bold))
            writer.add(summary(breakdown, currency, regular, bold))
            writer.add(breakdownSection(breakdown, currency, regular, bold))
            writer.add(closingRows(breakdown, currency, regular, bold))
            writer.addPageNumbers(regular)
        }
        document.save(out)
    }
}.toByteArray()

/** The client's copy of [calculation] for the archive; null for a draft, which has no price to show. */
fun clientPdfBytes(calculation: StoredCalculation, currency: String, zone: ZoneId): ByteArray? =
    calculation.breakdown(zone)?.let { clientPdfBytes(it, currency) }

private fun loadFont(document: PDDocument, resource: String): PDFont {
    val stream = PageWriter::class.java.getResourceAsStream(resource) ?: throw IOException("Font $resource is missing from the program")
    return stream.use { PDType0Font.load(document, it, true) }
}

private fun heading(breakdown: Breakdown, regular: PDFont, bold: PDFont): List<Block> {
    val counterparty = breakdown.counterparty.takeIf { it.isNotBlank() }
    return listOfNotNull(
        paragraph("Расчёт стоимости", regular, 11f, grey = true).spaced(6f),
        paragraph(breakdown.name.ifBlank { UNNAMED }, bold, 20f).spaced(4f),
        counterparty?.let { paragraph("Контрагент: $it", regular, 12f).spaced(4f) },
        paragraph("Дата: ${formatDate(breakdown.snapshotDate)}", regular, 11f, grey = true),
        paragraph("Статус: ${breakdown.status.title}", regular, 11f, grey = true).spaced(28f),
    )
}

/** The price and the masses: what the client pays for and what it weighs. */
private fun summary(breakdown: Breakdown, currency: String, regular: PDFont, bold: PDFont): List<Block> = buildList {
    if (breakdown.hasRunSize) add(row("Тираж", "${breakdown.quantity} шт", regular, regular, 12f))
    breakdown.discountPercent?.takeIf { breakdown.hasDiscount }?.let { percent ->
        add(row("Сумма без скидки", formatMoney(breakdown.subtotal, currency), regular, regular, 12f))
        add(row(discountLabel(percent), formatDiscount(breakdown.discount, currency), regular, regular, 12f))
    }
    if (breakdown.hasRunSize) {
        add(row("Цена за единицу", formatMoney(breakdown.unitPrice, currency), regular, regular, 12f))
        add(rule())
        add(row("Итого за тираж", formatMoney(breakdown.total, currency), bold, bold, 14f))
    } else {
        add(rule())
        add(row("Цена за единицу", formatMoney(breakdown.unitPrice, currency), bold, bold, 14f))
    }
    val masses = listOfNotNull(
        row("Масса изделия", kg(breakdown.unitMassKg), regular, regular, 12f),
        row("Масса тиража", kg(breakdown.runMassKg), regular, regular, 12f).takeIf { breakdown.hasRunSize },
    )
    addAll(masses.dropLast(1))
    add(masses.last().spaced(14f))
}

/**
 * «Разбивка» as the card has it: each part with its mass, its lines and its price, then the lines of the product. The
 * heading of a block stays with the first line under it, and the last line of a part with the price of the part.
 */
private fun breakdownSection(breakdown: Breakdown, currency: String, regular: PDFont, bold: PDFont): List<Block> = buildList {
    add(paragraph("Разбивка", bold, 14f, after = 6f).keepingWithNext())
    breakdown.parts.forEach { part ->
        add(
            (
                paragraph(part.name.ifBlank { UNNAMED }, bold, 12f, after = 2f) +
                    paragraph("Масса: ${kg(part.massKg)}", regular, 10f, grey = true, after = 4f)
                ).keepingWithNext(),
        )
        addAll(part.lines.map { breakdownLine(it, currency, regular) }.keepingLastWithNext())
        add(row("Цена детали", formatMoney(part.cost, currency), bold, bold, 11f, after = PART_GAP))
    }
    if (breakdown.productLines.isNotEmpty()) {
        // A simplified calculation has no parts: its lines are the product's, with no caption, as on the card.
        if (breakdown.parts.isNotEmpty()) add(paragraph("Операции изделия", regular, 10f, grey = true, after = 4f).keepingWithNext())
        addAll(breakdown.productLines.map { breakdownLine(it, currency, regular) })
    }
}

/** A line of the breakdown, with the note about its manual extra under it. */
private fun breakdownLine(line: BreakdownLine, currency: String, regular: PDFont): Block {
    val note = line.extra?.let { paragraph(manualExtraNote(it, currency), regular, 10f, indent = NOTE_INDENT, grey = true, after = LINE_GAP) }
    val main = row(line.title, formatMoney(line.cost, currency), regular, regular, 11f, after = if (note == null) LINE_GAP else NOTE_GAP)
    return if (note == null) main else main + note
}

/**
 * What the lines add up to: the sum before the discount, the discount and the price of one piece. Without a discount
 * the sum is the price, so it is written once, as the price.
 */
private fun closingRows(breakdown: Breakdown, currency: String, regular: PDFont, bold: PDFont): List<Block> {
    val price = row("Цена за единицу", formatMoney(breakdown.unitPrice, currency), bold, bold, 12f)
    val rows = breakdown.discountPercent?.takeIf { breakdown.hasDiscount }?.let { percent ->
        listOf(
            row("Сумма без скидки", formatMoney(breakdown.subtotal, currency), bold, bold, 12f),
            row(discountLabel(percent), formatDiscount(breakdown.discount, currency), regular, regular, 12f),
            price,
        )
    } ?: listOf(price)
    return (listOf(rule(before = 8f)) + rows).keepingAllButLastWithNext()
}

private fun kg(value: BigDecimal) = "${formatMeasure(value)} кг"

private const val UNNAMED = "Без названия"

/** One horizontal slice of a page, [height] tall; [paint] draws it with its top edge at the given y. */
private class Strip(val height: Float, val paint: PDPageContentStream.(top: Float) -> Unit)

/**
 * Strips that belong together. They go on one page; only a block taller than a page flows over several, breaking
 * between its strips. With [keepWithNext] the block is not left at the bottom of a page without the block after it.
 */
private class Block(val strips: List<Strip>, val keepWithNext: Boolean = false) {
    val height: Float = strips.fold(0f) { sum, strip -> sum + strip.height }

    /** This block, then [other]; the result keeps with the next block as [other] does. */
    operator fun plus(other: Block) = Block(strips + other.strips, other.keepWithNext)

    fun keepingWithNext() = Block(strips, keepWithNext = true)

    /** This block with [points] of empty space under it. */
    fun spaced(points: Float) = Block(strips + Strip(points) {}, keepWithNext)
}

private fun List<Block>.keepingLastWithNext(): List<Block> = mapIndexed { index, block -> if (index == lastIndex) block.keepingWithNext() else block }

private fun List<Block>.keepingAllButLastWithNext(): List<Block> = mapIndexed { index, block -> if (index < lastIndex) block.keepingWithNext() else block }

/** [text] wrapped to the width of the page less [indent]; [after] is the space under its last line. */
private fun paragraph(text: String, font: PDFont, size: Float, indent: Float = 0f, grey: Boolean = false, after: Float = size * AFTER_LINE): Block {
    val lines = wrap(text, font, size, WIDTH - indent)
    return Block(
        lines.mapIndexed { index, line ->
            Strip(size + if (index == lines.lastIndex) after else size * AFTER_LINE) { top -> show(line, font, size, LEFT + indent, top - size, grey) }
        },
    )
}

/**
 * [label] on the left and [value] aligned to the right edge. A label too long for the room beside the amount wraps
 * inside its own column, so it never runs into the amount, which stays on its first line; [after] is the space under the row.
 */
private fun row(label: String, value: String, labelFont: PDFont, valueFont: PDFont, size: Float, after: Float = ROW_GAP): Block {
    val shown = printable(value, valueFont)
    val valueWidth = width(shown, valueFont, size)
    val lines = wrap(label, labelFont, size, WIDTH - valueWidth - COLUMN_GAP).ifEmpty { listOf("") }
    return Block(
        lines.mapIndexed { index, line ->
            Strip(size + if (index == lines.lastIndex) after else size * AFTER_LINE) { top ->
                if (line.isNotEmpty()) show(line, labelFont, size, LEFT, top - size, grey = false)
                if (index == 0) show(shown, valueFont, size, RIGHT - valueWidth, top - size, grey = false)
            }
        },
    )
}

/** A thin grey line across the page, [before] below the block above it. */
private fun rule(before: Float = 2f): Block = Block(
    listOf(
        Strip(before + ROW_GAP) { top ->
            setStrokingColor(GREY, GREY, GREY)
            setLineWidth(0.5f)
            moveTo(LEFT, top - before)
            lineTo(RIGHT, top - before)
            stroke()
        },
    ),
    keepWithNext = true,
)

private fun PDPageContentStream.show(text: String, font: PDFont, size: Float, x: Float, baseline: Float, grey: Boolean) {
    beginText()
    setFont(font, size)
    if (grey) setNonStrokingColor(GREY, GREY, GREY) else setNonStrokingColor(0f, 0f, 0f)
    newLineAtOffset(x, baseline)
    showText(text)
    endText()
}

/**
 * Lays [Block]s out from the top of an A4 page down. A block that would go below the bottom margin starts the next
 * page, so nothing is cut off and no row is split. Closing the writer closes the stream of the last page.
 */
private class PageWriter(private val document: PDDocument) : AutoCloseable {
    private val pages = mutableListOf<PDPage>()
    private lateinit var content: PDPageContentStream
    private var open = false
    private var y = TOP

    init {
        newPage()
    }

    fun add(blocks: List<Block>) {
        var together = Block(emptyList())
        for (block in blocks) {
            together += block
            if (!block.keepWithNext) {
                place(together)
                together = Block(emptyList())
            }
        }
        place(together)
    }

    fun add(block: Block) = add(listOf(block))

    /** «Стр. N из M» at the foot of every page. M is known only now that all the pages are made, so they are appended to. */
    fun addPageNumbers(font: PDFont) {
        close()
        pages.forEachIndexed { index, page ->
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { footer ->
                val number = "Стр. ${index + 1} из ${pages.size}"
                footer.show(number, font, FOOTER_SIZE, (PAGE.width - width(number, font, FOOTER_SIZE)) / 2, FOOTER_BASELINE, grey = true)
            }
        }
    }

    override fun close() {
        if (open) content.close()
        open = false
    }

    private fun place(block: Block) {
        if (block.strips.isEmpty()) return
        if (block.height <= TOP - MARGIN) {
            if (y - block.height < MARGIN) newPage()
            block.strips.forEach(::paint)
        } else {
            // Taller than a page, which no page break could help: it goes on over the pages between two strips.
            block.strips.forEach {
                if (y - it.height < MARGIN) newPage()
                paint(it)
            }
        }
    }

    private fun paint(strip: Strip) {
        strip.paint(content, y)
        y -= strip.height
    }

    private fun newPage() {
        close()
        val page = PDPage(PAGE)
        document.addPage(page)
        pages += page
        content = PDPageContentStream(document, page)
        open = true
        y = TOP
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
