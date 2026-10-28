package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.ADMIN_PASSWORD
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.CalculationListModel
import io.github.laalka.slav.app.calculations.CardLine
import io.github.laalka.slav.app.calculations.CardModel
import io.github.laalka.slav.app.calculations.QUICK_CUT_TITLE
import io.github.laalka.slav.app.calculations.QUICK_MATERIAL_TITLE
import io.github.laalka.slav.app.calculations.breakdown
import io.github.laalka.slav.app.calculations.quickGoldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.calculations.saveQuickCalculated
import io.github.laalka.slav.app.calculations.updateSettings
import io.github.laalka.slav.app.export.ExportFormat
import io.github.laalka.slav.app.export.exportFileName
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The simplified calculation after «Рассчитать»: its card, the list, and the two exports. It follows
 * every rule of a full one: status by hand, a new run size and a recalculation as new versions, deleting
 * with the administrator password, drawings.
 */
class QuickCardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
    }

    @AfterTest
    fun close() = deps.close()

    private val revisions = mutableListOf<UUID>()
    private var deleted = 0

    private fun card(id: UUID) = CardModel(
        deps.calculations, deps.settings, deps.adminPassword, deps.attachments, deps.fileOpener, id,
        onRevision = { revisions += it },
        onDeleted = { deleted++ },
    )

    private fun stored(id: UUID): StoredCalculation.Quick = assertIs<StoredCalculation.Quick>(deps.calculations.get(id))

    private fun list() = CalculationListModel(deps.calculations, deps.settings, deps.adminPassword)

    @Test
    fun `the card shows the material and the cut, the unit price, the total and the mass of the golden case`() {
        val id = deps.saveQuickCalculated(quickGoldenInput(quantity = 10))

        val state = card(id).state.value

        assertEquals("Лист на раскрой", state.name)
        assertEquals("5 400,00 ₽", state.unitPrice)
        assertEquals("54 000,00 ₽", state.total)
        assertEquals("24 кг", state.unitMass)
        assertEquals("240 кг", state.runMass)
        assertEquals("10", state.quantity)
        assertEquals(formatDate(LocalDate.now()), state.snapshotDate)
        assertEquals(emptyList(), state.parts, "a simplified calculation has no parts")
        assertEquals(
            listOf(CardLine(QUICK_MATERIAL_TITLE, null, "4 800,00 ₽"), CardLine(QUICK_CUT_TITLE, null, "600,00 ₽")),
            state.productLines,
        )
        assertEquals("Материал", QUICK_MATERIAL_TITLE)
        assertEquals("Резка (время)", QUICK_CUT_TITLE)
        assertEquals(CalculationStatus.MANUAL.toList().sortedBy { it.ordinal }, state.statusOptions)
        assertTrue(state.isPriced)
    }

    @Test
    fun `manual extras with their comments are part of the lines`() {
        val input = quickGoldenInput().copy(
            materialExtra = ManualExtra(Money(BigDecimal("200.50")), "Доставка листа"),
            cutExtra = ManualExtra(Money(BigDecimal("150")), ""),
        )

        val state = card(deps.saveQuickCalculated(input)).state.value

        assertEquals(
            listOf(
                CardLine("Материал", "в т. ч. доп. операции 200,50 ₽ — Доставка листа", "5 000,50 ₽"),
                CardLine("Резка (время)", "в т. ч. доп. операции 150,00 ₽", "750,00 ₽"),
            ),
            state.productLines,
        )
        assertEquals("5 750,50 ₽", state.unitPrice)
    }

    @Test
    fun `the status is set by hand and nothing else changes`() {
        val id = deps.saveQuickCalculated()
        val card = card(id)
        val before = stored(id)

        card.setStatus(CalculationStatus.IN_WORK)

        assertEquals(CalculationStatus.IN_WORK, card.state.value.status)
        assertEquals(before.copy(status = CalculationStatus.IN_WORK, updatedAt = stored(id).updatedAt), stored(id))
    }

    @Test
    fun `a new run size is a new version with the same snapshot and result, and the original keeps its size`() {
        val id = deps.saveQuickCalculated(quickGoldenInput(quantity = 1))
        val original = stored(id)
        val card = card(id)

        card.requestQuantityChange()
        card.onQuantityChange("10")
        card.confirmQuantityChange()

        val revision = stored(revisions.single())
        assertEquals(10, revision.quantity)
        assertEquals(original.result, revision.result)
        assertEquals(original.snapshot, revision.snapshot)
        assertEquals(original.snapshotAt, revision.snapshotAt)
        assertEquals(id, revision.sourceId)
        assertEquals("54 000,00 ₽", card(revision.id).state.value.total)
        assertEquals(original, stored(id), "the original is as it was")
        assertEquals(setOf(CalculationKind.QUICK), deps.calculations.list().map { it.kind }.toSet())
        card.reload()
        assertEquals(listOf(revision.id), card.state.value.revisions.map { it.id })
    }

    @Test
    fun `a recalculation on current prices previews the change and makes a new version on confirmation`() {
        val id = deps.saveQuickCalculated(quickGoldenInput(quantity = 10))
        val card = card(id)
        deps.updateSettings { it.copy(laserCutPerMinute = BigDecimal("80")) }

        card.prepareRecalc()

        val preview = assertNotNull(card.state.value.recalc)
        assertEquals("5 400,00 ₽", preview.oldUnitPrice)
        // 4 800 for the material and 12 min × 80 ₽/min for the cut.
        assertEquals("5 760,00 ₽", preview.newUnitPrice)
        assertEquals("54 000,00 ₽", preview.oldTotal)
        assertEquals("57 600,00 ₽", preview.newTotal)
        assertFalse(preview.unchanged)
        assertEquals(1, deps.calculations.list().size, "a preview creates nothing")

        card.confirmRecalc()

        val revision = stored(revisions.single())
        assertEquals("5760.00", revision.unitPrice?.amount?.toPlainString())
        assertEquals(deps.settings.load(), revision.snapshot)
        assertEquals("5400.00", stored(id).unitPrice?.amount?.toPlainString())
        assertNull(card.state.value.recalc)
    }

    @Test
    fun `a recalculation with no laser rate lists the missing price and changes nothing`() {
        val id = deps.saveQuickCalculated()
        val card = card(id)
        deps.updateSettings { it.copy(laserCutPerMinute = null) }

        card.prepareRecalc()

        assertNull(card.state.value.recalc)
        assertEquals(listOf("Не задана цена: Лазер, ₽/мин"), card.state.value.recalcErrors.map { it.message })
        assertEquals(1, deps.calculations.list().size)
        assertEquals(emptyList(), revisions)

        card.dismissRecalcErrors()
        assertEquals(emptyList(), card.state.value.recalcErrors)
    }

    @Test
    fun `Изменить makes a quick revision draft, and a draft is edited as it is`() {
        val id = deps.saveQuickCalculated()
        val card = card(id)

        val draft = card.edit()

        assertNotEquals(id, draft)
        val stored = stored(draft)
        assertEquals(CalculationStatus.DRAFT, stored.status)
        assertEquals(id, stored.sourceId)
        assertEquals(stored(id).snapshot, stored.snapshot)
        assertEquals(draft, card(draft).edit())
    }

    @Test
    fun `deleting asks for the administrator password, a wrong one deletes nothing`() {
        val id = deps.saveQuickCalculated()
        val card = card(id)

        card.requestDelete()
        card.onDeletePasswordChange("not-the-password")
        card.confirmDelete()
        assertEquals("Неверный пароль", card.state.value.deleteQuestion?.error)
        assertNotNull(deps.calculations.get(id))
        assertEquals(0, deleted)

        card.onDeletePasswordChange(ADMIN_PASSWORD)
        card.confirmDelete()
        assertNull(deps.calculations.get(id))
        assertEquals(1, deleted)
    }

    @Test
    fun `a drawing is attached to the card, and removing it from a calculated record needs the password`() {
        val id = deps.saveQuickCalculated()
        val card = card(id)
        val file = temp.newFile("plan.pdf").toPath().also { it.writeBytes(byteArrayOf(1, 2, 3)) }

        card.attachments.attach(file)

        assertEquals(listOf("plan.pdf"), card.attachments.state.value.rows.map { it.fileName })
        val row = card.attachments.state.value.rows.single()
        card.attachments.requestRemove(row.id)
        assertNotNull(card.attachments.state.value.removeQuestion)
        assertEquals(1, deps.attachments.list(id).size, "not removed before the password")
        card.attachments.onRemovePasswordChange(ADMIN_PASSWORD)
        card.attachments.confirmRemove()
        assertEquals(emptyList(), deps.attachments.list(id))
    }

    @Test
    fun `the list marks a simplified calculation, and search, status and sort cover both kinds`() {
        val full = deps.saveCalculated()
        val quick = deps.saveQuickCalculated(quickGoldenInput("Лист опорный", quantity = 10))
        val draft = deps.calculations.copy(quick)
        val model = list()

        val rows = model.state.value.rows
        assertEquals(
            mapOf(full to CalculationKind.FULL, quick to CalculationKind.QUICK, draft to CalculationKind.QUICK),
            rows.associate { it.id to it.kind },
        )
        val row = rows.single { it.id == quick }
        assertEquals("5 400,00 ₽", row.unitPrice)
        assertEquals("54 000,00 ₽", row.total)

        model.onQueryChange("опорн")
        assertEquals(setOf(quick, draft), model.state.value.rows.map { it.id }.toSet())
        model.onQueryChange("")
        model.toggleStatus(CalculationStatus.DRAFT)
        assertEquals(listOf(draft), model.state.value.rows.map { it.id })
    }

    @Test
    fun `a simplified calculation is copied as a draft of its own kind`() {
        val quick = deps.saveQuickCalculated(quickGoldenInput(quantity = 3))
        val model = list()

        val copy = model.copy(quick)

        assertEquals(CalculationKind.QUICK, deps.calculations.list().single { it.id == copy }.kind)
        assertEquals(quickGoldenInput(quantity = 3), stored(copy).input)
        assertNull(stored(copy).snapshot)
    }

    @Test
    fun `the client PDF of a simplified calculation holds the name, the unit price and the total, and no lines`() {
        val id = deps.saveQuickCalculated(quickGoldenInput("Лист на раскрой", quantity = 10))
        val target = temp.root.toPath().resolve("client.pdf")
        val model = list()

        model.export(id, ExportFormat.PDF, target)

        assertNull(model.state.value.message?.takeIf { it.isError })
        val text = Loader.loadPDF(target.toFile()).use { PDFTextStripper().getText(it) }
        assertTrue("Лист на раскрой" in text, text)
        assertTrue("Тираж 10 шт" in text, text)
        assertTrue("Цена изделия 5 400,00 ₽" in text, text)
        assertTrue("Итог 54 000,00 ₽" in text, text)
        assertFalse("Резка" in text, text)
        assertEquals("Лист на раскрой.pdf", model.suggestedFileName(id, ExportFormat.PDF))
    }

    @Test
    fun `the xlsx of a simplified calculation holds the two lines as numbers and the totals`() {
        val input = quickGoldenInput(quantity = 10).copy(cutExtra = ManualExtra(Money(BigDecimal("150")), "Снятие фаски"))
        val id = deps.saveQuickCalculated(input)
        val breakdown = assertNotNull(deps.calculations.get(id)?.breakdown(ZoneId.systemDefault()))
        val target = temp.root.toPath().resolve("quick.xlsx")

        ExportFormat.XLSX.write(breakdown, "₽", target)

        XSSFWorkbook(target.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertEquals("Лист на раскрой", sheet.getRow(0).cell(0))
            assertEquals(5550.0, sheet.rowLabelled("Цена изделия, ₽").getCell(1).numericCellValue)
            assertEquals(55500.0, sheet.rowLabelled("Итог, ₽").getCell(1).numericCellValue)
            assertEquals(10.0, sheet.rowLabelled("Тираж, шт").getCell(1).numericCellValue)
            assertEquals(24.0, sheet.rowLabelled("Масса изделия, кг").getCell(1).numericCellValue, 1e-9)
            assertEquals(240.0, sheet.rowLabelled("Масса тиража, кг").getCell(1).numericCellValue, 1e-9)

            val material = sheet.first { it.cell(1) == "Материал" }
            val cut = sheet.first { it.cell(1) == "Резка (время)" }
            assertEquals(CellType.NUMERIC, material.getCell(5).cellType)
            assertEquals(4800.0, material.getCell(5).numericCellValue)
            assertEquals(750.0, cut.getCell(5).numericCellValue)
            assertEquals(150.0, cut.getCell(3).numericCellValue)
            assertEquals("Снятие фаски", cut.cell(4))
            val last = sheet.getRow(sheet.lastRowNum)
            assertEquals("Цена изделия", last.cell(1))
            assertEquals(5550.0, last.getCell(5).numericCellValue)
            assertEquals(24.0, last.getCell(2).numericCellValue, 1e-9)
            assertEquals(exportFileName("Лист на раскрой", ExportFormat.XLSX), "Лист на раскрой.xlsx")
        }
    }

    private fun Sheet.rowLabelled(label: String): Row = first { it.cell(0) == label }

    private fun Row.cell(column: Int): String = getCell(column)?.takeIf { it.cellType == CellType.STRING }?.stringCellValue.orEmpty()
}
