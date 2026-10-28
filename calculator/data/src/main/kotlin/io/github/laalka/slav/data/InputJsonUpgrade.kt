package io.github.laalka.slav.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

// Calculations saved by 1.0.0-beta.1 hold the input as it was modelled then. The stored text is
// never rewritten: it is brought to the current model here, every time it is read, so a calculation
// the new program has not touched stays exactly as the old one saved it. A current input passes
// through unchanged, because every step looks for a form that only the old model wrote.
//
// What changed since beta.1:
//  - a part had a quantity per product; now every part is one piece, so a part with quantity n
//    becomes n identical parts and the price of the product stays the same;
//  - a sheet had lengthMm and widthMm; now it has a shape, and these two make a rectangle;
//  - the laser mode «rectangle» (cut the part's outline) is «by_shape»;
//  - welding and painting were operations of a part too; now they belong to the product only, so
//    the part's ones are dropped (the next calculation no longer charges them);
//  - assembly welding had rows of seams with a count; now it has one total seam length.

/** The field of beta.1 that held how many pieces of a part the product takes; inputs and results both have it. */
internal const val LEGACY_PART_QUANTITY = "qtyPerProduct"

/** The input JSON of any program version as the current [io.github.laalka.slav.core.CalculationInput] reads it. */
internal fun upgradeInputJson(input: JsonElement): JsonElement {
    val root = input as? JsonObject ?: return input
    return root
        .replacing("parts") { parts -> JsonArray(parts.jsonArray.flatMap(::upgradePart)) }
        .replacing("productOperations") { operations -> JsonArray(operations.jsonArray.map(::upgradeProductOperation)) }
}

/**
 * One part's JSON of any program version as the current [io.github.laalka.slav.core.Part] reads it,
 * for a part stored on its own (the library). A part is one piece, so a legacy quantity n, which
 * the input turns into n parts, leaves the part itself as it is.
 */
internal fun upgradePartJson(part: JsonElement): JsonElement = upgradePart(part).first()

private fun upgradePart(part: JsonElement): List<JsonElement> {
    val root = part as? JsonObject ?: return listOf(part)
    // A draft keeps an unfilled quantity as 0; such a part is still one part, not none.
    val copies = (root[LEGACY_PART_QUANTITY] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: 1
    val upgraded = JsonObject(root - LEGACY_PART_QUANTITY)
        .replacing("material", ::upgradeMaterial)
        .replacing("operations") { operations -> JsonArray(operations.jsonArray.mapNotNull(::upgradePartOperation)) }
    return List(copies) { upgraded }
}

private fun upgradeMaterial(material: JsonElement): JsonElement {
    val root = material as? JsonObject ?: return material
    val isOldSheet = root.type() == "sheet" && "shape" !in root && "lengthMm" in root && "widthMm" in root
    if (!isOldSheet) return root
    val rectangle = JsonObject(
        mapOf("type" to JsonPrimitive("rectangle"), "lengthMm" to root.getValue("lengthMm"), "widthMm" to root.getValue("widthMm")),
    )
    return JsonObject(root - "lengthMm" - "widthMm" + ("shape" to rectangle))
}

/** Null for an operation the current model does not have on a part. */
private fun upgradePartOperation(operation: JsonElement): JsonElement? {
    val root = operation as? JsonObject ?: return operation
    return when (root.type()) {
        "welding", "painting" -> null
        "laser_sheet" -> root.replacing("mode") { mode ->
            if ((mode as? JsonObject)?.type() == "rectangle") JsonObject(mapOf("type" to JsonPrimitive("by_shape"))) else mode
        }
        else -> root
    }
}

private fun upgradeProductOperation(operation: JsonElement): JsonElement {
    val root = operation as? JsonObject ?: return operation
    if (root.type() != "assembly_welding" || "rows" !in root || "seamLengthMm" in root) return root
    // Σ length × count of the seams: the same welded length the rows priced.
    val seamLengthMm = root.getValue("rows").jsonArray.fold(BigDecimal.ZERO) { total, row ->
        val seam = row.jsonObject
        total + BigDecimal(seam.getValue("lengthMm").jsonPrimitive.content) * BigDecimal(seam.getValue("count").jsonPrimitive.int)
    }
    return JsonObject(root - "rows" + ("seamLengthMm" to JsonPrimitive(seamLengthMm.stripTrailingZeros().toPlainString())))
}

private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.contentOrNull

/** This object with the value of [name], when it has one, replaced by [transform] of it. */
private fun JsonObject.replacing(name: String, transform: (JsonElement) -> JsonElement): JsonObject {
    val value = this[name] ?: return this
    return JsonObject(this + (name to transform(value)))
}
