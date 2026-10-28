package io.github.laalka.slav.app.export

import io.github.laalka.slav.app.calculations.Breakdown
import io.github.laalka.slav.app.calculations.BreakdownLine
import io.github.laalka.slav.core.Money
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

// Breakdown columns: part, line, mass of the part, manual extra, its comment, amount.
private const val PART = 0
private const val LINE = 1
private const val MASS = 2
private const val EXTRA = 3
private const val COMMENT = 4
private const val AMOUNT = 5

// Widths in characters.
private val COLUMN_WIDTHS = listOf(28, 52, 14, 18, 36, 18)

/** The labels of the summary at the top of the sheet; the value is in the next column. */
object XlsxLabels {
    const val UNIT_PRICE = "Цена изделия"
    const val TOTAL = "Итог"
}

/**
 * The full breakdown of a calculation for the manager (spec 8.5): a summary, then every line of
 * every part and of the product. Amounts, quantities and masses are numeric cells, so the sheet
 * can be summed and checked; the currency label of the settings is in the headers.
 */
fun writeBreakdownXlsx(breakdown: Breakdown, currency: String, target: Path) {
    XSSFWorkbook().use { workbook ->
        val styles = Styles(workbook)
        val sheet = workbook.createSheet("Расчёт")
        COLUMN_WIDTHS.forEachIndexed { index, chars -> sheet.setColumnWidth(index, chars * 256) }
        val rows = Rows(sheet)

        rows.next().text(PART, breakdown.name.ifBlank { "Без названия" }, styles.title)
        rows.next().apply {
            text(PART, "Статус")
            text(LINE, breakdown.status.title)
        }
        rows.next().apply {
            text(PART, "Дата снимка цен")
            createCell(LINE).apply {
                setCellValue(breakdown.snapshotDate)
                cellStyle = styles.date
            }
        }
        rows.next().apply {
            text(PART, "Тираж, шт")
            number(LINE, breakdown.quantity.toBigDecimal(), styles.count)
        }
        rows.next().apply {
            text(PART, "${XlsxLabels.UNIT_PRICE}, $currency")
            money(LINE, breakdown.unitPrice, styles.money)
        }
        rows.next().apply {
            text(PART, "${XlsxLabels.TOTAL}, $currency", styles.bold)
            money(LINE, breakdown.total, styles.boldMoney)
        }
        rows.next().apply {
            text(PART, "Масса изделия, кг")
            number(LINE, breakdown.unitMassKg, styles.mass)
        }
        rows.next().apply {
            text(PART, "Масса тиража, кг")
            number(LINE, breakdown.runMassKg, styles.mass)
        }

        rows.skip()
        rows.next().apply {
            listOf("Деталь", "Строка", "Масса, кг", "Доп. операции, $currency", "Комментарий к доп. операциям", "Сумма, $currency")
                .forEachIndexed { column, header -> text(column, header, styles.header) }
        }
        breakdown.parts.forEach { part ->
            part.lines.forEach { rows.line(part.name, it, styles) }
            rows.next().apply {
                text(PART, part.name, styles.bold)
                text(LINE, "Цена детали", styles.bold)
                number(MASS, part.massKg, styles.mass)
                money(AMOUNT, part.cost, styles.boldMoney)
            }
        }
        breakdown.productLines.forEach { rows.line("Изделие", it, styles) }
        rows.next().apply {
            text(PART, "Изделие", styles.total)
            text(LINE, XlsxLabels.UNIT_PRICE, styles.total)
            number(MASS, breakdown.unitMassKg, styles.totalMass)
            text(EXTRA, "", styles.total)
            text(COMMENT, "", styles.total)
            money(AMOUNT, breakdown.unitPrice, styles.totalMoney)
        }

        Files.newOutputStream(target).use(workbook::write)
    }
}

private class Rows(private val sheet: XSSFSheet) {
    private var index = 0

    fun next(): Row = sheet.createRow(index++)

    fun skip() {
        index++
    }

    fun line(part: String, line: BreakdownLine, styles: Styles) {
        next().apply {
            text(PART, part)
            text(LINE, line.title)
            line.extra?.let { extra ->
                money(EXTRA, extra.amount, styles.money)
                text(COMMENT, extra.comment)
            }
            money(AMOUNT, line.cost, styles.money)
        }
    }
}

private fun Row.text(column: Int, value: String, style: CellStyle? = null) {
    createCell(column).apply {
        setCellValue(value)
        if (style != null) cellStyle = style
    }
}

private fun Row.number(column: Int, value: BigDecimal, style: CellStyle) {
    createCell(column).apply {
        setCellValue(value.toDouble())
        cellStyle = style
    }
}

private fun Row.money(column: Int, value: Money, style: CellStyle) = number(column, value.amount, style)

private class Styles(private val workbook: XSSFWorkbook) {
    private val format = workbook.createDataFormat()
    private val boldFont = workbook.createFont().apply { bold = true }

    val title: CellStyle = workbook.createCellStyle().apply {
        setFont(workbook.createFont().apply { bold = true; fontHeightInPoints = 14 })
    }
    val bold: CellStyle = workbook.createCellStyle().apply { setFont(boldFont) }
    val header: CellStyle = workbook.createCellStyle().apply {
        setFont(boldFont)
        wrapText = true
        borderBottom = BorderStyle.THIN
    }

    // Excel shows these with the separators of the user's locale: «11 280,00» on a Russian system.
    val money: CellStyle = numeric(MONEY_FORMAT)
    val boldMoney: CellStyle = numeric(MONEY_FORMAT).apply { setFont(boldFont) }
    val count: CellStyle = numeric("0")
    val mass: CellStyle = numeric(MASS_FORMAT)
    val date: CellStyle = numeric("dd.mm.yyyy")

    val total: CellStyle = workbook.createCellStyle().apply {
        setFont(boldFont)
        borderTop = BorderStyle.THIN
    }
    val totalMoney: CellStyle = numeric(MONEY_FORMAT).apply {
        setFont(boldFont)
        borderTop = BorderStyle.THIN
    }
    val totalMass: CellStyle = numeric(MASS_FORMAT).apply {
        setFont(boldFont)
        borderTop = BorderStyle.THIN
    }

    private fun numeric(pattern: String): CellStyle = workbook.createCellStyle().apply { dataFormat = format.getFormat(pattern) }

    private companion object {
        const val MONEY_FORMAT = "#,##0.00"
        const val MASS_FORMAT = "#,##0.0##"
    }
}
