package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.Decimal
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.MaterialLine
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.OperationLine
import io.github.laalka.slav.core.PartResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Settings and calculations are stored in the JSON form of the core model. */
internal val storageJson: Json = Json

// The stored form of CalcResult. The core result is not serializable: its unit price, mass and
// part costs are derived from the lines, so only the lines are stored and the rest is rebuilt.

@Serializable
internal data class StoredResult(val parts: List<StoredPart>, val productLines: List<StoredLine>) {
    fun toResult() = CalcResult(parts.map(StoredPart::toPart), productLines.map(StoredLine::toLine))

    companion object {
        fun of(result: CalcResult) = StoredResult(result.parts.map(StoredPart::of), result.productLines.map(StoredLine::of))
    }
}

@Serializable
internal data class StoredPart(
    val name: String,
    val qtyPerProduct: Int,
    val massKg: Decimal,
    val material: StoredMaterialLine,
    val operations: List<StoredLine>,
) {
    fun toPart() = PartResult(
        name = name,
        qtyPerProduct = qtyPerProduct,
        massKg = massKg,
        material = MaterialLine(material.cost, material.manualExtra),
        operations = operations.map(StoredLine::toLine),
    )

    companion object {
        fun of(part: PartResult) = StoredPart(
            name = part.name,
            qtyPerProduct = part.qtyPerProduct,
            massKg = part.massKg,
            material = StoredMaterialLine(part.material.cost, part.material.manualExtra),
            operations = part.operations.map(StoredLine::of),
        )
    }
}

@Serializable
internal data class StoredMaterialLine(val cost: Money, val manualExtra: ManualExtra?)

@Serializable
internal data class StoredLine(val kind: OperationKind, val cost: Money, val manualExtra: ManualExtra?) {
    fun toLine() = OperationLine(kind, cost, manualExtra)

    companion object {
        fun of(line: OperationLine) = StoredLine(line.kind, line.cost, line.manualExtra)
    }
}
