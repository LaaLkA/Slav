package io.github.laalka.slav.app.editor

import io.github.laalka.slav.core.CalcError
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.SheetShape
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class CalcErrorTextTest {
    @Test
    fun `a sheet set by weight cut by its shape without a perimeter is told what to type or choose instead`() {
        val sheet = Material.Sheet("Ст3", BigDecimal("3"), SheetShape.ByMass(BigDecimal("12")))

        val error = CalcError.MissingCutLength(sheet).readable(onSnapshot = false, currency = "₽")

        assertEquals(
            "Лист Ст3, 3 мм, по весу 12 кг: лазер «По форме» — у листа, заданного весом, нет периметра. " +
                "Введите периметр или выберите «Длина контура».",
            error.message,
        )
        assertEquals(null, error.cell, "it points to no price cell")
    }
}
