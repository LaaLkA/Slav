package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.ui.formatDate
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.SheetShape
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

        assertEquals("11 340,00 ₽", state.unitPrice)
        assertEquals("113 400,00 ₽", state.total)
        assertEquals("16 кг", state.unitMass)
        assertEquals("160 кг", state.runMass)
        assertEquals("10", state.quantityText)
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
        fun title(shape: SheetShape) = card(
            deps.saveCalculated(golden.copy(parts = golden.parts.map { it.copy(material = Material.Sheet("Ст3", BigDecimal("2"), shape), operations = emptyList()) })),
        ).state.value.parts.single().lines.first().title

        assertEquals("Материал: Лист Ст3, 2 мм, круг R 50 мм", title(SheetShape.Circle(BigDecimal("50"))))
        assertEquals("Материал: Лист Ст3, 2 мм, кольцо R 50 / r 20 мм", title(SheetShape.Ring(BigDecimal("50"), BigDecimal("20"))))
        assertEquals(
            "Материал: Лист Ст3, 2 мм, произвольная форма, площадь 12000,5 мм², периметр 500 мм",
            title(SheetShape.Free(BigDecimal("12000.5"), BigDecimal("500"))),
        )
    }

    @Test
    fun `changing the run size updates the total at once and saves it without recalculating`() {
        val id = deps.saveCalculated()
        val before = stored(id)
        // The current prices change after the calculation: the card must keep the snapshot's unit price.
        deps.setSt3TwoMillimetrePrice("300")
        val card = card(id)

        card.onQuantityChange("10")

        assertEquals("113 400,00 ₽", card.state.value.total)
        assertEquals("11 340,00 ₽", card.state.value.unitPrice)
        val after = stored(id)
        assertEquals(10, after.quantity)
        assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(after.unitPrice).amount))
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
        assertEquals("11 340,00 ₽", card.state.value.total)
        card.onQuantityChange("3")
        assertNull(card.state.value.quantityError)
        assertEquals("34 020,00 ₽", card.state.value.total)
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
        assertEquals("11 340,00 ₽", preview.oldUnitPrice)
        assertEquals("12 940,00 ₽", preview.newUnitPrice)
        assertEquals(false, preview.unchanged)
        assertEquals(before, stored(id), "nothing changes before the answer")

        card.cancelRecalc()
        assertNull(card.state.value.recalc)
        assertEquals(before, stored(id), "cancel changes nothing")

        card.prepareRecalc()
        card.confirmRecalc()

        val after = stored(id)
        assertEquals(0, BigDecimal("12940.00").compareTo(assertNotNull(after.unitPrice).amount))
        assertEquals(0, BigDecimal("300").compareTo(assertNotNull(after.st3TwoMillimetrePrice())), "the snapshot holds the current prices")
        assertEquals(CalculationStatus.CALCULATED, after.status)
        assertNull(card.state.value.recalc)
        assertEquals("12 940,00 ₽", card.state.value.unitPrice)
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
