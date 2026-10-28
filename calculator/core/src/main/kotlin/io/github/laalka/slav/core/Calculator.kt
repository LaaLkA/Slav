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
            OperationLine(op.kind, lineCost(priceProductOperation(op, input.parts, errors), op.manualExtra), op.manualExtra)
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
        // The mass from the drawing, when given, is the mass everywhere: shown, summed and priced.
        val massKg = part.massKg(grade.densityKgM3)
        return PartResult(
            name = part.name,
            massKg = massKg,
            material = MaterialLine(lineCost(priceMaterial(material.thicknessMm, massKg, grade, priceBook, errors), part.materialExtra), part.materialExtra),
            operations = operations.map { op ->
                OperationLine(op.kind, lineCost(pricePartOperation(op, material, grade.group, errors), op.manualExtra), op.manualExtra)
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
        }

    private fun priceProductOperation(op: ProductOperation, parts: List<Part>, errors: Errors): Money =
        when (op) {
            is ProductOperation.AssemblyWelding ->
                priceWelding(op.weldType, op.seamLengthMm, op.group, op.thicknessMm, priceBook, errors)
            // The product area is Σ area by the parts' dimensions (spec 6.7), never by their mass from the drawing.
            is ProductOperation.Painting -> pricePainting(
                op.areaOverrideM2 ?: parts.paintAreaM2(priceBook.general.minPaintAreaPerPartM2),
                priceBook,
                errors,
            )
        }
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
    /** Σ part cost + Σ product lines. */
    val unitPrice: Money = parts.map { it.cost }.sum() + productLines.map { it.cost }.sum()

    /** Σ part mass. */
    val unitMassKg: BigDecimal = parts.sumOf { it.massKg }

    /** The run total; the run size is changed on the card without recalculating (spec 8.2). */
    fun total(quantity: Int): Money = unitPrice * quantity
}

/** One part, one piece of the product; [massKg] is the override from the drawing when it was given. */
data class PartResult(
    val name: String,
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
