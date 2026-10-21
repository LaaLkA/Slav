package io.github.laalka.slav.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

private val TWO = BigDecimal(2)
private val FOUR = BigDecimal(4)

/**
 * What a part is made of (spec 4.3, 5): rolled kind, grade (by name) and part dimensions in mm.
 * Sizes come from the sortament; angle, channel and I-beam carry a copy of their sortament row,
 * so a saved calculation keeps working after the row is edited or deleted.
 */
@Serializable
sealed interface Material {
    val grade: String

    /** Thickness used to look up prices: sheet t, tube wall t, angle / channel / I-beam s. */
    val thicknessMm: BigDecimal

    /** Painted surface by the part's overall dimensions (spec 6.7). */
    val paintAreaM2: BigDecimal

    /** Mass by the part's overall dimensions (spec 5). Angle, channel and I-beam use the GOST mass of one metre. */
    fun massKg(densityKgM3: BigDecimal): BigDecimal

    @Serializable
    @SerialName("sheet")
    data class Sheet(
        override val grade: String,
        override val thicknessMm: Decimal,
        val lengthMm: Decimal,
        val widthMm: Decimal,
    ) : Material {
        // All six faces, as in the spreadsheet: 2 × (L × W + W × t + L × t).
        override val paintAreaM2: BigDecimal
            get() = (TWO * (lengthMm * widthMm + widthMm * thicknessMm + lengthMm * thicknessMm)).mm2ToM2()

        override fun massKg(densityKgM3: BigDecimal) = (lengthMm * widthMm * thicknessMm).mm3ToM3() * densityKgM3
    }

    /** Tubes and sections: the kinds the tube laser cuts (spec 6.3). */
    @Serializable
    sealed interface LongProduct : Material {
        val lengthMm: BigDecimal

        /** Length of one tube-laser cut: the section perimeter, corner radii ignored (spec 6.3). */
        val sectionPerimeterMm: BigDecimal

        /** The dimension compared with the tube-laser limit (spec 6.1). */
        val maxSectionMm: BigDecimal

        // Outer surface: round π × D × L, profile 2 × (A + B) × L, sections perimeter × L.
        override val paintAreaM2: BigDecimal
            get() = (sectionPerimeterMm * lengthMm).mm2ToM2()
    }

    @Serializable
    @SerialName("round_tube")
    data class RoundTube(
        override val grade: String,
        val diameterMm: Decimal,
        val wallMm: Decimal,
        override val lengthMm: Decimal,
    ) : LongProduct {
        override val thicknessMm: BigDecimal get() = wallMm
        override val sectionPerimeterMm: BigDecimal get() = PI * diameterMm
        override val maxSectionMm: BigDecimal get() = diameterMm

        override fun massKg(densityKgM3: BigDecimal) =
            (PI * (diameterMm - wallMm) * wallMm * lengthMm).mm3ToM3() * densityKgM3
    }

    @Serializable
    @SerialName("profile_tube")
    data class ProfileTube(
        override val grade: String,
        val sideAMm: Decimal,
        val sideBMm: Decimal,
        val wallMm: Decimal,
        override val lengthMm: Decimal,
    ) : LongProduct {
        override val thicknessMm: BigDecimal get() = wallMm
        override val sectionPerimeterMm: BigDecimal get() = TWO * (sideAMm + sideBMm)
        override val maxSectionMm: BigDecimal get() = maxOf(sideAMm, sideBMm)

        override fun massKg(densityKgM3: BigDecimal) =
            ((TWO * (sideAMm + sideBMm) - FOUR * wallMm) * wallMm * lengthMm).mm3ToM3() * densityKgM3
    }

    @Serializable
    @SerialName("angle")
    data class Angle(override val grade: String, val size: AngleSize, override val lengthMm: Decimal) : LongProduct {
        override val thicknessMm: BigDecimal get() = size.thicknessMm
        override val sectionPerimeterMm: BigDecimal get() = TWO * (size.flangeAMm + size.flangeBMm)
        override val maxSectionMm: BigDecimal get() = maxOf(size.flangeAMm, size.flangeBMm)

        override fun massKg(densityKgM3: BigDecimal) = size.massPerMeterKg * lengthMm.mmToM()
    }

    @Serializable
    @SerialName("channel")
    data class Channel(override val grade: String, val size: BeamSize, override val lengthMm: Decimal) : LongProduct {
        override val thicknessMm: BigDecimal get() = size.thicknessMm
        override val sectionPerimeterMm: BigDecimal get() = size.sectionPerimeterMm()
        override val maxSectionMm: BigDecimal get() = maxOf(size.heightMm, size.flangeWidthMm)

        override fun massKg(densityKgM3: BigDecimal) = size.massPerMeterKg * lengthMm.mmToM()
    }

    @Serializable
    @SerialName("i_beam")
    data class IBeam(override val grade: String, val size: BeamSize, override val lengthMm: Decimal) : LongProduct {
        override val thicknessMm: BigDecimal get() = size.thicknessMm
        override val sectionPerimeterMm: BigDecimal get() = size.sectionPerimeterMm()
        override val maxSectionMm: BigDecimal get() = maxOf(size.heightMm, size.flangeWidthMm)

        override fun massKg(densityKgM3: BigDecimal) = size.massPerMeterKg * lengthMm.mmToM()
    }
}

/** 2 × h + 4 × b − 2 × s (spec 6.3). */
private fun BeamSize.sectionPerimeterMm() = TWO * heightMm + FOUR * flangeWidthMm - TWO * thicknessMm
