package io.github.laalka.slav.core

import kotlinx.serialization.Serializable

/** Sizes the user picks from when entering a part (spec 4.3). All dimensions in mm. */
@Serializable
data class Sortament(
    val sheetThicknessesMm: List<Decimal>,
    val roundTubes: List<RoundTubeSize>,
    val profileTubes: List<ProfileTubeSize>,
    val angles: List<AngleSize>,
    val channels: List<BeamSize>,
    val iBeams: List<BeamSize>,
)

/** Round tube D × t. */
@Serializable
data class RoundTubeSize(val diameterMm: Decimal, val wallMm: Decimal)

/** Profile tube A × B × t. */
@Serializable
data class ProfileTubeSize(val sideAMm: Decimal, val sideBMm: Decimal, val wallMm: Decimal)

/** Angle: flanges a × b, thickness s, GOST mass of one metre. */
@Serializable
data class AngleSize(
    val designation: String,
    val flangeAMm: Decimal,
    val flangeBMm: Decimal,
    val thicknessMm: Decimal,
    val massPerMeterKg: Decimal,
)

/** Channel or I-beam (same sortament shape): height h, flange width b, web thickness s, GOST mass of one metre. */
@Serializable
data class BeamSize(
    val designation: String,
    val heightMm: Decimal,
    val flangeWidthMm: Decimal,
    val thicknessMm: Decimal,
    val massPerMeterKg: Decimal,
)
