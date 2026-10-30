package io.github.laalka.slav.app.library

import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BeamSize
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.SheetShape
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRowTest {
    private fun dec(value: String) = BigDecimal(value)

    private val angle = AngleSize("50×50×5", dec("50"), dec("50"), dec("5"), dec("3.77"))
    private val beam = BeamSize("10У", dec("100"), dec("46"), dec("4.5"), dec("9.46"))

    @Test
    fun `a sheet is described by its kind, grade and thickness, then its shape`() {
        assertEquals("Лист Ст3 2 мм, 1000×1000", Material.Sheet("Ст3", dec("2"), SheetShape.Rectangle(dec("1000"), dec("1000"))).summary())
        assertEquals("Лист Ст3 2,5 мм, 1200,5×300", Material.Sheet("Ст3", dec("2.50"), SheetShape.Rectangle(dec("1200.5"), dec("300"))).summary())
        assertEquals("Лист Ст3 3 мм, круг R50", Material.Sheet("Ст3", dec("3"), SheetShape.Circle(dec("50"))).summary())
        assertEquals("Лист Ст3 3 мм, кольцо R50 / r20", Material.Sheet("Ст3", dec("3"), SheetShape.Ring(dec("50"), dec("20"))).summary())
        assertEquals("Лист Ст3 3 мм, произвольная форма, 12500 мм²", Material.Sheet("Ст3", dec("3"), SheetShape.Free(dec("12500"), dec("600"))).summary())
    }

    @Test
    fun `a sheet set by weight says the weight and the perimeter when there is one`() {
        assertEquals("Лист Ст3 3 мм, по весу 12 кг", Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12"))).summary())
        assertEquals(
            "Лист Ст3 3 мм, по весу 12 кг, периметр 1 200 мм",
            Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12"), dec("1200"))).summary(),
        )
        assertEquals("Лист Ст3 3 мм, по весу 12,5 кг", Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12.50"))).summary())
    }

    @Test
    fun `a long product is described by its section and its length`() {
        assertEquals("Труба круглая Ст3 57×3 мм, длина 1000 мм", Material.RoundTube("Ст3", dec("57"), dec("3"), dec("1000")).summary())
        assertEquals("Труба профильная Ст3 40×20×2 мм, длина 600 мм", Material.ProfileTube("Ст3", dec("40"), dec("20"), dec("2"), dec("600")).summary())
        assertEquals("Уголок Ст3 50×50×5, длина 750 мм", Material.Angle("Ст3", angle, dec("750")).summary())
        assertEquals("Швеллер Ст3 10У, длина 2000 мм", Material.Channel("Ст3", beam, dec("2000")).summary())
        assertEquals("Двутавр Ст3 10У, длина 2000 мм", Material.IBeam("Ст3", beam, dec("2000")).summary())
    }

    @Test
    fun `what an unfinished part has not filled in is left out, not shown as zeros`() {
        val blank = Material.Sheet("", BigDecimal.ZERO, SheetShape.Rectangle(BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals("Лист", blank.summary())
        assertEquals("Лист Ст3", blank.copy(grade = "Ст3").summary())
        assertEquals("Лист Ст3 2 мм", blank.copy(grade = "Ст3", thicknessMm = dec("2")).summary())
        // A shape with one side missing says nothing about its size rather than half of it.
        assertEquals("Лист Ст3 2 мм", Material.Sheet("Ст3", dec("2"), SheetShape.Rectangle(dec("1000"), BigDecimal.ZERO)).summary())
        assertEquals("Лист Ст3 2 мм", Material.Sheet("Ст3", dec("2"), SheetShape.Ring(dec("50"), BigDecimal.ZERO)).summary())
        // A weight not typed yet says nothing, whatever the perimeter.
        assertEquals("Лист Ст3 2 мм", Material.Sheet("Ст3", dec("2"), SheetShape.ByMass(BigDecimal.ZERO, dec("500"))).summary())
        assertEquals("Труба круглая", Material.RoundTube("", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).summary())
        assertEquals("Уголок Ст3, длина 750 мм", Material.Angle("Ст3", angle.copy(designation = ""), dec("750")).summary())
    }
}
