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

    /** A sheet part: its flat [shape] cut from a sheet [thicknessMm] thick. */
    @Serializable
    @SerialName("sheet")
    data class Sheet(
        override val grade: String,
        override val thicknessMm: Decimal,
        val shape: SheetShape,
    ) : Material {
        // Both faces and the cut edges: 2 × area + cut length × t. For a rectangle this is the
        // spreadsheet's 2 × (L × W + W × t + L × t).
        override val paintAreaM2: BigDecimal
            get() = (TWO * shape.areaMm2 + shape.cutLengthMm * thicknessMm).mm2ToM2()

        override fun massKg(densityKgM3: BigDecimal) = (shape.areaMm2 * thicknessMm).mm3ToM3() * densityKgM3
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

/**
 * The flat outline of a sheet part, mm. [areaMm2] is the area of the part itself (holes of the
 * drawing are not subtracted from it, as before), [cutLengthMm] the length of all its contours and
 * [pierces] the number of contours, each of which the laser starts with a pierce.
 */
@Serializable
sealed interface SheetShape {
    val areaMm2: BigDecimal
    val cutLengthMm: BigDecimal
    val pierces: Int

    /** A rectangle or a strip: L × W. */
    @Serializable
    @SerialName("rectangle")
    data class Rectangle(val lengthMm: Decimal, val widthMm: Decimal) : SheetShape {
        override val areaMm2: BigDecimal get() = lengthMm * widthMm
        override val cutLengthMm: BigDecimal get() = TWO * (lengthMm + widthMm)
        override val pierces: Int get() = 1
    }

    /** A disc of radius R. */
    @Serializable
    @SerialName("circle")
    data class Circle(val radiusMm: Decimal) : SheetShape {
        override val areaMm2: BigDecimal get() = PI * radiusMm * radiusMm
        override val cutLengthMm: BigDecimal get() = TWO * PI * radiusMm
        override val pierces: Int get() = 1
    }

    /**
     * A disc of radius R with a concentric hole of radius r, where r < R. The editor refuses a
     * ring whose inner radius is not smaller; an unfilled draft holds zeros.
     */
    @Serializable
    @SerialName("ring")
    data class Ring(val outerRadiusMm: Decimal, val innerRadiusMm: Decimal) : SheetShape {
        override val areaMm2: BigDecimal get() = PI * (outerRadiusMm * outerRadiusMm - innerRadiusMm * innerRadiusMm)
        override val cutLengthMm: BigDecimal get() = TWO * PI * (outerRadiusMm + innerRadiusMm)
        override val pierces: Int get() = 2
    }

    /** Any other outline: the area and the total cut length of all contours are typed from the drawing. */
    @Serializable
    @SerialName("free")
    data class Free(override val areaMm2: Decimal, val perimeterMm: Decimal) : SheetShape {
        override val cutLengthMm: BigDecimal get() = perimeterMm
        override val pierces: Int get() = 1
    }
}
