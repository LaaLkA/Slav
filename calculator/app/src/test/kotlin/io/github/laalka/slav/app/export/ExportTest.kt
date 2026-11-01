package io.github.laalka.slav.app.export

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.Breakdown
import io.github.laalka.slav.app.calculations.BreakdownLine
import io.github.laalka.slav.app.calculations.BreakdownPart
import io.github.laalka.slav.app.calculations.breakdown
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.quickGoldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.calculations.saveQuickCalculated
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.app.ui.formatMeasure
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
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

    /** The text rows of the PDF, one per line, without blank lines; the spaces of the formatter normalised. */
    private fun pdfLines(file: Path): List<String> =
        pdfText(file).lines().map { it.replace('\u00a0', ' ').replace('\u202f', ' ').trim() }.filter { it.isNotEmpty() }

    @Test
    fun `the client PDF holds the Cyrillic name, the date, the run size, the unit price and the run total`() {
        val target = temp.root.toPath().resolve("client.pdf")

        ExportFormat.PDF.write(golden("Рама сварная"), "₽", target)

        val text = pdfText(target)
        assertTrue("Рама сварная" in text, text)
        assertTrue("Дата: ${formatDate(LocalDate.now())}" in text, text)
        assertTrue("Статус: Рассчитан" in text, text)
        assertTrue("Тираж 10 шт" in text, text)
        assertTrue("Цена за единицу 11 340,00 ₽" in text, text)
        assertTrue("Итого за тираж 113 400,00 ₽" in text, text)
        assertTrue("Масса изделия 16 кг" in text, text)
        assertTrue("Масса тиража 160 кг" in text, text)
        // Nothing about a discount or a customer that is not there.
        assertFalse("Скидка" in text || "Сумма без скидки" in text, text)
        assertFalse("Контрагент" in text, text)
    }

    @Test
    fun `the client PDF holds the whole breakdown of the card, every line with its amount`() {
        val target = temp.root.toPath().resolve("breakdown.pdf")

        ExportFormat.PDF.write(golden("Рама сварная"), "₽", target)

        // The part is named «Кронштейн» and the calculation «Рама сварная», so the part name below is the part's own.
        assertEquals(
            listOf(
                "Разбивка",
                "Кронштейн",
                "Масса: 16 кг",
                "Материал: Лист Ст3, 2 мм, 1000 × 1000 мм 3 200,00 ₽",
                "Лазер: рез листа 8 000,00 ₽",
                "Гибка 100,00 ₽",
                "Цена детали 11 300,00 ₽",
                "Операции изделия",
                "Сварка сборки (полуавтомат) 40,00 ₽",
                // Without a discount the sum of the lines is the unit price: it is written once.
                "Цена за единицу 11 340,00 ₽",
                "Стр. 1 из 1",
            ),
            pdfLines(target).dropWhile { it != "Разбивка" },
        )
    }

    /** The amounts of the breakdown lines of [lines], the text of a PDF: not the sums of the parts, the notes or the closing rows. */
    private fun breakdownAmounts(lines: List<String>): List<BigDecimal> {
        assertTrue("Разбивка" in lines, lines.toString())
        val section = lines.dropWhile { it != "Разбивка" }.drop(1).takeWhile { !it.startsWith("Сумма без скидки") && !it.startsWith("Цена за единицу") }
        val amount = Regex("""(\d[\d ]*,\d{2}) ₽$""")
        return section
            .filterNot { it.startsWith("Цена детали") || it.startsWith("в т. ч.") }
            .mapNotNull { amount.find(it)?.groupValues?.get(1) }
            .map { BigDecimal(it.replace(" ", "").replace(',', '.')) }
    }

    private fun assertLinesAddUp(lines: List<String>, breakdown: Breakdown) {
        val amounts = breakdownAmounts(lines)
        val lineCount = breakdown.parts.sumOf { it.lines.size } + breakdown.productLines.size
        assertEquals(lineCount, amounts.size, "every line of the breakdown has its amount in the PDF:\n$lines")
        assertEquals(0, amounts.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(breakdown.subtotal.amount), "the amounts of the lines add up to ${breakdown.subtotal}: $amounts")
    }

    @Test
    fun `the amounts of the breakdown lines in the PDF add up to the sum before the discount`() {
        val input = goldenInput(quantity = 10).copy(discountPercent = BigDecimal("10"))
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(deps.saveCalculated(input))).breakdown(ZoneId.systemDefault()))
        val target = temp.root.toPath().resolve("sum.pdf")

        ExportFormat.PDF.write(breakdown, "₽", target)

        assertLinesAddUp(pdfLines(target), breakdown)
        assertEquals(0, breakdown.subtotal.compareTo(Money(BigDecimal("11340"))), "the sum before the 10 % discount is 11 340,00")
    }

    @Test
    fun `a manual extra is written under its line with its comment, and with no dash when there is no comment`() {
        val golden = goldenInput(quantity = 1)
        val input = golden.copy(
            parts = golden.parts.map { part ->
                part.copy(
                    materialExtra = ManualExtra(Money(BigDecimal("500")), "срочно"),
                    operations = part.operations.map { if (it is PartOperation.Bending) it.copy(manualExtra = ManualExtra(Money(BigDecimal("100")), "  ")) else it },
                )
            },
        )
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(deps.saveCalculated(input))).breakdown(ZoneId.systemDefault()))
        val target = temp.root.toPath().resolve("extras.pdf")

        ExportFormat.PDF.write(breakdown, "₽", target)

        val lines = pdfLines(target)
        val rows = lines.windowed(2)
        assertTrue(listOf("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм 3 700,00 ₽", "в т. ч. доп. операции 500,00 ₽ — срочно") in rows, lines.toString())
        assertTrue(listOf("Гибка 200,00 ₽", "в т. ч. доп. операции 100,00 ₽") in rows, "a blank comment leaves no dash:\n$lines")
        assertLinesAddUp(lines, breakdown)
    }

    @Test
    fun `the PDF of a simplified calculation holds its two lines and the mass, with no run size, no total and no caption of the product`() {
        val id = deps.saveQuickCalculated(quickGoldenInput("Лист на раскрой", quantity = 10))
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(id)).breakdown(ZoneId.systemDefault()))
        val target = temp.root.toPath().resolve("quick.pdf")

        ExportFormat.PDF.write(breakdown, "₽", target)

        val lines = pdfLines(target)
        assertTrue(lines.none { "ираж" in it }, "no «Тираж», «Итого за тираж», «Масса тиража»:\n$lines")
        assertTrue("Операции изделия" !in lines, lines.toString())
        assertEquals(
            listOf(
                "Разбивка",
                "Материал 4 800,00 ₽",
                "Резка (время) 600,00 ₽",
                "Цена за единицу 5 400,00 ₽",
                "Стр. 1 из 1",
            ),
            lines.dropWhile { it != "Разбивка" },
        )
        assertTrue("Масса изделия ${formatMeasure(breakdown.unitMassKg)} кг" in lines, lines.toString())
        assertTrue("Статус: Рассчитан" in lines, lines.toString())
        assertLinesAddUp(lines, breakdown)
    }

    @Test
    fun `the archive PDF of a stored calculation holds the same breakdown`() {
        val id = deps.saveCalculated(goldenInput("Рама сварная", quantity = 10))
        val stored = assertNotNull(deps.calculations.get(id))
        val bytes = assertNotNull(clientPdfBytes(stored, "₽", ZoneId.systemDefault()))

        val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

        assertTrue("Цена детали 11 300,00 ₽" in text, text)
        assertTrue("Сварка сборки (полуавтомат) 40,00 ₽" in text, text)
        assertTrue("Стр. 1 из 1" in text, text)
    }

    /** [count] parts of four lines each, one of them with the [longTitle]; the sums are the sums of the lines. */
    private fun bigBreakdown(count: Int, longTitle: String): Breakdown {
        val parts = (1..count).map { n ->
            val lines = listOf(
                BreakdownLine("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", null, Money(BigDecimal("3200.00"))),
                BreakdownLine(if (n == 7) longTitle else "Лазер: рез листа", null, Money(BigDecimal("800.50"))),
                BreakdownLine("Гибка", ManualExtra(Money(BigDecimal("20")), "по месту").takeIf { n % 5 == 0 }, Money(BigDecimal("100.00"))),
                BreakdownLine("Сварка", null, Money(BigDecimal("40.25"))),
            )
            BreakdownPart("Деталь №%02d".format(n), BigDecimal("1.5"), lines, Money(lines.sumOf { it.cost.amount }))
        }
        val subtotal = Money(parts.sumOf { it.cost.amount })
        return Breakdown(
            name = "Большое изделие",
            counterparty = "",
            kind = CalculationKind.FULL,
            status = CalculationStatus.CALCULATED,
            quantity = 5,
            snapshotDate = LocalDate.of(2026, 10, 30),
            parts = parts,
            productLines = emptyList(),
            subtotal = subtotal,
            discountPercent = null,
            discount = Money.ZERO,
            unitPrice = subtotal,
            unitMassKg = BigDecimal("60"),
        )
    }

    @Test
    fun `a product of 40 parts goes on over several A4 pages, numbered, with every line whole and inside the margins`() {
        val longTitle = "Лазерная резка контура с припуском под гибку и зачисткой кромок после реза, с проверкой размеров по чертежу перед отправкой на гибку и сварку " +
            "в сборе с соседними деталями изделия"
        assertTrue(longTitle.length > 150 && "  " !in longTitle)
        val breakdown = bigBreakdown(40, longTitle)
        val target = temp.root.toPath().resolve("big.pdf")

        ExportFormat.PDF.write(breakdown, "₽", target)

        val pages = Loader.loadPDF(target.toFile()).use { it.numberOfPages }
        assertTrue(pages > 1, "40 parts do not fit one page")
        val lines = pdfLines(target)
        (1..40).forEach { n -> assertTrue("Деталь №%02d".format(n) in lines, "part $n is in the PDF") }
        (1..pages).forEach { page -> assertTrue("Стр. $page из $pages" in lines, "page $page has its number:\n$lines") }
        assertEquals(pages, lines.count { it.startsWith("Стр. ") }, "one number per page")
        assertTrue("Стр. 1 из $pages" in lines && "Стр. $pages из $pages" in lines)

        // The long title is wrapped inside the label column, its amount on the first line, and every word of it is kept.
        val start = lines.indexOfFirst { it.startsWith(longTitle.take(24)) }
        assertTrue(start >= 0, "the long title is in the PDF")
        val wrapped = mutableListOf(lines[start].removeSuffix(" 800,50 ₽"))
        assertTrue(lines[start].endsWith(" 800,50 ₽"), "the amount is on the first line of the label: ${lines[start]}")
        while (wrapped.joinToString(" ").length < longTitle.length) wrapped += lines[start + wrapped.size]
        assertTrue(wrapped.size > 1, "the title wraps")
        assertEquals(longTitle, wrapped.joinToString(" "))

        assertLinesAddUp(lines, breakdown)
        assertEquals(40, lines.count { it.startsWith("Цена детали ") })

        assertPagesAreTidy(target)
    }

    /**
     * The rows of text of every page but the page numbers lie at least 50 pt from every edge, every page has its number
     * in the bottom margin, and no two pieces of text on one row touch: a wrapped title stays clear of its amount.
     */
    private fun assertPagesAreTidy(file: Path) {
        val edge = 50f
        class Run(val page: Int, val text: String, val positions: List<TextPosition>)
        val runs = mutableListOf<Run>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                runs += Run(currentPageNo, text, textPositions.toList())
                super.writeString(text, textPositions)
            }
        }
        Loader.loadPDF(file.toFile()).use { document ->
            stripper.getText(document)
            val box = document.getPage(0).mediaBox
            val (numbers, body) = runs.partition { it.text.startsWith("Стр. ") }
            assertTrue(body.isNotEmpty())
            body.forEach { run ->
                run.positions.forEach { position ->
                    val where = "«${run.text}» on page ${run.page}"
                    assertTrue(position.xDirAdj >= edge - 1f, "$where starts inside the left margin")
                    assertTrue(position.xDirAdj + position.widthDirAdj <= box.width - edge + 1f, "$where ends inside the right margin")
                    assertTrue(position.yDirAdj >= edge, "$where is below the top margin")
                    assertTrue(position.yDirAdj <= box.height - edge, "$where is above the bottom margin, at ${box.height - position.yDirAdj} pt from the bottom")
                }
            }
            numbers.forEach { run ->
                run.positions.forEach { position ->
                    assertTrue(position.yDirAdj <= box.height && position.yDirAdj > box.height - edge, "the number of page ${run.page} is in the bottom margin")
                }
            }
            body.groupBy { it.page to Math.round(it.positions.first().yDirAdj) }.values.filter { it.size > 1 }.forEach { row ->
                row.sortedBy { it.positions.first().xDirAdj }.zipWithNext().forEach { (left, right) ->
                    val end = left.positions.last().let { it.xDirAdj + it.widthDirAdj }
                    assertTrue(right.positions.first().xDirAdj - end >= 10f, "«${left.text}» and «${right.text}» on page ${left.page} do not touch")
                }
            }
        }
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
            val unitPrice = sheet.rowLabelled("Цена за единицу, ₽").getCell(1)
            assertEquals(CellType.NUMERIC, unitPrice.cellType)
            assertEquals(11340.00, unitPrice.numericCellValue)
            assertEquals(113400.00, sheet.rowLabelled("Итого за тираж, ₽").getCell(1).numericCellValue)
            assertEquals(10.0, sheet.rowLabelled("Тираж, шт").getCell(1).numericCellValue)
            assertEquals(LocalDate.now(), sheet.rowLabelled("Дата снимка цен").getCell(1).localDateTimeCellValue.toLocalDate())

            // The breakdown: each line's amount is a number, and they add up to the unit price in the last row.
            val lines = sheet.filter { it.cell(1) in listOf("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", "Лазер: рез листа", "Гибка", "Сварка сборки (полуавтомат)") }
            assertEquals(listOf(3200.0, 8000.0, 100.0, 40.0), lines.map { it.getCell(AMOUNT).numericCellValue })
            val last = sheet.getRow(sheet.lastRowNum)
            assertEquals("Цена за единицу", last.cell(1))
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
    fun `a sheet set by weight is exported with the same description as the card, in both formats`() {
        val golden = goldenInput(quantity = 10)
        val byMass = Material.Sheet("Ст3", BigDecimal("2"), SheetShape.ByMass(BigDecimal("16"), BigDecimal("1200")))
        val input = golden.copy(
            parts = golden.parts.map { it.copy(material = byMass, operations = listOf(PartOperation.LaserSheet(SheetCutMode.ByShape))) },
            productOperations = emptyList(),
        )
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(deps.saveCalculated(input))).breakdown(ZoneId.systemDefault()))
        val description = "Материал: Лист Ст3, 2 мм, по весу 16 кг, периметр 1 200 мм"
        assertEquals(description, breakdown.parts.single().lines.first().title)

        val xlsx = temp.root.toPath().resolve("weight.xlsx")
        ExportFormat.XLSX.write(breakdown, "₽", xlsx)
        XSSFWorkbook(xlsx.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val material = sheet.first { it.cell(1).startsWith("Материал:") }
            assertEquals(description, material.cell(1))
            // 16 kg × 200 ₽/kg; the laser by shape: 1200 mm = 120 cm × 20 ₽/cm = 2400.00, 1 pierce × 0.
            assertEquals(3200.0, material.getCell(AMOUNT).numericCellValue)
            assertEquals(2400.0, sheet.first { it.cell(1) == "Лазер: рез листа" }.getCell(AMOUNT).numericCellValue)
        }

        val pdf = temp.root.toPath().resolve("weight.pdf")
        ExportFormat.PDF.write(breakdown, "₽", pdf)
        assertTrue("Цена за единицу 5 600,00 ₽" in pdfText(pdf), pdfText(pdf))
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
            assertEquals(11840.0, sheet.rowLabelled("Цена за единицу, руб.").getCell(1).numericCellValue)
        }
    }

    @Test
    fun `a 10 percent discount is in both formats as 11 340,00 less 1 134,00 is 10 206,00, with the counterparty`() {
        val input = goldenInput(quantity = 10).copy(counterparty = "ООО Ромашка", discountPercent = BigDecimal("10"))
        val breakdown = assertNotNull(assertNotNull(deps.calculations.get(deps.saveCalculated(input))).breakdown(ZoneId.systemDefault()))

        val pdf = temp.root.toPath().resolve("discount.pdf")
        ExportFormat.PDF.write(breakdown, "₽", pdf)
        val text = pdfText(pdf)
        assertTrue("Контрагент: ООО Ромашка" in text, text)
        assertTrue("Тираж 10 шт" in text, text)
        assertTrue("Сумма без скидки 11 340,00 ₽" in text, text)
        assertTrue("Скидка (10 %) −1 134,00 ₽" in text, text)
        assertTrue("Цена за единицу 10 206,00 ₽" in text, text)
        assertTrue("Итого за тираж 102 060,00 ₽" in text, text)
        // The breakdown ends with the three rows that take the discount off the sum of its lines.
        val lines = pdfLines(pdf)
        assertEquals(
            listOf("Сумма без скидки 11 340,00 ₽", "Скидка (10 %) −1 134,00 ₽", "Цена за единицу 10 206,00 ₽"),
            lines.dropWhile { it != "Разбивка" }.dropLast(1).takeLast(3),
        )
        assertEquals("Стр. 1 из 1", lines.last())
        assertLinesAddUp(lines, breakdown)

        val xlsx = temp.root.toPath().resolve("discount.xlsx")
        ExportFormat.XLSX.write(breakdown, "₽", xlsx)
        XSSFWorkbook(xlsx.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertEquals("ООО Ромашка", sheet.rowLabelled("Контрагент").cell(1))
            assertEquals(11340.0, sheet.rowLabelled("Сумма без скидки, ₽").getCell(1).numericCellValue)
            assertEquals(1134.0, sheet.rowLabelled("Скидка (10 %), ₽").getCell(1).numericCellValue)
            assertEquals(10206.0, sheet.rowLabelled("Цена за единицу, ₽").getCell(1).numericCellValue)
            assertEquals(102060.0, sheet.rowLabelled("Итого за тираж, ₽").getCell(1).numericCellValue)

            // The lines still add up to the sum before the discount; the three last rows take the discount off it.
            val lines = sheet.filter { it.cell(1) in listOf("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", "Лазер: рез листа", "Гибка", "Сварка сборки (полуавтомат)") }
            assertEquals(11340.0, lines.sumOf { it.getCell(AMOUNT).numericCellValue })
            val last = (sheet.lastRowNum - 2..sheet.lastRowNum).map { sheet.getRow(it) }
            assertEquals(listOf("Сумма без скидки", "Скидка (10 %)", "Цена за единицу"), last.map { it.cell(1) })
            assertEquals(listOf(11340.0, 1134.0, 10206.0), last.map { it.getCell(AMOUNT).numericCellValue })
        }
    }

    @Test
    fun `a calculation with no discount and no counterparty has neither in the xlsx`() {
        val target = temp.root.toPath().resolve("plain.xlsx")

        ExportFormat.XLSX.write(golden(), "₽", target)

        XSSFWorkbook(target.toFile()).use { workbook ->
            val labels = workbook.getSheetAt(0).map { it.cell(0) + "|" + it.cell(1) }
            assertFalse(labels.any { "Контрагент" in it || "скидк" in it.lowercase() }, labels.toString())
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
