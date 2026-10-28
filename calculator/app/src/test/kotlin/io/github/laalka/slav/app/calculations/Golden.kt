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
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.QuickCalculationInput
import io.github.laalka.slav.core.QuickCalculator
import io.github.laalka.slav.core.QuickOutcome
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertIs

/**
 * The golden case: one part «Кронштейн», sheet Ст3 2 mm 1000 × 1000, laser contour 4000 mm with 3
 * pierces, 4 bends of 80 mm, and the assembly welding of a 40 mm seam (semi-automatic, steel, 2 mm)
 * — 16,00 kg and 11 340,00 on the default prices: 3200 + 8000 + 0 + 100 + 40.
 */
fun goldenInput(name: String = "Кронштейн", quantity: Int = 1) = CalculationInput(
    name = name,
    quantity = quantity,
    parts = listOf(
        Part(
            name = "Кронштейн",
            material = Material.Sheet("Ст3", BigDecimal("2"), SheetShape.Rectangle(BigDecimal("1000"), BigDecimal("1000"))),
            operations = listOf(
                PartOperation.LaserSheet(SheetCutMode.ContourLength(BigDecimal("4000"), pierces = 3)),
                PartOperation.Bending(listOf(BendRow(BigDecimal("80"), 4))),
            ),
        ),
    ),
    productOperations = listOf(
        ProductOperation.AssemblyWelding("полуавтомат", "Сталь", BigDecimal("2"), seamLengthMm = BigDecimal("40")),
    ),
)

/** [input] priced on the current settings and saved as «Рассчитан», the way «Рассчитать» saves it. */
fun Deps.saveCalculated(input: CalculationInput = goldenInput()): UUID {
    val book = settings.load()
    val result = assertIs<CalcOutcome.Ok>(Calculator(book).calculate(input)).result
    return calculations.saveCalculated(input, book, result)
}

/**
 * The golden quick case: Ст3, 2 mm, 1.5 m² of sheet and 12 min of laser — 24,00 kg and 5 400,00 on
 * the default prices: 4800 for the material and 600 for the cut.
 */
fun quickGoldenInput(name: String = "Лист на раскрой", quantity: Int = 1) = QuickCalculationInput(
    name = name,
    quantity = quantity,
    grade = "Ст3",
    thicknessMm = BigDecimal("2"),
    sheetAreaM2 = BigDecimal("1.5"),
    cutTimeMin = BigDecimal("12"),
)

/** [input] priced on the current settings and saved as «Рассчитан», the way the quick editor's «Рассчитать» saves it. */
fun Deps.saveQuickCalculated(input: QuickCalculationInput = quickGoldenInput()): UUID {
    val book = settings.load()
    val result = assertIs<QuickOutcome.Ok>(QuickCalculator(book).calculate(input)).result
    return calculations.saveCalculated(input, book, result)
}

fun Deps.updateSettings(transform: (PriceBook) -> PriceBook) = settings.save(transform(settings.load()))

/** Ст3 at [pricePerKg] for 2 mm: 300 makes the golden material 4 800,00 and the unit price 12 940,00. */
fun Deps.setSt3TwoMillimetrePrice(pricePerKg: String) = updateSettings { book ->
    book.copy(
        materialPricesPerKg = book.materialPricesPerKg.map {
            if (it.grade == "Ст3" && it.thicknessMm.compareTo(BigDecimal("2")) == 0) it.copy(pricePerKg = BigDecimal(pricePerKg)) else it
        },
    )
}
