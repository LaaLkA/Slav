package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.editor.kind
import io.github.laalka.slav.app.editor.title
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.OperationLine
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** One line of the breakdown, the material or an operation; [cost] includes [extra] (spec 3). */
data class BreakdownLine(val title: String, val extra: ManualExtra?, val cost: Money)

/** A part priced once: [cost] and [massKg] are for one piece. */
data class BreakdownPart(
    val name: String,
    val qtyPerProduct: Int,
    val massKg: BigDecimal,
    /** The material line first, then the operations in the order they were entered. */
    val lines: List<BreakdownLine>,
    val cost: Money,
) {
    val costPerProduct: Money get() = cost * qtyPerProduct
}

/**
 * A priced calculation as the card and the exports show it (spec 8.2, 8.5). Every amount is a
 * line of the stored result or a sum of them, so the breakdown adds up to the unit price exactly.
 */
data class Breakdown(
    val name: String,
    val status: CalculationStatus,
    val quantity: Int,
    /** The day the price snapshot was taken, the date the unit price is valid for. */
    val snapshotDate: LocalDate,
    val parts: List<BreakdownPart>,
    val productLines: List<BreakdownLine>,
    val unitPrice: Money,
    val unitMassKg: BigDecimal,
) {
    val total: Money get() = unitPrice * quantity

    val runMassKg: BigDecimal get() = unitMassKg * quantity.toBigDecimal()
}

/** The breakdown of a priced calculation; null for a draft, which has no price (spec 7.2). */
fun StoredCalculation.breakdown(zone: ZoneId): Breakdown? {
    val result = result ?: return null
    val snapshotAt = checkNotNull(snapshotAt) { "Priced calculation $id has no snapshot date" }
    return Breakdown(
        name = name,
        status = status,
        quantity = quantity,
        snapshotDate = snapshotAt.atZone(zone).toLocalDate(),
        // A successful calculation prices every part, so the result follows the input part by part.
        parts = result.parts.mapIndexed { index, part ->
            val input = input.parts.getOrNull(index)
            BreakdownPart(
                name = part.name,
                qtyPerProduct = part.qtyPerProduct,
                massKg = part.massKg,
                lines = listOf(
                    BreakdownLine(input?.material?.let { "Материал: ${it.description()}" } ?: "Материал", part.material.manualExtra, part.material.cost),
                ) + part.operations.map { BreakdownLine(it.partTitle(input), it.manualExtra, it.cost) },
                cost = part.cost,
            )
        },
        productLines = result.productLines.map { line ->
            BreakdownLine(line.productTitle(input.productOperations), line.manualExtra, line.cost)
        },
        unitPrice = result.unitPrice,
        unitMassKg = result.unitMassKg,
    )
}

/** "Лист Ст3, 2 мм, 1000 × 1000 мм": the rolled kind, the grade, the size and the part's dimensions. */
fun Material.description(): String {
    val size = when (this) {
        is Material.Sheet -> "${thicknessMm.toInputText()} мм, ${lengthMm.toInputText()} × ${widthMm.toInputText()} мм"
        is Material.RoundTube -> "${diameterMm.toInputText()} × ${wallMm.toInputText()}, длина ${lengthMm.toInputText()} мм"
        is Material.ProfileTube ->
            "${sideAMm.toInputText()} × ${sideBMm.toInputText()} × ${wallMm.toInputText()}, длина ${lengthMm.toInputText()} мм"
        is Material.Angle -> "${size.designation}, длина ${lengthMm.toInputText()} мм"
        is Material.Channel -> "${size.designation}, длина ${lengthMm.toInputText()} мм"
        is Material.IBeam -> "${size.designation}, длина ${lengthMm.toInputText()} мм"
    }
    return "${kind.title} $grade, $size"
}

/** The checklist title; welding names its type, a part has at most one operation of each kind. */
private fun OperationLine.partTitle(part: Part?): String {
    val welding = part?.operations?.firstOrNull { it.kind == kind } as? PartOperation.Welding
    return if (welding != null) "${kind.title} (${welding.weldType})" else kind.title
}

private fun OperationLine.productTitle(operations: List<ProductOperation>): String = when (kind) {
    OperationKind.WELDING -> {
        val welding = operations.firstOrNull { it.kind == kind } as? ProductOperation.AssemblyWelding
        if (welding != null) "Сварка сборки (${welding.weldType})" else "Сварка сборки"
    }
    OperationKind.PAINTING -> "Покраска изделия"
    OperationKind.LASER_SHEET, OperationKind.TUBE_LASER, OperationKind.HOLES, OperationKind.BENDING -> kind.title
}
