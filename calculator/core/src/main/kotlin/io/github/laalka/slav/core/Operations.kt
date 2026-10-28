package io.github.laalka.slav.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Operation inputs of spec 6. Grade, group and thickness are not entered for part operations:
// they come from the part material. Welding and painting belong to the assembled product only.
// Lengths are in mm. Defaults are always encoded, so stored inputs keep their meaning if a
// default changes later.

/** Operations of spec 6.1. */
enum class OperationKind { LASER_SHEET, TUBE_LASER, HOLES, BENDING, WELDING, PAINTING }

/** «Доп. операции, ₽»: an amount the manager adds to a line by hand (spec 3). */
@Serializable
data class ManualExtra(val amount: Money, val comment: String)

/** An operation on one part. */
@Serializable
sealed interface PartOperation {
    val kind: OperationKind
    val manualExtra: ManualExtra?

    /** Laser cutting of a sheet (spec 6.2). */
    @Serializable
    @SerialName("laser_sheet")
    data class LaserSheet(
        val mode: SheetCutMode,
        @EncodeDefault override val manualExtra: ManualExtra? = null,
    ) : PartOperation {
        override val kind get() = OperationKind.LASER_SHEET
    }

    /** End cuts on the tube laser; one cut is the section perimeter (spec 6.3). */
    @Serializable
    @SerialName("tube_laser")
    data class TubeLaser(
        @EncodeDefault val cuts: Int = 2,
        @EncodeDefault override val manualExtra: ManualExtra? = null,
    ) : PartOperation {
        override val kind get() = OperationKind.TUBE_LASER
    }

    /** Round holes (spec 6.4). Depth is the material thickness and is not stored. */
    @Serializable
    @SerialName("holes")
    data class Holes(
        val rows: List<HoleRow>,
        @EncodeDefault override val manualExtra: ManualExtra? = null,
    ) : PartOperation {
        override val kind get() = OperationKind.HOLES
    }

    /** Bending (spec 6.5). */
    @Serializable
    @SerialName("bending")
    data class Bending(
        val rows: List<BendRow>,
        @EncodeDefault override val manualExtra: ManualExtra? = null,
    ) : PartOperation {
        override val kind get() = OperationKind.BENDING
    }
}

/** An operation on the assembled product. */
@Serializable
sealed interface ProductOperation {
    val kind: OperationKind
    val manualExtra: ManualExtra?

    /**
     * Welding of the assembly (spec 6.6), the only welding there is: there is no single material,
     * so group and thickness are chosen. [seamLengthMm] is the total length of all seams.
     */
    @Serializable
    @SerialName("assembly_welding")
    data class AssemblyWelding(
        val weldType: String,
        val group: String,
        val thicknessMm: Decimal,
        val seamLengthMm: Decimal,
        @EncodeDefault override val manualExtra: ManualExtra? = null,
    ) : ProductOperation {
        override val kind get() = OperationKind.WELDING
    }

    /** Product painting (spec 6.7), the only painting there is; the area is Σ part areas (each at least the minimum) unless overridden. */
    @Serializable
    @SerialName("product_painting")
    data class Painting(
        @EncodeDefault val areaOverrideM2: Decimal? = null,
        @EncodeDefault override val manualExtra: ManualExtra? = null,
    ) : ProductOperation {
        override val kind get() = OperationKind.PAINTING
    }
}

/** How the sheet cut length is obtained (spec 6.2). */
@Serializable
sealed interface SheetCutMode {
    /** Cut length and pierces of the sheet's [SheetShape]: all its contours, one pierce each. */
    @Serializable
    @SerialName("by_shape")
    data object ByShape : SheetCutMode

    /** Total cut length of all contours from the drawing; round holes go to [PartOperation.Holes]. */
    @Serializable
    @SerialName("contour_length")
    data class ContourLength(val lengthMm: Decimal, @EncodeDefault val pierces: Int = 1) : SheetCutMode
}

/** [throughBothWalls] applies to tubes only: the hole goes through the tube and counts twice. */
@Serializable
data class HoleRow(
    val diameterMm: Decimal,
    @EncodeDefault val count: Int = 1,
    @EncodeDefault val throughBothWalls: Boolean = false,
)

@Serializable
data class BendRow(val lengthMm: Decimal, val count: Int)
