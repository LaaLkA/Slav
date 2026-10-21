package io.github.laalka.slav.core

import java.math.BigDecimal

/**
 * Prices a [CalculationInput] against one [PriceBook] (spec 3, 10). It reads nothing else, so the
 * book decides which prices apply: recalculating after an edit passes the calculation's snapshot,
 * «Пересчитать по текущим ценам» passes the current settings (spec 8.3).
 */
class Calculator(private val priceBook: PriceBook) {
    fun calculate(input: CalculationInput): CalcOutcome {
        val errors = Errors()
        val parts = input.parts.mapNotNull { pricePart(it, errors) }
        val productLines = input.productOperations.map { op ->
            OperationLine(op.kind, line(priceProductOperation(op, input.parts, errors), op.manualExtra), op.manualExtra)
        }
        val found = errors.all
        return if (found.isEmpty()) CalcOutcome.Ok(CalcResult(parts, productLines)) else CalcOutcome.Failed(found)
    }

    /** Null when the grade is unknown; the error is recorded and the outcome is Failed. */
    private fun pricePart(part: Part, errors: Errors): PartResult? {
        val material = part.material
        val grade = priceBook.grades.firstOrNull { it.name == material.grade }
        if (grade == null) errors += CalcError.UnknownGrade(material.grade)
        val operations = part.operations.filter { checkApplicable(it.kind, material, errors) }
        // Without the grade there is no group to look operation prices up by, nor density for the mass.
        if (grade == null) return null
        return PartResult(
            name = part.name,
            qtyPerProduct = part.qtyPerProduct,
            massKg = material.massKg(grade.densityKgM3),
            material = MaterialLine(line(priceMaterial(material, grade, priceBook, errors), part.materialExtra), part.materialExtra),
            operations = operations.map { op ->
                OperationLine(op.kind, line(pricePartOperation(op, material, grade.group, errors), op.manualExtra), op.manualExtra)
            },
        )
    }

    private fun checkApplicable(kind: OperationKind, material: Material, errors: Errors): Boolean =
        when (applicability(kind, material, priceBook)) {
            Applicability.APPLICABLE -> true
            Applicability.NOT_APPLICABLE -> false.also { errors += CalcError.NotApplicable(kind, material) }
            Applicability.SECTION_TOO_LARGE -> false.also {
                errors += CalcError.SectionTooLarge(kind, material, priceBook.general.tubeLaserMaxSectionMm)
            }
        }

    // The casts hold because the operation passed the applicability matrix of spec 6.1.
    private fun pricePartOperation(op: PartOperation, material: Material, group: String, errors: Errors): Money =
        when (op) {
            is PartOperation.LaserSheet -> priceLaserSheet(op, material as Material.Sheet, group, priceBook, errors)
            is PartOperation.TubeLaser -> priceTubeLaser(op, material as Material.LongProduct, group, priceBook, errors)
            is PartOperation.Holes -> priceHoles(op, material, group, priceBook, errors)
            is PartOperation.Bending -> priceBending(op, material as Material.Sheet, group, priceBook, errors)
            is PartOperation.Welding -> priceWelding(op.weldType, op.rows, group, material.thicknessMm, priceBook, errors)
            is PartOperation.Painting -> pricePainting(op.areaOverrideM2 ?: material.paintAreaM2, priceBook, errors)
        }

    private fun priceProductOperation(op: ProductOperation, parts: List<Part>, errors: Errors): Money =
        when (op) {
            is ProductOperation.AssemblyWelding ->
                priceWelding(op.weldType, op.rows, op.group, op.thicknessMm, priceBook, errors)
            // The product area is Σ area by the parts' dimensions (spec 6.7), not their painting overrides.
            is ProductOperation.Painting -> pricePainting(
                op.areaOverrideM2 ?: parts.sumOf { it.material.paintAreaM2 * it.qtyPerProduct.toBigDecimal() },
                priceBook,
                errors,
            )
        }

    /** Spec 3, 10: a line is its formula value plus the manual extra, rounded half-up to kopecks. */
    private fun line(cost: Money, extra: ManualExtra?): Money = (if (extra == null) cost else cost + extra.amount).rounded()
}

sealed interface CalcOutcome {
    data class Ok(val result: CalcResult) : CalcOutcome

    /** Every error found, so the user can fill all missing prices at once (spec 7.3). */
    data class Failed(val errors: List<CalcError>) : CalcOutcome
}

/**
 * The breakdown of spec 8.2. Every line is already rounded, so part cost, unit price and total are
 * sums of rounded lines and agree with the breakdown to the kopeck (spec 10).
 */
data class CalcResult(val parts: List<PartResult>, val productLines: List<OperationLine>) {
    /** Σ (part cost × quantity per product) + Σ product lines. */
    val unitPrice: Money = parts.map { it.cost * it.qtyPerProduct }.sum() + productLines.map { it.cost }.sum()

    /** Σ (part mass × quantity per product). */
    val unitMassKg: BigDecimal = parts.sumOf { it.massKg * it.qtyPerProduct.toBigDecimal() }

    /** The run total; the run size is changed on the card without recalculating (spec 8.2). */
    fun total(quantity: Int): Money = unitPrice * quantity
}

/** One part, priced once; [cost] is for one piece. */
data class PartResult(
    val name: String,
    val qtyPerProduct: Int,
    val massKg: BigDecimal,
    val material: MaterialLine,
    /** In the order the operations were entered. */
    val operations: List<OperationLine>,
) {
    val cost: Money = material.cost + operations.map { it.cost }.sum()
}

/** The material line of a part; [cost] includes [manualExtra]. */
data class MaterialLine(val cost: Money, val manualExtra: ManualExtra?)

/** One operation line; [cost] includes [manualExtra]. */
data class OperationLine(val kind: OperationKind, val cost: Money, val manualExtra: ManualExtra?)
