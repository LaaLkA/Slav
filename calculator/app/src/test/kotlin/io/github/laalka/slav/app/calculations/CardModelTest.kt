package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.WeldRow
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() = deps.close()

    private fun card(id: UUID) = CardModel(deps.calculations, deps.settings, id)

    private fun stored(id: UUID): StoredCalculation = assertNotNull(deps.calculations.get(id))

    private fun StoredCalculation.st3TwoMillimetrePrice(): BigDecimal? =
        snapshot?.materialPricesPerKg?.single { it.grade == "Ст3" && it.thicknessMm.compareTo(BigDecimal("2")) == 0 }?.pricePerKg

    @Test
    fun `the card shows the breakdown, the masses and the snapshot date of the golden case`() {
        val id = deps.saveCalculated(goldenInput(quantity = 10))

        val state = card(id).state.value

        assertEquals("11 280,00 ₽", state.unitPrice)
        assertEquals("112 800,00 ₽", state.total)
        assertEquals("15,7 кг", state.unitMass)
        assertEquals("157 кг", state.runMass)
        assertEquals("10", state.quantityText)
        val part = state.parts.single()
        assertEquals(
            listOf(
                CardLine("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", null, "3 140,00 ₽"),
                CardLine("Лазер: рез листа", null, "8 000,00 ₽"),
                CardLine("Гибка", null, "100,00 ₽"),
                CardLine("Сварка (полуавтомат)", null, "40,00 ₽"),
            ),
            part.lines,
        )
        assertEquals("11 280,00 ₽", part.cost)
        assertEquals("1 шт на изделие · 15,7 кг за шт", part.details)
        assertEquals(formatDate(LocalDate.now()), state.snapshotDate)
        assertEquals(CalculationStatus.MANUAL.toList().sortedBy { it.ordinal }, state.statusOptions)
    }

    @Test
    fun `manual extras with their comments and product lines are part of the breakdown`() {
        val golden = goldenInput()
        val input = golden.copy(
            parts = golden.parts.map { it.copy(qtyPerProduct = 2, materialExtra = ManualExtra(Money(BigDecimal("500")), "срочно")) },
            productOperations = listOf(ProductOperation.AssemblyWelding("полуавтомат", "Сталь", BigDecimal("2"), listOf(WeldRow(BigDecimal("100"), 1)))),
        )
        val state = card(deps.saveCalculated(input)).state.value

        val part = state.parts.single()
        assertEquals(CardLine("Материал: Лист Ст3, 2 мм, 1000 × 1000 мм", "в т. ч. доп. операции 500,00 ₽ — срочно", "3 640,00 ₽"), part.lines.first())
        assertEquals("× 2 шт = 23 560,00 ₽", part.perProduct)
        // 100 mm = 10 cm × 10 ₽/cm of semi-automatic welding, steel, 2 mm.
        assertEquals(listOf(CardLine("Сварка сборки (полуавтомат)", null, "100,00 ₽")), state.productLines)
        assertEquals("23 660,00 ₽", state.unitPrice)
    }

    @Test
    fun `changing the run size updates the total at once and saves it without recalculating`() {
        val id = deps.saveCalculated()
        val before = stored(id)
        // The current prices change after the calculation: the card must keep the snapshot's unit price.
        deps.setSt3TwoMillimetrePrice("300")
        val card = card(id)

        card.onQuantityChange("10")

        assertEquals("112 800,00 ₽", card.state.value.total)
        assertEquals("11 280,00 ₽", card.state.value.unitPrice)
        val after = stored(id)
        assertEquals(10, after.quantity)
        assertEquals(0, BigDecimal("11280.00").compareTo(assertNotNull(after.unitPrice).amount))
        assertEquals(before.snapshot, after.snapshot)
        assertEquals(before.snapshotAt, after.snapshotAt)
        assertEquals(before.result, after.result)
        assertEquals(CalculationStatus.CALCULATED, after.status)
    }

    @Test
    fun `an invalid run size is flagged and nothing is saved`() {
        val id = deps.saveCalculated()
        val card = card(id)

        card.onQuantityChange("0")
        assertEquals("Целое число больше нуля", card.state.value.quantityError)
        card.onQuantityChange("")
        assertEquals("Заполните", card.state.value.quantityError)

        assertEquals(1, stored(id).quantity)
        assertEquals("11 280,00 ₽", card.state.value.total)
        card.onQuantityChange("3")
        assertNull(card.state.value.quantityError)
        assertEquals("33 840,00 ₽", card.state.value.total)
    }

    @Test
    fun `recalculating on current prices shows old and new and applies only on confirm`() {
        val id = deps.saveCalculated()
        deps.calculations.setStatus(id, CalculationStatus.SENT)
        val before = stored(id)
        deps.setSt3TwoMillimetrePrice("300")
        val card = card(id)

        card.prepareRecalc()

        val preview = assertNotNull(card.state.value.recalc)
        assertEquals("11 280,00 ₽", preview.oldUnitPrice)
        assertEquals("12 850,00 ₽", preview.newUnitPrice)
        assertEquals(false, preview.unchanged)
        assertEquals(before, stored(id), "nothing changes before the answer")

        card.cancelRecalc()
        assertNull(card.state.value.recalc)
        assertEquals(before, stored(id), "cancel changes nothing")

        card.prepareRecalc()
        card.confirmRecalc()

        val after = stored(id)
        assertEquals(0, BigDecimal("12850.00").compareTo(assertNotNull(after.unitPrice).amount))
        assertEquals(0, BigDecimal("300").compareTo(assertNotNull(after.st3TwoMillimetrePrice())), "the snapshot holds the current prices")
        assertEquals(CalculationStatus.CALCULATED, after.status)
        assertNull(card.state.value.recalc)
        assertEquals("12 850,00 ₽", card.state.value.unitPrice)
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
}
