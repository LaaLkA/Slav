package io.github.laalka.slav.core

import java.math.BigDecimal

// One function per operation of spec 6.2–6.7, plus the material line of spec 5. Each returns the
// exact formula value; the calculator adds the manual extra and rounds the line. A missing price
// is recorded in Errors and counts as zero, so one pass finds every missing cell. Lengths are in
// mm and the cut and weld prices in ₽/cm, hence mmToCm (an exact shift, never `/`).

private val TWO = BigDecimal(2)

/** Spec 5: mass × price per kg (grade, thickness); [massKg] is the part's, from the drawing or the dimensions. */
internal fun priceMaterial(thicknessMm: BigDecimal, massKg: BigDecimal, grade: Grade, book: PriceBook, errors: Errors): Money {
    val perKg = errors.price(
        book.materialPricesPerKg.priceFor(grade.name, thicknessMm), PriceTable.MaterialPerKg, grade.name, thicknessMm.toHeader(),
    )
    return Money(massKg * perKg)
}

/** Spec 3, 10: a line is its formula value plus the manual extra, rounded half-up to kopecks. */
internal fun lineCost(cost: Money, extra: ManualExtra?): Money = (if (extra == null) cost else cost + extra.amount).rounded()

/** Spec 6.2: L / 10 × cut(group, t) + pierces × pierce(group, t); a shape cut takes L and the pierces from the shape. */
internal fun priceLaserSheet(
    op: PartOperation.LaserSheet,
    sheet: Material.Sheet,
    group: String,
    book: PriceBook,
    errors: Errors,
): Money {
    val (lengthMm, pierces) = when (val mode = op.mode) {
        SheetCutMode.ByShape -> sheet.shape.cutLengthMm to sheet.shape.pierces
        is SheetCutMode.ContourLength -> mode.lengthMm to mode.pierces
    }
    val t = sheet.thicknessMm
    val cut = errors.groupPrice(book.laserSheetCutPerCm, PriceTable.LaserSheetCut, group, t)
    val pierce = errors.groupPrice(book.laserSheetPiercePerPiece, PriceTable.LaserSheetPierce, group, t)
    return Money(lengthMm.mmToCm() * cut + pierces.toBigDecimal() * pierce)
}

/** Spec 6.3: cuts × section perimeter / 10 × cut(group, t). An end cut needs no pierce. */
internal fun priceTubeLaser(
    op: PartOperation.TubeLaser,
    product: Material.LongProduct,
    group: String,
    book: PriceBook,
    errors: Errors,
): Money {
    val cut = errors.groupPrice(book.tubeLaserCutPerCm, PriceTable.TubeLaserCut, group, product.thicknessMm)
    return Money(op.cuts.toBigDecimal() * product.sectionPerimeterMm.mmToCm() * cut)
}

/**
 * Spec 6.4: Σ count × k × (π × d / 10 × cut(group, t) + pierce(group, t)), k = 2 through both
 * walls. A sheet is priced by the sheet tables, every other kind by the tube-laser tables. Both
 * walls exist only in a tube, so the flag doubles nothing on a sheet or a section.
 */
internal fun priceHoles(op: PartOperation.Holes, material: Material, group: String, book: PriceBook, errors: Errors): Money {
    val t = material.thicknessMm
    val (cut, pierce) = when (material) {
        is Material.Sheet ->
            errors.groupPrice(book.laserSheetCutPerCm, PriceTable.LaserSheetCut, group, t) to
                errors.groupPrice(book.laserSheetPiercePerPiece, PriceTable.LaserSheetPierce, group, t)
        is Material.LongProduct ->
            errors.groupPrice(book.tubeLaserCutPerCm, PriceTable.TubeLaserCut, group, t) to
                errors.groupPrice(book.tubeLaserPiercePerPiece, PriceTable.TubeLaserPierce, group, t)
    }
    val hasTwoWalls = material is Material.RoundTube || material is Material.ProfileTube
    return Money(
        op.rows.sumOf { row ->
            val walls = if (row.throughBothWalls && hasTwoWalls) TWO else BigDecimal.ONE
            row.count.toBigDecimal() * walls * (PI * row.diameterMm.mmToCm() * cut + pierce)
        },
    )
}

/** Spec 6.5: Σ count × bend(group, t, range(length)); a length on a bound belongs to the lower range. */
internal fun priceBending(op: PartOperation.Bending, sheet: Material.Sheet, group: String, book: PriceBook, errors: Errors): Money {
    val t = sheet.thicknessMm
    return Money(
        op.rows.sumOf { row ->
            val range = book.general.bendRangeFor(row.lengthMm)
            val perBend = errors.price(
                book.bendingPerBend.priceFor(group, t, range), PriceTable.Bending(group), t.toHeader(), range.label,
            )
            row.count.toBigDecimal() * perBend
        },
    )
}

/** Spec 6.6: seam length / 10 × weld(type, group, t), for the group and thickness chosen on the assembly. */
internal fun priceWelding(
    weldType: String,
    seamLengthMm: BigDecimal,
    group: String,
    thicknessMm: BigDecimal,
    book: PriceBook,
    errors: Errors,
): Money {
    val perCm = errors.price(
        book.weldingPerCm.priceFor(weldType, group, thicknessMm), PriceTable.Welding(weldType), group, thicknessMm.toHeader(),
    )
    return Money(seamLengthMm.mmToCm() * perCm)
}

/** Spec 6.7: area × price per m². */
internal fun pricePainting(areaM2: BigDecimal, book: PriceBook, errors: Errors): Money {
    val perM2 = errors.price(book.paintingPerM2, PriceTable.Painting, row = null, column = null)
    return Money(areaM2 * perM2)
}

private fun Errors.groupPrice(table: List<GroupPrice>, id: PriceTable, group: String, thicknessMm: BigDecimal) =
    price(table.priceFor(group, thicknessMm), id, group, thicknessMm.toHeader())
