package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertIs

fun dec(value: String) = BigDecimal(value)

/** A clock the test moves by hand. The zone is not UTC, so day boundaries of the list filter are tested. */
class TestClock(var now: Instant = Instant.parse("2026-10-22T09:00:00Z"), private val zone: ZoneId = MOSCOW) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    companion object {
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
    }
}

/**
 * A sheet of Ст3, 2 mm, cut on the laser, with manual extras on both lines; [cutExtra] varies the
 * unit price. Priceable with [PriceBook.default].
 */
fun sheetInput(name: String, quantity: Int = 1, cutExtra: String = "150") = CalculationInput(
    name = name,
    quantity = quantity,
    parts = listOf(
        Part(
            name = "Пластина",
            material = Material.Sheet("Ст3", thicknessMm = dec("2"), shape = SheetShape.Rectangle(dec("1000"), dec("500"))),
            operations = listOf(
                PartOperation.LaserSheet(SheetCutMode.ByShape, ManualExtra(Money(dec(cutExtra)), "Снятие фаски")),
            ),
            materialExtra = ManualExtra(Money(dec("200.50")), "Доставка листа"),
        ),
    ),
    productOperations = emptyList(),
)

fun PriceBook.priced(input: CalculationInput): CalcResult =
    assertIs<CalcOutcome.Ok>(Calculator(this).calculate(input), "the test input must be priceable").result
