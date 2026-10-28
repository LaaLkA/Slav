package io.github.laalka.slav.app.export

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.Breakdown
import io.github.laalka.slav.app.calculations.breakdown
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.data.AppPaths
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The columns of the breakdown sheet: part, line, mass of the part, manual extra, its comment, amount.
private const val MASS = 2
private const val EXTRA = 3
private const val COMMENT = 4
private const val AMOUNT = 5

class ExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() = deps.close()

    private fun golden(name: String = "Кронштейн", quantity: Int = 10): Breakdown {
        val id = deps.saveCalculated(goldenInput(name, quantity))
        return assertNotNull(assertNotNull(deps.calculations.get(id)).breakdown(ZoneId.systemDefault()))
    }

    private fun pdfText(file: Path): String = Loader.loadPDF(file.toFile()).use { PDFTextStripper().getText(it) }

    @Test
    fun `the client PDF holds the Cyrillic name, the date, the run size, the unit price and the total`() {
        val target = temp.root.toPath().resolve("client.pdf")

        ExportFormat.PDF.write(golden(), "₽", target)

        val text = pdfText(target)
        assertTrue("Кронштейн" in text, text)
        assertTrue("Дата: ${formatDate(LocalDate.now())}" in text, text)
        assertTrue("Тираж 10 шт" in text, text)
        assertTrue("Цена изделия 11 340,00 ₽" in text, text)
        assertTrue("Итог 113 400,00 ₽" in text, text)
        // For the client: no breakdown lines.
        assertFalse("Гибка" in text, text)
    }

    @Test
    fun `a long name wraps and characters the font lacks do not break the PDF`() {
        val name = "Кронштейн опорный усиленный для монтажа вентиляционного оборудования на кровле здания 🙂"
        val target = temp.root.toPath().resolve("long.pdf")

        ExportFormat.PDF.write(golden(name), "₽", target)

        val lines = pdfText(target).lines()
        val nameLines = lines.subList(lines.indexOf("Расчёт стоимости") + 1, lines.indexOfFirst { it.startsWith("Дата:") })
        assertTrue(nameLines.size > 1, "the name wraps:\n$lines")
        assertEquals(name.dropLast(2) + "?", nameLines.joinToString(" "), "every word is kept, the emoji becomes «?»")
    }

    @Test
    fun `the xlsx breakdown keeps money as numbers, the golden unit price is numeric 11340,00`() {
        val target = temp.root.toPath().resolve("breakdown.xlsx")

        ExportFormat.XLSX.write(golden(), "₽", target)

        XSSFWorkbook(target.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val unitPrice = sheet.rowLabelled("Цена изделия, ₽").getCell(1)
            assertEquals(CellType.NUMERIC, unitPrice.cellType)
            assertEquals(11340.00, unitPrice.numericCellValue)
            assertEquals(113400.00, sheet.rowLabelled("Итог, ₽").getCell(1).numericCellValue)
            assertEquals(10.0, sheet.rowLabelled("Тираж, шт").getCell(1).numericCellValue)
            assertEquals(LocalDate.now(), sheet.rowLabelled("Дата снимка цен").getCell(1).localDateTimeCellValue.toLocalDate())

            // The breakdown: each line's amount is a number, and they add up to the unit price in the last row.
            val lines = sheet.filter { it.cell(1) in listOf("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", "Лазер: рез листа", "Гибка", "Сварка сборки (полуавтомат)") }
            assertEquals(listOf(3200.0, 8000.0, 100.0, 40.0), lines.map { it.getCell(AMOUNT).numericCellValue })
            val last = sheet.getRow(sheet.lastRowNum)
            assertEquals("Цена изделия", last.cell(1))
            assertEquals(CellType.NUMERIC, last.getCell(AMOUNT).cellType)
            assertEquals(11340.00, last.getCell(AMOUNT).numericCellValue)
            assertEquals(16.0, last.getCell(MASS).numericCellValue, 1e-9)
        }
    }

    @Test
    fun `the xlsx has no quantity-per-product column or row`() {
        val target = temp.root.toPath().resolve("columns.xlsx")

        ExportFormat.XLSX.write(golden(), "₽", target)

        XSSFWorkbook(target.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val header = sheet.first { it.cell(0) == "Деталь" }
            assertEquals(
                listOf("Деталь", "Строка", "Масса, кг", "Доп. операции, ₽", "Комментарий к доп. операциям", "Сумма, ₽"),
                (0 until header.lastCellNum).map { header.cell(it) },
            )
            val text = sheet.flatMap { row -> (0 until row.lastCellNum.coerceAtLeast(0)).map { row.cell(it) } }
            assertFalse(text.any { "на изделие" in it || "1 шт" in it }, text.filter { it.isNotEmpty() }.toString())
            // "Цена детали" is the only row of a part's total, with its mass.
            val partTotal = sheet.first { it.cell(1) == "Цена детали" }
            assertEquals(16.0, partTotal.getCell(MASS).numericCellValue, 1e-9)
            assertEquals(11300.0, partTotal.getCell(AMOUNT).numericCellValue)
        }
    }

    @Test
    fun `the mass from the drawing is the mass of the part in the xlsx and the unit mass`() {
        val golden = goldenInput(quantity = 10)
        val input = golden.copy(parts = golden.parts.map { it.copy(massOverrideKg = BigDecimal("12.5")) })
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(deps.saveCalculated(input))).breakdown(ZoneId.systemDefault()))
        val target = temp.root.toPath().resolve("override.xlsx")

        ExportFormat.XLSX.write(breakdown, "₽", target)

        XSSFWorkbook(target.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertEquals(12.5, sheet.first { it.cell(1) == "Цена детали" }.getCell(MASS).numericCellValue, 1e-9)
            assertEquals(12.5, sheet.rowLabelled("Масса изделия, кг").getCell(1).numericCellValue, 1e-9)
            assertEquals(125.0, sheet.rowLabelled("Масса тиража, кг").getCell(1).numericCellValue, 1e-9)
            // 12.5 kg × 200 ₽/kg: the material line is priced by the mass from the drawing.
            assertEquals(2500.0, sheet.first { it.cell(1).startsWith("Материал:") }.getCell(AMOUNT).numericCellValue)
        }
    }

    @Test
    fun `manual extras land in their own numeric column with the comment`() {
        val golden = goldenInput(quantity = 1)
        val input = golden.copy(parts = golden.parts.map { it.copy(materialExtra = ManualExtra(Money(BigDecimal("500")), "срочно")) })
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(deps.saveCalculated(input))).breakdown(ZoneId.systemDefault()))
        val target = temp.root.toPath().resolve("extras.xlsx")

        ExportFormat.XLSX.write(breakdown, "руб.", target)

        XSSFWorkbook(target.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val material = sheet.first { it.cell(1).startsWith("Материал:") }
            assertEquals(500.0, material.getCell(EXTRA).numericCellValue)
            assertEquals("срочно", material.cell(COMMENT))
            assertEquals(3700.0, material.getCell(AMOUNT).numericCellValue)
            assertEquals(11840.0, sheet.rowLabelled("Цена изделия, руб.").getCell(1).numericCellValue)
        }
    }

    @Test
    fun `the suggested file name drops characters file systems refuse`() {
        assertEquals("Кронштейн 1_2 _нов_.pdf", exportFileName("Кронштейн 1/2 \"нов\"", ExportFormat.PDF))
        assertEquals("Расчёт.xlsx", exportFileName("  ", ExportFormat.XLSX))
        assertEquals("Рама.xlsx", exportFileName("Рама.", ExportFormat.XLSX))
    }

    private fun Sheet.rowLabelled(label: String): Row = first { it.cell(0) == label }

    private fun Row.cell(column: Int): String = getCell(column)?.takeIf { it.cellType == CellType.STRING }?.stringCellValue.orEmpty()
}
