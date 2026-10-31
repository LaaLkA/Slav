package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.ADMIN_PASSWORD
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.app.testScope
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.AttachResult
import io.github.laalka.slav.data.CalculationArchive
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
    }

    @AfterTest
    fun close() = deps.close()

    /** The ids the card handed over as new versions, and how many times it said the calculation was deleted. */
    private val revisions = mutableListOf<UUID>()
    private var deleted = 0

    /** The editors the card asked for: the id and whether it is edited in place. */
    private val opened = mutableListOf<Pair<UUID, Boolean>>()

    /** What the card said about the calculations it archived. */
    private val archived = mutableListOf<ArchiveOutcome.Sent>()

    private fun card(id: UUID, archive: CalculationArchive = deps.archive) = CardModel(
        deps.calculations,
        deps.settings,
        deps.adminPassword,
        deps.attachments,
        deps.fileOpener,
        testScope(),
        id,
        onRevision = { revisions += it },
        onDeleted = { deleted++ },
        onOpenEditor = { edited, inPlace -> opened += edited to inPlace },
        archive = archive,
        onArchived = { archived += it },
    )

    private fun stored(id: UUID): StoredCalculation.Full = assertIs<StoredCalculation.Full>(deps.calculations.get(id))

    private fun StoredCalculation.st3TwoMillimetrePrice(): BigDecimal? =
        snapshot?.materialPricesPerKg?.single { it.grade == "Ст3" && it.thicknessMm.compareTo(BigDecimal("2")) == 0 }?.pricePerKg

    @Test
    fun `the card shows the breakdown, the masses and the snapshot date of the golden case`() {
        val id = deps.saveCalculated(goldenInput(quantity = 10))

        val state = card(id).state.value

        assertEquals("11 340,00 ₽", state.unitPrice)
        assertEquals("113 400,00 ₽", state.total)
        assertEquals("16 кг", state.unitMass)
        assertEquals("160 кг", state.runMass)
        assertEquals("10", state.quantity)
        val part = state.parts.single()
        assertEquals(
            listOf(
                CardLine("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", null, "3 200,00 ₽"),
                CardLine("Лазер: рез листа", null, "8 000,00 ₽"),
                CardLine("Гибка", null, "100,00 ₽"),
            ),
            part.lines,
        )
        assertEquals("11 300,00 ₽", part.cost)
        assertEquals("Масса: 16 кг", part.details)
        assertEquals(listOf(CardLine("Сварка сборки (полуавтомат)", null, "40,00 ₽")), state.productLines)
        assertEquals(formatDate(LocalDate.now()), state.snapshotDate)
        assertEquals(CalculationStatus.MANUAL.toList().sortedBy { it.ordinal }, state.statusOptions)
    }

    @Test
    fun `manual extras with their comments and product lines are part of the breakdown`() {
        val golden = goldenInput()
        val input = golden.copy(
            parts = golden.parts.map { it.copy(materialExtra = ManualExtra(Money(BigDecimal("500")), "срочно")) },
            productOperations = listOf(ProductOperation.AssemblyWelding("полуавтомат", "Сталь", BigDecimal("2"), seamLengthMm = BigDecimal("100"))),
        )
        val state = card(deps.saveCalculated(input)).state.value

        val part = state.parts.single()
        assertEquals(CardLine("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", "в т. ч. доп. операции 500,00 ₽ — срочно", "3 700,00 ₽"), part.lines.first())
        assertEquals("11 800,00 ₽", part.cost)
        // 100 mm = 10 cm × 10 ₽/cm of semi-automatic welding, steel, 2 mm.
        assertEquals(listOf(CardLine("Сварка сборки (полуавтомат)", null, "100,00 ₽")), state.productLines)
        assertEquals("11 900,00 ₽", state.unitPrice)
    }

    @Test
    fun `every part is one piece, identical parts are listed one by one and add up`() {
        val golden = goldenInput(quantity = 2)
        val input = golden.copy(parts = golden.parts + golden.parts.map { it.copy(name = "Кронштейн (копия)") })

        val state = card(deps.saveCalculated(input)).state.value

        assertEquals(listOf("Кронштейн", "Кронштейн (копия)"), state.parts.map { it.title })
        assertEquals(listOf("11 300,00 ₽", "11 300,00 ₽"), state.parts.map { it.cost })
        // 2 × 11 300 + 40 of the assembly welding.
        assertEquals("22 640,00 ₽", state.unitPrice)
        assertEquals("32 кг", state.unitMass)
    }

    @Test
    fun `the mass from the drawing is the mass of the part, of the product and of the run`() {
        val golden = goldenInput(quantity = 10)
        val input = golden.copy(parts = golden.parts.map { it.copy(massOverrideKg = BigDecimal("12.5")) })

        val state = card(deps.saveCalculated(input)).state.value

        assertEquals("Масса: 12,5 кг", state.parts.single().details)
        assertEquals("12,5 кг", state.unitMass)
        assertEquals("125 кг", state.runMass)
        // 12.5 kg × 200 ₽/kg = 2500.00 instead of 3200.00.
        assertEquals("2 500,00 ₽", state.parts.single().lines.first().cost)
        assertEquals("10 640,00 ₽", state.unitPrice)
    }

    @Test
    fun `the material line names the shape of a sheet`() {
        val golden = goldenInput()
        var saved = 0
        // Names are unique, so every calculation saved here has one of its own.
        fun title(shape: SheetShape) = card(
            deps.saveCalculated(
                golden.copy(
                    name = "Кронштейн ${++saved}",
                    parts = golden.parts.map { it.copy(material = Material.Sheet("Ст3", BigDecimal("2"), shape), operations = emptyList()) },
                ),
            ),
        ).state.value.parts.single().lines.first().title

        assertEquals("Материал: Лист Ст3, 2 мм, круг R 50 мм", title(SheetShape.Circle(BigDecimal("50"))))
        assertEquals("Материал: Лист Ст3, 2 мм, кольцо R 50 / r 20 мм", title(SheetShape.Ring(BigDecimal("50"), BigDecimal("20"))))
        assertEquals(
            "Материал: Лист Ст3, 2 мм, произвольная форма, площадь 12000,5 мм², периметр 500 мм",
            title(SheetShape.Free(BigDecimal("12000.5"), BigDecimal("500"))),
        )
        assertEquals("Материал: Лист Ст3, 2 мм, по весу 12 кг", title(SheetShape.ByMass(BigDecimal("12"))))
        assertEquals(
            "Материал: Лист Ст3, 2 мм, по весу 12 кг, периметр 1 200 мм",
            title(SheetShape.ByMass(BigDecimal("12"), BigDecimal("1200"))),
        )
    }

    @Test
    fun `a new run size is a new version with the same prices and result, and the original keeps its total`() {
        val id = deps.saveCalculated()
        val before = stored(id)
        // The current prices change after the calculation: the new version must keep the snapshot's unit price.
        deps.setSt3TwoMillimetrePrice("300")
        val card = card(id)

        card.requestQuantityChange()
        assertEquals("1", assertNotNull(card.state.value.quantityDialog).text)
        card.onQuantityChange("10")
        card.confirmQuantityChange()

        val revision = revisions.single()
        assertNull(card.state.value.quantityDialog)
        val after = stored(revision)
        assertEquals(10, after.quantity)
        assertEquals(before.result, after.result)
        assertEquals(before.snapshot, after.snapshot)
        assertEquals(before.snapshotAt, after.snapshotAt)
        assertEquals(CalculationStatus.CALCULATED, after.status)
        assertEquals(id, after.sourceId)
        assertEquals("113 400,00 ₽", card(revision).state.value.total)
        assertEquals("11 340,00 ₽", card(revision).state.value.unitPrice)
        // The original still shows its own run size and total.
        assertEquals(before, stored(id))
        assertEquals("11 340,00 ₽", card(id).state.value.total)
        assertEquals("1", card(id).state.value.quantity)
        assertEquals(2, deps.calculations.list().size)
    }

    @Test
    fun `an invalid or unchanged run size is flagged and nothing is created`() {
        val id = deps.saveCalculated()
        val card = card(id)
        card.requestQuantityChange()

        card.onQuantityChange("0")
        assertEquals("Целое число больше нуля", card.state.value.quantityDialog?.error)
        card.onQuantityChange("")
        assertEquals("Заполните", card.state.value.quantityDialog?.error)
        card.onQuantityChange("1")
        assertEquals("Тираж не изменился", card.state.value.quantityDialog?.error)
        card.onQuantityChange("1,5")
        card.confirmQuantityChange()

        assertEquals("Целое число больше нуля", card.state.value.quantityDialog?.error)
        assertEquals(emptyList(), revisions)
        assertEquals(1, deps.calculations.list().size)
        card.cancelQuantityChange()
        assertNull(card.state.value.quantityDialog)
        card.onQuantityChange("3")
        assertNull(card.state.value.quantityDialog, "typing without a dialog opens none")
        card.requestQuantityChange()
        card.onQuantityChange("3")
        assertNull(card.state.value.quantityDialog?.error)
    }

    @Test
    fun `recalculating on current prices shows old and new and creates a second row only on confirm`() {
        val id = deps.saveCalculated()
        deps.calculations.setStatus(id, CalculationStatus.SENT)
        val before = stored(id)
        deps.setSt3TwoMillimetrePrice("300")
        val card = card(id)

        card.prepareRecalc()

        val preview = assertNotNull(card.state.value.recalc)
        assertEquals("11 340,00 ₽", preview.oldUnitPrice)
        assertEquals("12 940,00 ₽", preview.newUnitPrice)
        assertEquals(false, preview.unchanged)
        assertEquals(before, stored(id), "nothing changes before the answer")

        card.cancelRecalc()
        assertNull(card.state.value.recalc)
        assertEquals(before, stored(id), "cancel changes nothing")
        assertEquals(1, deps.calculations.list().size)

        card.prepareRecalc()
        card.confirmRecalc()

        // A second row holds the new result on the current prices ...
        assertEquals(2, deps.calculations.list().size)
        val revision = stored(revisions.single())
        assertEquals(0, BigDecimal("12940.00").compareTo(assertNotNull(revision.unitPrice).amount))
        assertEquals(0, BigDecimal("300").compareTo(assertNotNull(revision.st3TwoMillimetrePrice())), "the snapshot holds the current prices")
        assertEquals(CalculationStatus.CALCULATED, revision.status)
        assertEquals(id, revision.sourceId)
        assertEquals("Кронштейн №2", revision.name, "the repository names a recalculation after its source")
        assertNull(card.state.value.recalc)
        assertEquals("12 940,00 ₽", card(revision.id).state.value.unitPrice)
        // ... and the original is the row it was: its unit price, snapshot, status and every column.
        val original = stored(id)
        assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(original.unitPrice).amount))
        assertEquals(0, BigDecimal("200").compareTo(assertNotNull(original.st3TwoMillimetrePrice())))
        assertEquals(CalculationStatus.SENT, original.status)
        assertEquals(before.copy(status = CalculationStatus.SENT), stored(id))
        assertEquals("11 340,00 ₽", card(id).state.value.unitPrice)
    }

    @Test
    fun `a recalculation missing a price lists the error and changes nothing`() {
        val id = deps.saveCalculated()
        val before = stored(id)
        deps.updateSettings { book ->
            book.copy(materialPricesPerKg = book.materialPricesPerKg.filterNot { it.grade == "Ст3" && it.thicknessMm.compareTo(BigDecimal("2")) == 0 })
        }
        val card = card(id)

        card.prepareRecalc()

        val errors = card.state.value.recalcErrors
        assertEquals(listOf("Нет цены в таблице «Цены материала»: строка «Ст3», столбец «2»"), errors.map { it.message })
        assertNull(card.state.value.recalc)
        assertEquals(before, stored(id))
        assertEquals(1, deps.calculations.list().size)
        card.dismissRecalcErrors()
        assertTrue(card.state.value.recalcErrors.isEmpty())
    }

    @Test
    fun `the status is set by hand and saved`() {
        val id = deps.saveCalculated()
        val card = card(id)

        card.setStatus(CalculationStatus.IN_WORK)

        assertEquals(CalculationStatus.IN_WORK, card.state.value.status)
        assertEquals(CalculationStatus.IN_WORK, stored(id).status)
    }

    @Test
    fun `the status is still set in place, it is workflow and not content`() {
        val id = deps.saveCalculated()
        val before = stored(id)
        val card = card(id)

        card.setStatus(CalculationStatus.ARCHIVED)

        val after = stored(id)
        assertEquals(CalculationStatus.ARCHIVED, after.status)
        assertEquals(before.input, after.input)
        assertEquals(before.result, after.result)
        assertEquals(before.snapshot, after.snapshot)
    }

    @Test
    fun `Изменить on a calculated record asks the administrator password and opens nothing until it is right`() {
        val id = deps.saveCalculated(goldenInput(quantity = 4))
        val before = stored(id)
        val card = card(id)

        card.requestEdit()
        assertEquals("Кронштейн", card.state.value.editQuestion?.name)
        assertNull(card.state.value.editQuestion?.error)
        assertEquals(emptyList(), opened, "the question comes first")

        card.onEditPasswordChange("not-the-password")
        card.confirmEdit()
        assertEquals("Неверный пароль", card.state.value.editQuestion?.error)
        // Typing again clears the error; an empty password is wrong as well.
        card.onEditPasswordChange("")
        assertNull(card.state.value.editQuestion?.error)
        card.confirmEdit()
        assertEquals("Неверный пароль", card.state.value.editQuestion?.error)
        assertEquals(emptyList(), opened)

        card.cancelEdit()
        assertNull(card.state.value.editQuestion)
        assertEquals(emptyList(), opened)

        card.requestEdit()
        card.onEditPasswordChange(ADMIN_PASSWORD)
        card.confirmEdit()

        assertEquals(listOf(id to true), opened, "the editor opens on this very record, in place")
        assertNull(card.state.value.editQuestion)
        assertEquals(before, stored(id))
        assertEquals(1, deps.calculations.list().size, "no revision draft and no copy")
    }

    @Test
    fun `Сделать копию и изменить makes a draft named with a copy number on current prices and leaves the original alone`() {
        val id = deps.saveCalculated(goldenInput(quantity = 4))
        val before = stored(id)
        deps.setSt3TwoMillimetrePrice("300")

        card(id).copyAndEdit()

        val (copyId, inPlace) = opened.single()
        assertEquals(false, inPlace, "a copy is a normal draft with autosave")
        val copy = stored(copyId)
        assertEquals(CalculationStatus.DRAFT, copy.status)
        assertEquals(goldenInput(name = "Кронштейн (2)", quantity = 4), copy.input)
        assertNull(copy.snapshot, "a copy is priced on the current settings")
        assertNull(copy.sourceId)
        assertEquals(before, stored(id))

        card(id).copyAndEdit()
        assertEquals("Кронштейн (3)", stored(opened.last().first).name)
    }

    @Test
    fun `a draft card is edited as it is, with no password and no copy`() {
        val id = deps.calculations.saveDraft(goldenInput())

        card(id).requestEdit()

        assertEquals(listOf(id to false), opened)
        assertNull(card(id).state.value.editQuestion)
        card(id).copyAndEdit()
        assertEquals(1, opened.size)
        assertEquals(1, deps.calculations.list().size)
        card(id).requestQuantityChange()
        assertNull(card(id).state.value.quantityDialog, "a draft changes its run size in the editor")
    }

    @Test
    fun `the card shows the counterparty and the discount lines of a discounted calculation`() {
        val input = goldenInput(quantity = 10).copy(counterparty = "ООО Ромашка", discountPercent = BigDecimal("10"))

        val state = card(deps.saveCalculated(input)).state.value

        assertEquals("ООО Ромашка", state.counterparty)
        assertEquals(DiscountLines("11 340,00 ₽", "Скидка (10 %)", "−1 134,00 ₽"), state.discount)
        assertEquals("10 206,00 ₽", state.unitPrice)
        assertEquals("102 060,00 ₽", state.total)
        assertEquals("10", state.quantity)
    }

    @Test
    fun `a calculation with no discount and no counterparty shows neither`() {
        val state = card(deps.saveCalculated()).state.value

        assertEquals("", state.counterparty)
        assertNull(state.discount)
        assertEquals("11 340,00 ₽", state.unitPrice)
    }

    @Test
    fun `a recalculation and a new run size keep the counterparty and the discount`() {
        val input = goldenInput(quantity = 1).copy(counterparty = "ООО Ромашка", discountPercent = BigDecimal("10"))
        val id = deps.saveCalculated(input)
        deps.setSt3TwoMillimetrePrice("300")
        val card = card(id)

        card.prepareRecalc()
        // 12 940 before the discount, 10 % off.
        assertEquals("10 206,00 ₽", card.state.value.recalc?.oldUnitPrice)
        assertEquals("11 646,00 ₽", card.state.value.recalc?.newUnitPrice)
        card.confirmRecalc()
        card.requestQuantityChange()
        card.onQuantityChange("5")
        card.confirmQuantityChange()

        val recalculated = stored(revisions[0])
        assertEquals("ООО Ромашка", recalculated.input.counterparty)
        assertEquals(0, BigDecimal("10").compareTo(recalculated.input.discountPercent))
        assertEquals(0, BigDecimal("1294.00").compareTo(recalculated.result?.discount?.amount))
        val resized = stored(revisions[1])
        assertEquals("ООО Ромашка", resized.input.counterparty)
        assertEquals(5, resized.quantity)
        assertEquals("Кронштейн №3", resized.name, "№2 is taken by the recalculation")
    }

    @Test
    fun `the card links to the calculation it was made from and to its newer versions`() {
        val id = deps.saveCalculated(goldenInput("Кронштейн"))
        val original = card(id)
        assertNull(original.state.value.source)
        assertEquals(emptyList(), original.state.value.revisions)

        original.requestQuantityChange()
        original.onQuantityChange("5")
        original.confirmQuantityChange()
        val quantityRevision = revisions.single()
        // A revision draft is what an earlier version made of «Изменить»; it may still be in a database.
        val draft = deps.calculations.createRevisionDraft(id)

        val source = assertNotNull(card(quantityRevision).state.value.source)
        assertEquals(id, source.id)
        assertEquals("Кронштейн от ${formatDate(LocalDate.now())}", source.title)
        assertEquals(false, source.isDraft)
        val links = card(id).state.value.revisions
        assertEquals(listOf(draft, quantityRevision).toSet(), links.map { it.id }.toSet())
        assertEquals(setOf(true, false), links.map { it.isDraft }.toSet())
        val calculatedLink = links.single { it.id == quantityRevision }
        assertEquals("Рассчитан, 11 340,00 ₽ × 5", calculatedLink.details)
        assertEquals("Черновик, без цены × 1", links.single { it.id == draft }.details)
        assertEquals(1, links.count { it.isDraft })
    }

    @Test
    fun `a revision whose source was deleted shows no link`() {
        val id = deps.saveCalculated()
        val original = card(id)
        original.requestQuantityChange()
        original.onQuantityChange("2")
        original.confirmQuantityChange()
        val revision = revisions.single()
        deps.calculations.delete(id)

        assertNull(card(revision).state.value.source)
    }

    @Test
    fun `deleting with a wrong password keeps the calculation and says so`() {
        val id = deps.saveCalculated()
        val card = card(id)

        card.requestDelete()
        assertEquals("Кронштейн", card.state.value.deleteQuestion?.name)
        card.onDeletePasswordChange("not-the-password")
        card.confirmDelete()

        assertEquals("Неверный пароль", card.state.value.deleteQuestion?.error)
        assertNotNull(deps.calculations.get(id))
        assertEquals(0, deleted)
        // Typing again clears the error; an empty password is wrong as well.
        card.onDeletePasswordChange("")
        assertNull(card.state.value.deleteQuestion?.error)
        card.confirmDelete()
        assertEquals("Неверный пароль", card.state.value.deleteQuestion?.error)
        assertNotNull(deps.calculations.get(id))
        card.cancelDelete()
        assertNull(card.state.value.deleteQuestion)
        assertNotNull(deps.calculations.get(id))
    }

    @Test
    fun `deleting with the administrator password removes the calculation of any status`() {
        for (status in listOf(null) + CalculationStatus.MANUAL) {
            val id = deps.saveCalculated()
            status?.let { deps.calculations.setStatus(id, it) }
            val card = card(id)

            card.requestDelete()
            card.onDeletePasswordChange(ADMIN_PASSWORD)
            card.confirmDelete()

            assertNull(deps.calculations.get(id), "$status")
            assertNull(card.state.value.deleteQuestion)
        }
        assertEquals(4, deleted)
    }

    @Test
    fun `a draft is deleted with the password too`() {
        val id = deps.calculations.saveDraft(goldenInput())
        val card = card(id)

        card.requestDelete()
        card.onDeletePasswordChange("wrong")
        card.confirmDelete()
        assertNotNull(deps.calculations.get(id))

        card.onDeletePasswordChange(ADMIN_PASSWORD)
        card.confirmDelete()
        assertNull(deps.calculations.get(id))
        assertEquals(1, deleted)
    }

    private fun archiveTarget(): Path = temp.newFolder("out").toPath().resolve("a.zip")

    @Test
    fun `В архив asks the administrator password, and a wrong one writes nothing and deletes nothing`() {
        val id = deps.saveCalculated()
        val target = archiveTarget()
        val card = card(id)

        card.requestArchive(target)
        assertEquals(ArchiveQuestion(1, "Кронштейн", target.toString(), null), card.state.value.archiveQuestion)
        card.onArchivePasswordChange("not-the-password")
        card.confirmArchive()

        assertEquals(WRONG_PASSWORD, card.state.value.archiveQuestion?.error)
        assertNotNull(deps.calculations.get(id))
        assertEquals(emptyList(), target.parent.listDirectoryEntries())
        assertEquals(emptyList(), archived)
        card.cancelArchive()
        assertNull(card.state.value.archiveQuestion)
        assertNotNull(deps.calculations.get(id))
    }

    @Test
    fun `В архив with the password writes the zip, deletes the calculation with its drawings and hands the outcome over`() {
        val id = deps.saveCalculated()
        val drawing = temp.newFile("узел.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertIs<AttachResult.Added>(deps.attachments.add(id, drawing.toPath()))
        val target = archiveTarget()
        val card = card(id)

        card.requestArchive(target)
        card.onArchivePasswordChange(ADMIN_PASSWORD)
        card.confirmArchive()

        assertNull(deps.calculations.get(id))
        assertEquals(emptyList(), deps.attachments.list(id))
        assertEquals(listOf(ArchiveOutcome.Sent(1, target)), archived)
        assertEquals(0, deleted, "it is archived, not deleted from the card")
        val folder = "Кронштейн__${id.toString().take(8)}"
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                setOf("manifest.json", "$folder/calculation.json", "$folder/calculation.pdf", "$folder/drawings/узел.png"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
        }
        assertEquals(listOf(target), target.parent.listDirectoryEntries())
    }

    @Test
    fun `a failed archive stays on the card as a message and deletes nothing`() {
        val id = deps.saveCalculated()
        val target = archiveTarget()
        val failing = CalculationArchive(deps.calculations, deps.attachments, pdf = { throw IOException("PDF failed") })
        val card = card(id, failing)

        card.requestArchive(target)
        card.onArchivePasswordChange(ADMIN_PASSWORD)
        card.confirmArchive()

        assertNotNull(deps.calculations.get(id))
        assertEquals(emptyList(), archived)
        val message = assertNotNull(card.state.value.message)
        assertTrue(message.isError)
        assertEquals("Не удалось отправить в архив: не удалось записать архив в $target: PDF failed. Ничего не удалено.", message.text)
        assertNull(card.state.value.archiveQuestion)
        assertEquals(emptyList(), target.parent.listDirectoryEntries())
        card.dismissMessage()
        assertNull(card.state.value.message)
    }

    @Test
    fun `a draft can be archived too, without a PDF`() {
        val id = deps.calculations.saveDraft(goldenInput())
        val target = archiveTarget()
        val card = card(id)

        card.requestArchive(target)
        card.onArchivePasswordChange(ADMIN_PASSWORD)
        card.confirmArchive()

        assertNull(deps.calculations.get(id))
        ZipFile(target.toFile()).use { zip ->
            assertEquals(setOf("manifest.json", "Кронштейн__${id.toString().take(8)}/calculation.json"), zip.entries().asSequence().map { it.name }.toSet())
        }
    }
}
