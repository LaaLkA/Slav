package io.github.laalka.slav.core

import io.github.laalka.slav.core.Fixtures.angle
import io.github.laalka.slav.core.Fixtures.channel
import io.github.laalka.slav.core.Fixtures.iBeam
import io.github.laalka.slav.core.Fixtures.profileTube
import io.github.laalka.slav.core.Fixtures.roundTube
import io.github.laalka.slav.core.Fixtures.sheet
import kotlin.test.Test

class MaterialTest {
    private val steel = dec("7850")

    @Test
    fun `sheet mass matches the spreadsheet formula`() {
        val sheet = rectangleSheet("1", "1000", "1000")
        // 1000 × 1000 × 1 = 1 000 000 mm³ = 0.001 m³; × 7850 = 7.85 kg.
        assertDecimal("7.85", sheet.massKg(steel))
        // Spreadsheet: L × W × T × 0.00000785.
        assertDecimal((dec("1000") * dec("1000") * dec("1") * dec("0.00000785")).toPlainString(), sheet.massKg(steel))
    }

    @Test
    fun `sheet mass uses all three dimensions and the density`() {
        val steelSheet = rectangleSheet("3", "1250", "2500")
        // 1250 × 2500 × 3 = 9 375 000 mm³ = 0.009375 m³; × 7850 = 73.59375 kg.
        assertDecimal("73.59375", steelSheet.massKg(steel))
        val aluminium = rectangleSheet("4", "1500", "750").copy(grade = "АМг3")
        // 1500 × 750 × 4 = 4 500 000 mm³ = 0.0045 m³; × 2700 = 12.15 kg.
        assertDecimal("12.15", aluminium.massKg(dec("2700")))
    }

    @Test
    fun `round tube mass`() {
        // 57 × 3, 1000 mm: π × (57 − 3) × 3 × 1000 = π × 162 000 mm³ = π × 0.000162 m³;
        // × 7850 = π × 1.2717 = 3.995163377570… kg (GOST 8732 table: 3.99 kg/m).
        assertDecimalRounded("3.995163377570", roundTube.massKg(steel))
    }

    @Test
    fun `profile tube mass`() {
        // 40 × 20 × 2, 6000 mm: (2 × (40 + 20) − 4 × 2) × 2 = 112 × 2 = 224 mm²;
        // × 6000 = 1 344 000 mm³ = 0.001344 m³; × 7850 = 10.5504 kg.
        assertDecimal("10.5504", profileTube.massKg(steel))
    }

    @Test
    fun `angle mass is mass per metre times length`() {
        // 50×50×5: 3.77 kg/m × 2.5 m = 9.425 kg; the density does not matter.
        assertDecimal("9.425", angle.massKg(steel))
        assertDecimal("9.425", angle.massKg(dec("2700")))
    }

    @Test
    fun `channel mass is mass per metre times length`() {
        // 10П: 8.59 kg/m × 1.2 m = 10.308 kg.
        assertDecimal("10.308", channel.massKg(steel))
    }

    @Test
    fun `I-beam mass is mass per metre times length`() {
        // 20: 21.0 kg/m × 3.5 m = 73.5 kg.
        assertDecimal("73.5", iBeam.massKg(steel))
    }

    @Test
    fun `thickness for price lookup per kind`() {
        assertDecimal("2", sheet.thicknessMm)
        assertDecimal("3", roundTube.thicknessMm)
        assertDecimal("2", profileTube.thicknessMm)
        assertDecimal("5", angle.thicknessMm)
        assertDecimal("4.5", channel.thicknessMm)
        assertDecimal("5.2", iBeam.thicknessMm)
    }

    @Test
    fun `section perimeters`() {
        // π × 57 = 179.070781254618…
        assertDecimalRounded("179.070781254618", roundTube.sectionPerimeterMm)
        // 2 × (40 + 20) = 120.
        assertDecimal("120", profileTube.sectionPerimeterMm)
        // 2 × (50 + 50) = 200.
        assertDecimal("200", angle.sectionPerimeterMm)
        // 2 × 100 + 4 × 46 − 2 × 4.5 = 200 + 184 − 9 = 375.
        assertDecimal("375", channel.sectionPerimeterMm)
        // 2 × 200 + 4 × 100 − 2 × 5.2 = 400 + 400 − 10.4 = 789.6.
        assertDecimal("789.6", iBeam.sectionPerimeterMm)
    }

    @Test
    fun `paint areas`() {
        // 2 × (1000 × 500 + 500 × 2 + 1000 × 2) = 2 × 503 000 = 1 006 000 mm² = 1.006 m².
        assertDecimal("1.006", sheet.paintAreaM2)
        // π × 57 × 1000 = 179 070.78… mm² = 0.179070781255 m².
        assertDecimalRounded("0.179070781255", roundTube.paintAreaM2)
        // 2 × (40 + 20) × 6000 = 720 000 mm² = 0.72 m².
        assertDecimal("0.72", profileTube.paintAreaM2)
        // 200 × 2500 = 500 000 mm² = 0.5 m².
        assertDecimal("0.5", angle.paintAreaM2)
        // 375 × 1200 = 450 000 mm² = 0.45 m².
        assertDecimal("0.45", channel.paintAreaM2)
        // 789.6 × 3500 = 2 763 600 mm² = 2.7636 m².
        assertDecimal("2.7636", iBeam.paintAreaM2)
    }

    @Test
    fun `max section for the tube laser limit`() {
        assertDecimal("57", roundTube.maxSectionMm)
        assertDecimal("40", profileTube.maxSectionMm)
        assertDecimal("40", profileTube.copy(sideAMm = dec("20"), sideBMm = dec("40")).maxSectionMm)
        assertDecimal("63", Material.Angle("Ст3", AngleSize("63×40×5", dec("40"), dec("63"), dec("5"), dec("3.91")), dec("1")).maxSectionMm)
        assertDecimal("100", channel.maxSectionMm)
        // Wide-flange beam: flange wider than the height.
        val wide = BeamSize("20К1", dec("195"), dec("200"), dec("6.5"), dec("41.5"))
        assertDecimal("200", Material.IBeam("Ст3", wide, dec("1")).maxSectionMm)
    }
}
