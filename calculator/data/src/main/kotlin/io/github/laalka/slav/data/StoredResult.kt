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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

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
    val massKg: Decimal,
    val material: StoredMaterialLine,
    val operations: List<StoredLine>,
) {
    fun toPart() = PartResult(
        name = name,
        massKg = massKg,
        material = MaterialLine(material.cost, material.manualExtra),
        operations = operations.map(StoredLine::toLine),
    )

    companion object {
        fun of(part: PartResult) = StoredPart(
            name = part.name,
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

/**
 * The result JSON of any program version as [StoredResult] reads it. Results saved by 1.0.0-beta.1
 * list a part once with its quantity per product; a part is now one piece, so such a part is listed
 * that many times, as the upgraded input lists it, and the unit price and mass add up to what they
 * were. Lines the current model no longer has on a part (welding, painting) stay: the result keeps
 * the price it was calculated for until the calculation is calculated again.
 */
internal fun upgradeResultJson(result: JsonElement): JsonElement {
    val root = result as? JsonObject ?: return result
    val parts = root["parts"]?.jsonArray ?: return root
    return JsonObject(root + ("parts" to JsonArray(parts.flatMap(::upgradeStoredPart))))
}

private fun upgradeStoredPart(part: JsonElement): List<JsonElement> {
    val root = part as? JsonObject ?: return listOf(part)
    val copies = (root[LEGACY_PART_QUANTITY] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: 1
    val upgraded = JsonObject(root - LEGACY_PART_QUANTITY)
    return List(copies) { upgraded }
}
