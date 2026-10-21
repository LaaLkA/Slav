package io.github.laalka.slav.core

enum class Applicability {
    APPLICABLE,

    /** Not offered for this rolled kind; the form hides the operation. */
    NOT_APPLICABLE,

    /** Tube-laser operation on a section above the limit; the form shows it disabled with a hint. */
    SECTION_TOO_LARGE,
}

/** The matrix of spec 6.1, including the tube-laser section limit (spec 4.6). */
fun applicability(kind: OperationKind, material: Material, priceBook: PriceBook): Applicability {
    fun onTubeLaser(product: Material.LongProduct) =
        if (product.maxSectionMm <= priceBook.general.tubeLaserMaxSectionMm) Applicability.APPLICABLE
        else Applicability.SECTION_TOO_LARGE

    return when (kind) {
        OperationKind.LASER_SHEET, OperationKind.BENDING ->
            if (material is Material.Sheet) Applicability.APPLICABLE else Applicability.NOT_APPLICABLE
        OperationKind.TUBE_LASER -> when (material) {
            is Material.Sheet -> Applicability.NOT_APPLICABLE
            is Material.LongProduct -> onTubeLaser(material)
        }
        OperationKind.HOLES -> when (material) {
            is Material.Sheet -> Applicability.APPLICABLE
            is Material.LongProduct -> onTubeLaser(material)
        }
        OperationKind.WELDING, OperationKind.PAINTING -> Applicability.APPLICABLE
    }
}

fun isApplicable(kind: OperationKind, material: Material, priceBook: PriceBook): Boolean =
    applicability(kind, material, priceBook) == Applicability.APPLICABLE
