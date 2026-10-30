package io.github.laalka.slav.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

fun dec(value: String) = BigDecimal(value)

/** Numeric equality: BigDecimal.equals would fail "2.0" against "2". */
fun assertDecimal(expected: String, actual: BigDecimal?) {
    assertNotNull(actual, "expected $expected, got null")
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got ${actual.toPlainString()}")
}

/** For results involving π: compares after rounding [actual] to the scale of [expected]. */
fun assertDecimalRounded(expected: String, actual: BigDecimal) {
    val scale = BigDecimal(expected).scale()
    assertEquals(expected, actual.setScale(scale, RoundingMode.HALF_UP).toPlainString())
}

fun assertMoney(expected: String, actual: Money) = assertDecimal(expected, actual.amount)

/** Exact text, scale included: a rounded line is always written with two decimals. */
fun assertKopecks(expected: String, actual: Money) = assertEquals(expected, actual.amount.toPlainString())

/** A one-piece calculation of a single part, no product operations. */
fun onePart(material: Material, vararg operations: PartOperation, materialExtra: ManualExtra? = null) =
    CalculationInput(
        name = "Расчёт",
        quantity = 1,
        parts = listOf(Part("Деталь", material, operations.toList(), materialExtra)),
        productOperations = emptyList(),
    )

fun PriceBook.priced(input: CalculationInput): CalcResult {
    val outcome = Calculator(this).calculate(input)
    return assertIs<CalcOutcome.Ok>(outcome, "expected Ok, got $outcome").result
}

fun PriceBook.errorsOf(input: CalculationInput): List<CalcError> {
    val outcome = Calculator(this).calculate(input)
    return assertIs<CalcOutcome.Failed>(outcome, "expected Failed, got $outcome").errors
}

/** A rectangular sheet of Ст3. */
fun rectangleSheet(thicknessMm: String, lengthMm: String, widthMm: String) =
    Material.Sheet(Fixtures.ST3, dec(thicknessMm), SheetShape.Rectangle(dec(lengthMm), dec(widthMm)))

/** Cost of the first operation of the only part. */
fun PriceBook.costOf(material: Material, operation: PartOperation): Money =
    priced(onePart(material, operation)).parts.single().operations.single().cost

object Fixtures {
    const val STEEL = "Сталь"
    const val ST3 = "Ст3"

    val angle50 = AngleSize("50×50×5", dec("50"), dec("50"), dec("5"), dec("3.77"))
    val channel10 = BeamSize("10П", dec("100"), dec("46"), dec("4.5"), dec("8.59"))
    val iBeam20 = BeamSize("20", dec("200"), dec("100"), dec("5.2"), dec("21.0"))

    val sheet = rectangleSheet("2", "1000", "500")
    val roundTube = Material.RoundTube(ST3, diameterMm = dec("57"), wallMm = dec("3"), lengthMm = dec("1000"))
    val profileTube =
        Material.ProfileTube(ST3, sideAMm = dec("40"), sideBMm = dec("20"), wallMm = dec("2"), lengthMm = dec("6000"))
    val angle = Material.Angle(ST3, angle50, lengthMm = dec("2500"))
    val channel = Material.Channel(ST3, channel10, lengthMm = dec("1200"))
    val iBeam = Material.IBeam(ST3, iBeam20, lengthMm = dec("3500"))

    val allMaterials: List<Material> = listOf(sheet, roundTube, profileTube, angle, channel, iBeam)

    private fun extra(amount: String, comment: String) = ManualExtra(Money(dec(amount)), comment)

    /**
     * Every material kind, sheet shape, part operation and cut mode, both product operations,
     * manual extras and a mass from the drawing.
     */
    val fullInput = CalculationInput(
        name = "Кронштейн КР-12",
        quantity = 25,
        parts = listOf(
            Part(
                name = "Основание",
                material = sheet,
                operations = listOf(
                    PartOperation.LaserSheet(SheetCutMode.ByShape),
                    PartOperation.LaserSheet(
                        SheetCutMode.ContourLength(lengthMm = dec("1840.5"), pierces = 3),
                        extra("150", "Снятие фаски"),
                    ),
                    PartOperation.Holes(
                        listOf(HoleRow(dec("8.5"), count = 4), HoleRow(dec("12"), count = 2)),
                    ),
                    PartOperation.Bending(listOf(BendRow(dec("500"), 2), BendRow(dec("100"), 1))),
                ),
                materialExtra = extra("200.50", "Доставка листа"),
            ),
            Part("Фланец", Material.Sheet(ST3, dec("4"), SheetShape.Circle(dec("50"))), listOf(PartOperation.LaserSheet(SheetCutMode.ByShape))),
            Part(
                "Шайба",
                Material.Sheet(ST3, dec("2"), SheetShape.Ring(dec("50"), dec("20"))),
                listOf(PartOperation.LaserSheet(SheetCutMode.ByShape)),
                massOverrideKg = dec("0.1"),
            ),
            Part(
                "Накладка",
                Material.Sheet(ST3, dec("3"), SheetShape.Free(areaMm2 = dec("12500.5"), perimeterMm = dec("640"))),
                listOf(PartOperation.LaserSheet(SheetCutMode.ByShape)),
            ),
            Part(
                "Пластина",
                Material.Sheet(ST3, dec("3"), SheetShape.ByMass(massKg = dec("12"), perimeterMm = dec("3000"))),
                listOf(PartOperation.LaserSheet(SheetCutMode.ByShape)),
            ),
            Part("Прокладка", Material.Sheet(ST3, dec("2"), SheetShape.ByMass(massKg = dec("0.5"))), emptyList()),
            Part(
                name = "Стойка",
                material = roundTube,
                operations = listOf(
                    PartOperation.TubeLaser(),
                    PartOperation.Holes(listOf(HoleRow(dec("10"), count = 2, throughBothWalls = true))),
                ),
            ),
            Part("Поперечина", profileTube, listOf(PartOperation.TubeLaser(cuts = 4, manualExtra = extra("0", "")))),
            Part("Упор", angle, emptyList()),
            Part("Рама", channel, emptyList()),
            Part("Балка", iBeam, emptyList()),
        ),
        productOperations = listOf(
            ProductOperation.AssemblyWelding(
                weldType = "полуавтомат",
                group = STEEL,
                thicknessMm = dec("3"),
                seamLengthMm = dec("561"),
                manualExtra = extra("1000", "Зачистка швов"),
            ),
            ProductOperation.Painting(),
        ),
    )
}
