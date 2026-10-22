package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.core.BendRow
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.WeldRow
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertIs

/**
 * The golden case of the old spreadsheet: sheet Ст3 2 mm 1000 × 1000, laser contour 4000 mm with
 * 3 pierces, 4 bends of 80 mm, 40 mm of semi-automatic welding — 11 280,00 on the default prices.
 */
fun goldenInput(name: String = "Кронштейн", quantity: Int = 1) = CalculationInput(
    name = name,
    quantity = quantity,
    parts = listOf(
        Part(
            name = "Деталь 1",
            qtyPerProduct = 1,
            material = Material.Sheet("Ст3", BigDecimal("2"), lengthMm = BigDecimal("1000"), widthMm = BigDecimal("1000")),
            operations = listOf(
                PartOperation.LaserSheet(SheetCutMode.ContourLength(BigDecimal("4000"), pierces = 3)),
                PartOperation.Bending(listOf(BendRow(BigDecimal("80"), 4))),
                PartOperation.Welding("полуавтомат", listOf(WeldRow(BigDecimal("40"), 1))),
            ),
        ),
    ),
    productOperations = emptyList(),
)

/** [input] priced on the current settings and saved as «Рассчитан», the way «Рассчитать» saves it. */
fun Deps.saveCalculated(input: CalculationInput = goldenInput()): UUID {
    val book = settings.load()
    val result = assertIs<CalcOutcome.Ok>(Calculator(book).calculate(input)).result
    return calculations.saveCalculated(input, book, result)
}

fun Deps.updateSettings(transform: (PriceBook) -> PriceBook) = settings.save(transform(settings.load()))

/** Ст3 at [pricePerKg] for 2 mm: 300 makes the golden material 4 710,00 and the unit price 12 850,00. */
fun Deps.setSt3TwoMillimetrePrice(pricePerKg: String) = updateSettings { book ->
    book.copy(
        materialPricesPerKg = book.materialPricesPerKg.map {
            if (it.grade == "Ст3" && it.thicknessMm.compareTo(BigDecimal("2")) == 0) it.copy(pricePerKg = BigDecimal(pricePerKg)) else it
        },
    )
}
