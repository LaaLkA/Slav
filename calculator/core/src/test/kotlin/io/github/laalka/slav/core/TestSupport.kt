package io.github.laalka.slav.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
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

object Fixtures {
    const val STEEL = "Сталь"
    const val ST3 = "Ст3"

    val angle50 = AngleSize("50×50×5", dec("50"), dec("50"), dec("5"), dec("3.77"))
    val channel10 = BeamSize("10П", dec("100"), dec("46"), dec("4.5"), dec("8.59"))
    val iBeam20 = BeamSize("20", dec("200"), dec("100"), dec("5.2"), dec("21.0"))

    val sheet = Material.Sheet(ST3, thicknessMm = dec("2"), lengthMm = dec("1000"), widthMm = dec("500"))
    val roundTube = Material.RoundTube(ST3, diameterMm = dec("57"), wallMm = dec("3"), lengthMm = dec("1000"))
    val profileTube =
        Material.ProfileTube(ST3, sideAMm = dec("40"), sideBMm = dec("20"), wallMm = dec("2"), lengthMm = dec("6000"))
    val angle = Material.Angle(ST3, angle50, lengthMm = dec("2500"))
    val channel = Material.Channel(ST3, channel10, lengthMm = dec("1200"))
    val iBeam = Material.IBeam(ST3, iBeam20, lengthMm = dec("3500"))

    val allMaterials: List<Material> = listOf(sheet, roundTube, profileTube, angle, channel, iBeam)

    private fun extra(amount: String, comment: String) = ManualExtra(Money(dec(amount)), comment)

    /** Every material kind, every part operation and cut mode, both product operations, manual extras. */
    val fullInput = CalculationInput(
        name = "Кронштейн КР-12",
        quantity = 25,
        parts = listOf(
            Part(
                name = "Основание",
                qtyPerProduct = 1,
                material = sheet,
                operations = listOf(
                    PartOperation.LaserSheet(SheetCutMode.Rectangle),
                    PartOperation.LaserSheet(
                        SheetCutMode.ContourLength(lengthMm = dec("1840.5"), pierces = 3),
                        extra("150", "Снятие фаски"),
                    ),
                    PartOperation.Holes(
                        listOf(HoleRow(dec("8.5"), count = 4), HoleRow(dec("12"), count = 2)),
                    ),
                    PartOperation.Bending(listOf(BendRow(dec("500"), 2), BendRow(dec("100"), 1))),
                    PartOperation.Welding("полуавтомат", listOf(WeldRow(dec("120"), 4))),
                    PartOperation.Painting(areaOverrideM2 = dec("0.75")),
                ),
                materialExtra = extra("200.50", "Доставка листа"),
            ),
            Part(
                name = "Стойка",
                qtyPerProduct = 2,
                material = roundTube,
                operations = listOf(
                    PartOperation.TubeLaser(),
                    PartOperation.Holes(listOf(HoleRow(dec("10"), count = 2, throughBothWalls = true))),
                    PartOperation.Painting(),
                ),
            ),
            Part(
                "Поперечина", 2, profileTube,
                listOf(PartOperation.TubeLaser(cuts = 4, manualExtra = extra("0", ""))),
            ),
            Part("Упор", 4, angle, listOf(PartOperation.Welding("аргон", listOf(WeldRow(dec("50"), 2))))),
            Part("Рама", 1, channel, emptyList()),
            Part("Балка", 1, iBeam, listOf(PartOperation.Painting())),
        ),
        productOperations = listOf(
            ProductOperation.AssemblyWelding(
                weldType = "полуавтомат",
                group = STEEL,
                thicknessMm = dec("3"),
                rows = listOf(WeldRow(dec("80"), 6), WeldRow(dec("40.5"), 2)),
                manualExtra = extra("1000", "Зачистка швов"),
            ),
            ProductOperation.Painting(),
        ),
    )
}
