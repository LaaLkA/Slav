package io.github.laalka.slav.core

import io.github.laalka.slav.core.Applicability.APPLICABLE
import io.github.laalka.slav.core.Applicability.NOT_APPLICABLE
import io.github.laalka.slav.core.Applicability.SECTION_TOO_LARGE
import io.github.laalka.slav.core.Fixtures.angle
import io.github.laalka.slav.core.Fixtures.channel
import io.github.laalka.slav.core.Fixtures.iBeam
import io.github.laalka.slav.core.Fixtures.profileTube
import io.github.laalka.slav.core.Fixtures.roundTube
import io.github.laalka.slav.core.Fixtures.sheet
import io.github.laalka.slav.core.OperationKind.BENDING
import io.github.laalka.slav.core.OperationKind.HOLES
import io.github.laalka.slav.core.OperationKind.LASER_SHEET
import io.github.laalka.slav.core.OperationKind.PAINTING
import io.github.laalka.slav.core.OperationKind.TUBE_LASER
import io.github.laalka.slav.core.OperationKind.WELDING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicabilityTest {
    private val book = PriceBook.default()

    @Test
    fun `matrix of spec 6_1 for sections within the tube laser limit`() {
        // Columns: sheet, round tube, profile tube, angle, channel, I-beam.
        val expected = mapOf(
            LASER_SHEET to listOf(true, false, false, false, false, false),
            TUBE_LASER to listOf(false, true, true, true, true, true),
            HOLES to listOf(true, true, true, true, true, true),
            BENDING to listOf(true, false, false, false, false, false),
            WELDING to listOf(true, true, true, true, true, true),
            PAINTING to listOf(true, true, true, true, true, true),
        )
        assertEquals(OperationKind.entries.toSet(), expected.keys)
        val materials = listOf(sheet, roundTube, profileTube, angle, channel, iBeam)
        for ((kind, row) in expected) {
            assertEquals(row, materials.map { isApplicable(kind, it, book) }, "row $kind")
        }
        assertEquals(NOT_APPLICABLE, applicability(TUBE_LASER, sheet, book))
        assertEquals(NOT_APPLICABLE, applicability(BENDING, roundTube, book))
    }

    @Test
    fun `220 mm tube is rejected for the tube laser but can still be welded and painted`() {
        val tube = Material.RoundTube("Ст3", diameterMm = dec("220"), wallMm = dec("6"), lengthMm = dec("1000"))
        assertEquals(SECTION_TOO_LARGE, applicability(TUBE_LASER, tube, book))
        assertEquals(SECTION_TOO_LARGE, applicability(HOLES, tube, book))
        assertFalse(isApplicable(TUBE_LASER, tube, book))
        assertFalse(isApplicable(HOLES, tube, book))
        assertTrue(isApplicable(WELDING, tube, book))
        assertTrue(isApplicable(PAINTING, tube, book))
        assertEquals(NOT_APPLICABLE, applicability(LASER_SHEET, tube, book))
    }

    @Test
    fun `limit is inclusive and uses the largest section dimension`() {
        val at = Material.RoundTube("Ст3", dec("200.0"), dec("6"), dec("1000"))
        assertEquals(APPLICABLE, applicability(TUBE_LASER, at, book))
        val wideProfile = Material.ProfileTube("Ст3", dec("100"), dec("220"), dec("5"), dec("1000"))
        assertEquals(SECTION_TOO_LARGE, applicability(TUBE_LASER, wideProfile, book))
        val bigAngle = Material.Angle("Ст3", AngleSize("250×250×20", dec("250"), dec("250"), dec("20"), dec("75.1")), dec("1000"))
        assertEquals(SECTION_TOO_LARGE, applicability(HOLES, bigAngle, book))
        val wideFlange = BeamSize("20К1", dec("195"), dec("200.5"), dec("6.5"), dec("41.5"))
        assertEquals(SECTION_TOO_LARGE, applicability(TUBE_LASER, Material.IBeam("Ст3", wideFlange, dec("1000")), book))
        val tallChannel = BeamSize("24П", dec("240"), dec("90"), dec("5.6"), dec("24.0"))
        assertEquals(SECTION_TOO_LARGE, applicability(TUBE_LASER, Material.Channel("Ст3", tallChannel, dec("1000")), book))
    }

    @Test
    fun `limit comes from the settings`() {
        val tube = Material.RoundTube("Ст3", dec("220"), dec("6"), dec("1000"))
        val bigger = book.copy(general = book.general.copy(tubeLaserMaxSectionMm = dec("250")))
        assertTrue(isApplicable(TUBE_LASER, tube, bigger))
    }
}
