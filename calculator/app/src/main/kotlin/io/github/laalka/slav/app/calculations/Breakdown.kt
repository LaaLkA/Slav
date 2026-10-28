package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.editor.kind
import io.github.laalka.slav.app.editor.title
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.OperationLine
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.CalculationStatus
import io.github.laalka.slav.data.StoredCalculation
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** One line of the breakdown, the material or an operation; [cost] includes [extra] (spec 3). */
data class BreakdownLine(val title: String, val extra: ManualExtra?, val cost: Money)

/** One part, one piece of the product; [massKg] is the one from the drawing when it was given. */
data class BreakdownPart(
    val name: String,
    val massKg: BigDecimal,
    /** The material line first, then the operations in the order they were entered. */
    val lines: List<BreakdownLine>,
    val cost: Money,
)

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
    /**
     * The lines of the product itself: its assembly operations, or, for a simplified calculation,
     * which has no parts, its material and its cut.
     */
    val productLines: List<BreakdownLine>,
    val unitPrice: Money,
    val unitMassKg: BigDecimal,
) {
    val total: Money get() = unitPrice * quantity

    val runMassKg: BigDecimal get() = unitMassKg * quantity.toBigDecimal()
}

/** The breakdown of a priced calculation of either kind; null for a draft, which has no price (spec 7.2). */
fun StoredCalculation.breakdown(zone: ZoneId): Breakdown? = when (this) {
    is StoredCalculation.Full -> fullBreakdown(zone)
    is StoredCalculation.Quick -> quickBreakdown(zone)
}

/** The two lines of a simplified calculation: the sheet and the laser time. */
private fun StoredCalculation.Quick.quickBreakdown(zone: ZoneId): Breakdown? {
    val result = result ?: return null
    val snapshotAt = checkNotNull(snapshotAt) { "Priced calculation $id has no snapshot date" }
    return Breakdown(
        name = name,
        status = status,
        quantity = quantity,
        snapshotDate = snapshotAt.atZone(zone).toLocalDate(),
        parts = emptyList(),
        productLines = listOf(
            BreakdownLine(QUICK_MATERIAL_TITLE, result.material.manualExtra, result.material.cost),
            BreakdownLine(QUICK_CUT_TITLE, result.cut.manualExtra, result.cut.cost),
        ),
        unitPrice = result.unitPrice,
        unitMassKg = result.massKg,
    )
}

const val QUICK_MATERIAL_TITLE = "Материал"
const val QUICK_CUT_TITLE = "Резка (время)"

private fun StoredCalculation.Full.fullBreakdown(zone: ZoneId): Breakdown? {
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
                massKg = part.massKg,
                lines = listOf(
                    BreakdownLine(input?.material?.let { "Материал: ${it.description()}" } ?: "Материал", part.material.manualExtra, part.material.cost),
                ) + part.operations.map { BreakdownLine(it.kind.title, it.manualExtra, it.cost) },
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
        is Material.Sheet -> "${thicknessMm.toInputText()} мм, ${shape.description()}"
        is Material.RoundTube -> "${diameterMm.toInputText()} × ${wallMm.toInputText()}, длина ${lengthMm.toInputText()} мм"
        is Material.ProfileTube ->
            "${sideAMm.toInputText()} × ${sideBMm.toInputText()} × ${wallMm.toInputText()}, длина ${lengthMm.toInputText()} мм"
        is Material.Angle -> "${size.designation}, длина ${lengthMm.toInputText()} мм"
        is Material.Channel -> "${size.designation}, длина ${lengthMm.toInputText()} мм"
        is Material.IBeam -> "${size.designation}, длина ${lengthMm.toInputText()} мм"
    }
    return "${kind.title} $grade, $size"
}

private fun SheetShape.description(): String = when (this) {
    is SheetShape.Rectangle -> "${lengthMm.toInputText()} × ${widthMm.toInputText()} мм"
    is SheetShape.Circle -> "круг R ${radiusMm.toInputText()} мм"
    is SheetShape.Ring -> "кольцо R ${outerRadiusMm.toInputText()} / r ${innerRadiusMm.toInputText()} мм"
    is SheetShape.Free -> "произвольная форма, площадь ${areaMm2.toInputText()} мм², периметр ${perimeterMm.toInputText()} мм"
}

private fun OperationLine.productTitle(operations: List<ProductOperation>): String = when (kind) {
    OperationKind.WELDING -> {
        val welding = operations.firstOrNull { it.kind == kind } as? ProductOperation.AssemblyWelding
        if (welding != null) "Сварка сборки (${welding.weldType})" else "Сварка сборки"
    }
    OperationKind.PAINTING -> "Покраска изделия"
    OperationKind.LASER_SHEET, OperationKind.TUBE_LASER, OperationKind.HOLES, OperationKind.BENDING -> kind.title
}
