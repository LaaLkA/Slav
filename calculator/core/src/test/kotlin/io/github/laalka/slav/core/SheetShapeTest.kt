package io.github.laalka.slav.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand-computed numbers of the sheet shapes: R = 50 mm, r = 20 mm, t = 2 mm, density 8000 kg/m³.
 * Mass is area × t × density with mm³ → m³ shifted by nine places; the painted surface is
 * 2 × area + cut length × t, mm² → m².
 */
class SheetShapeTest {
    private val steel = dec("8000")

    private fun sheet(shape: SheetShape) = Material.Sheet("Ст3", dec("2"), shape)

    @Test
    fun `rectangle 300 by 200`() {
        val shape = SheetShape.Rectangle(dec("300"), dec("200"))
        assertDecimal("60000", shape.areaMm2)
        assertDecimal("1000", shape.cutLengthMm)
        assertEquals(1, shape.pierces)
        // 60 000 mm² × 2 mm = 120 000 mm³ = 0.00012 m³; × 8000 = 0.96 kg.
        assertDecimal("0.96", sheet(shape).massKg(steel))
        // 2 × 60 000 + 1000 × 2 = 122 000 mm² = 0.122 m².
        assertDecimal("0.122", sheet(shape).paintAreaM2(steel))
    }

    @Test
    fun `strip is a rectangle`() {
        // 1500 × 40 strip: area 60 000 mm², cut 2 × (1500 + 40) = 3080 mm, one pierce.
        val strip = SheetShape.Rectangle(dec("1500"), dec("40"))
        assertDecimal("60000", strip.areaMm2)
        assertDecimal("3080", strip.cutLengthMm)
        assertEquals(1, strip.pierces)
    }

    @Test
    fun `rectangle paint area equals the spreadsheet formula 2 times LW + Wt + Lt`() {
        for ((length, width, thickness) in listOf(Triple("1000", "500", "2"), Triple("300", "200", "4"), Triple("2500", "1250", "10.5"))) {
            val l = dec(length)
            val w = dec(width)
            val t = dec(thickness)
            val spreadsheet = (dec("2") * (l * w + w * t + l * t)).mm2ToM2()
            val shapeBased = Material.Sheet("Ст3", t, SheetShape.Rectangle(l, w)).paintAreaM2(steel)
            assertDecimal(spreadsheet.toPlainString(), shapeBased)
        }
        // And one of them by hand: 2 × (1000 × 500 + 500 × 2 + 1000 × 2) = 1 006 000 mm² = 1.006 m².
        assertDecimal("1.006", rectangleSheet("2", "1000", "500").paintAreaM2(steel))
    }

    @Test
    fun `circle of radius 50`() {
        val shape = SheetShape.Circle(dec("50"))
        // π × 50² = 7853.98163397448309615… mm².
        assertDecimalRounded("7853.981633974483", shape.areaMm2)
        // 2 × π × 50 = 314.159265358979323846 mm.
        assertDecimalRounded("314.159265358979", shape.cutLengthMm)
        assertEquals(1, shape.pierces)
        // 7853.98… × 2 = 15 707.96… mm³ = 1.570796…e-5 m³; × 8000 = 0.12566370614359… kg.
        assertDecimalRounded("0.125663706143592", sheet(shape).massKg(steel))
        // 2 × 7853.98… + 314.159… × 2 = 16 336.2817986669… mm² = 0.0163362817986669 m².
        assertDecimalRounded("0.016336281798667", sheet(shape).paintAreaM2(steel))
    }

    @Test
    fun `ring of outer radius 50 and inner radius 20 has two contours`() {
        val shape = SheetShape.Ring(dec("50"), dec("20"))
        // π × (50² − 20²) = 2100 × π = 6597.344572538565… mm².
        assertDecimalRounded("6597.344572538566", shape.areaMm2)
        // 2 × π × (50 + 20) = 140 × π = 439.822971502571… mm, both contours.
        assertDecimalRounded("439.822971502571", shape.cutLengthMm)
        assertEquals(2, shape.pierces)
        // 6597.34… × 2 = 13 194.69… mm³; × 8000 = 0.10555751316062 kg.
        assertDecimalRounded("0.105557513160617", sheet(shape).massKg(steel))
        // 2 × 6597.34… + 439.82… × 2 = 14 074.335088082… mm² = 0.014074335088082 m².
        assertDecimalRounded("0.014074335088082", sheet(shape).paintAreaM2(steel))
    }

    @Test
    fun `free shape takes the area and the perimeter as typed`() {
        val shape = SheetShape.Free(areaMm2 = dec("12000"), perimeterMm = dec("500"))
        assertDecimal("12000", shape.areaMm2)
        assertDecimal("500", shape.cutLengthMm)
        assertEquals(1, shape.pierces)
        // 12 000 × 2 = 24 000 mm³ = 0.000024 m³; × 8000 = 0.192 kg.
        assertDecimal("0.192", sheet(shape).massKg(steel))
        // 2 × 12 000 + 500 × 2 = 25 000 mm² = 0.025 m².
        assertDecimal("0.025", sheet(shape).paintAreaM2(steel))
    }

    @Test
    fun `a sheet set by weight has the mass as typed, the area from the weight and the perimeter as its edge`() {
        val byMass = Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12"), dec("3000")))
        assertDecimal("12", byMass.massKg(steel))
        // One face: 12 / (0.003 m × 8000 kg/m³) = 12 / 24 = 0.5 m²; both faces 1; the edge 3000 × 3 = 9000 mm² = 0.009 m².
        assertDecimal("1.009", byMass.paintAreaM2(steel))
        assertDecimal("3000", byMass.cutLengthMm)
        assertEquals(1, byMass.pierces)
    }

    @Test
    fun `a sheet set by weight without a perimeter has no edge and no cut length`() {
        val byMass = Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12")))
        assertDecimal("12", byMass.massKg(steel))
        assertDecimal("1", byMass.paintAreaM2(steel))
        assertEquals(null, byMass.cutLengthMm)
        assertEquals(1, byMass.pierces)
    }

    @Test
    fun `the density turns the weight into an area, a heavier grade means a smaller area`() {
        val byMass = Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12")))
        // 12 / (0.003 × 4000) = 1 m² per face, so 2 m² in all, twice the area of steel.
        assertDecimal("2", byMass.paintAreaM2(dec("4000")))
    }

    @Test
    fun `a quotient that does not terminate is kept to 34 digits, not rounded to a scale`() {
        val byMass = Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("1")))
        // 1 / (0.003 × 7850) = 1 / 23.55 = 0.0424628450106157…; both faces 0.0849256900212314… m².
        assertDecimalRounded("0.084925690021231", byMass.paintAreaM2(dec("7850")))
        assertEquals(34, byMass.paintAreaM2(dec("7850")).precision())
    }

    @Test
    fun `the outline shapes give their own cut length and pierces to the sheet`() {
        assertDecimal("1000", sheet(SheetShape.Rectangle(dec("300"), dec("200"))).cutLengthMm)
        assertEquals(1, sheet(SheetShape.Rectangle(dec("300"), dec("200"))).pierces)
        assertEquals(2, sheet(SheetShape.Ring(dec("50"), dec("20"))).pierces)
        assertDecimal("500", sheet(SheetShape.Free(dec("12000"), dec("500"))).cutLengthMm)
    }

    @Test
    fun `thicker sheet gets more edge area and more mass`() {
        // Circle R = 50 at t = 4: mass doubles to 0.2513274122871834…; paint 2 × 7853.98… + 314.159… × 4 = 16 964.6… mm².
        val circle = Material.Sheet("Ст3", dec("4"), SheetShape.Circle(dec("50")))
        assertDecimalRounded("0.251327412287183", circle.massKg(steel))
        assertDecimalRounded("0.016964600329385", circle.paintAreaM2(steel))
    }
}
