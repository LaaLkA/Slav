package io.github.laalka.slav.app.developer

import io.github.laalka.slav.app.editor.AssemblyWeldingForm
import io.github.laalka.slav.app.editor.BendingForm
import io.github.laalka.slav.app.editor.CalculationForm
import io.github.laalka.slav.app.editor.CountRowForm
import io.github.laalka.slav.app.editor.CutMode
import io.github.laalka.slav.app.editor.HoleRowForm
import io.github.laalka.slav.app.editor.HolesForm
import io.github.laalka.slav.app.editor.LaserSheetForm
import io.github.laalka.slav.app.editor.MaterialForm
import io.github.laalka.slav.app.editor.PaintingForm
import io.github.laalka.slav.app.editor.PartForm
import io.github.laalka.slav.app.editor.PartOperationForm
import io.github.laalka.slav.app.editor.ProductOperationForm
import io.github.laalka.slav.app.editor.ShapeKind
import io.github.laalka.slav.app.editor.SizeChoice
import io.github.laalka.slav.app.editor.TubeLaserForm
import io.github.laalka.slav.app.editor.hasTwoWalls
import io.github.laalka.slav.app.editor.read
import io.github.laalka.slav.app.editor.sizeOptions
import io.github.laalka.slav.app.quick.QuickForm
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.allThicknesses
import io.github.laalka.slav.app.settings.sheetThicknesses
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.core.Applicability
import io.github.laalka.slav.core.BendRange
import io.github.laalka.slav.core.Grade
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.applicability
import io.github.laalka.slav.core.priceFor
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Developer mode: a random calculation that is valid on [book], as an editor holds it. Everything is
 * drawn from [random], so a seed repeats a calculation. Only what [book] can price is drawn: a grade
 * and a size are paired only where the material price exists, an operation is chosen only where core
 * [applicability] allows it for the material and its price cells exist, a bend length falls in a range
 * that has a price. The forms therefore read without errors and calculate Ok; a book that cannot price
 * something (no grades, no sizes) leaves that choice unmade for the editor to flag, nothing throws.
 */
class RandomCalculation(private val book: PriceBook, private val random: Random) {
    /** A grade and a size of the sortament with a material price: what a part of one kind can be made of. */
    private class Pairing(val grade: Grade, val size: SizeChoice, val thicknessMm: BigDecimal)

    private val pairings: Map<SortamentKind, List<Pairing>> = SortamentKind.entries
        .associateWith { kind ->
            book.sizeOptions(kind).flatMap { size ->
                val thickness = MaterialForm(kind, size = size).read().material.thicknessMm
                book.grades
                    .filter { book.materialPricesPerKg.priceFor(it.name, thickness) != null }
                    .map { Pairing(it, size, thickness) }
            }
        }
        .filterValues { it.isNotEmpty() }

    /**
     * 1–4 parts of random kinds, with operations on the parts and on the product, a discount of 0–15 %.
     * [newId] numbers the parts and the rows, as the editor does, so ids of later additions stay unique.
     */
    fun calculationForm(newId: () -> Long): CalculationForm {
        val parts = List(random.nextInt(1, MAX_PARTS + 1)) { part(newId, number = it + 1) }
        return CalculationForm(
            name = name(),
            counterparty = counterparty(),
            quantity = integer(1, 50),
            parts = parts,
            productOperations = productOperations(),
            discount = discount(),
        )
    }

    /** A sheet of a grade and thickness with a price, 0,5–10 m², 1–60 min of the laser, a counterparty, a discount. */
    fun quickForm(): QuickForm {
        val sheets = book.sortament.sheetThicknesses().flatMap { thickness ->
            book.grades.filter { book.materialPricesPerKg.priceFor(it.name, thickness) != null }.map { it to thickness }
        }
        val (grade, thickness) = sheets.randomOrNull(random) ?: (null to null)
        return QuickForm(
            name = name(),
            counterparty = counterparty(),
            grade = grade?.name,
            thicknessMm = thickness,
            sheetArea = tenths(5, 100),
            cutTime = integer(1, 60),
            discount = discount(),
        )
    }

    private fun part(newId: () -> Long, number: Int): PartForm {
        val kind = pairings.keys.randomOrNull(random)
        val pairing = kind?.let { pairings.getValue(it).random(random) }
        val material = if (kind == null || pairing == null) MaterialForm() else material(kind, pairing)
        return PartForm(
            id = newId(),
            name = "Деталь $number",
            material = material,
            operations = if (pairing == null) emptyMap() else operations(material, pairing, newId),
        )
    }

    private fun material(kind: SortamentKind, pairing: Pairing): MaterialForm {
        val chosen = MaterialForm(kind, pairing.grade.name, pairing.size)
        if (kind != SortamentKind.SHEET) return chosen.copy(length = integer(100, 6000))
        return when (val shape = ShapeKind.entries.random(random)) {
            ShapeKind.RECTANGLE -> chosen.copy(shape = shape, length = integer(100, 2000), width = integer(50, 1500))
            ShapeKind.CIRCLE -> chosen.copy(shape = shape, radius = integer(20, 300))
            ShapeKind.RING -> {
                val outer = random.nextInt(60, 301)
                chosen.copy(shape = shape, outerRadius = outer.toString(), innerRadius = random.nextInt(10, outer - 19).toString())
            }
            ShapeKind.FREE -> chosen.copy(shape = shape, area = integer(5_000, 500_000), perimeter = integer(300, 5_000))
            // The perimeter is optional, but the laser cut «По форме» needs it, so it is always typed.
            ShapeKind.BY_MASS -> chosen.copy(shape = shape, mass = tenths(1, 500), perimeter = integer(300, 5_000))
        }
    }

    /** Each operation is taken half of the time, and only where the material allows it and the book has its prices. */
    private fun operations(material: MaterialForm, pairing: Pairing, newId: () -> Long): Map<OperationKind, PartOperationForm> {
        val read = material.read().material
        val group = pairing.grade.group
        val thickness = pairing.thicknessMm
        val sheetLaser = book.laserSheetCutPerCm.priceFor(group, thickness) != null &&
            book.laserSheetPiercePerPiece.priceFor(group, thickness) != null
        val tubeCut = book.tubeLaserCutPerCm.priceFor(group, thickness) != null
        val tubeLaser = tubeCut && book.tubeLaserPiercePerPiece.priceFor(group, thickness) != null
        val bendRanges = book.general.bendRanges().filter { book.bendingPerBend.priceFor(group, thickness, it) != null }

        fun chosen(kind: OperationKind, priced: Boolean) =
            priced && applicability(kind, read, book) == Applicability.APPLICABLE && random.nextBoolean()

        val operations = mutableMapOf<OperationKind, PartOperationForm>()
        if (chosen(OperationKind.LASER_SHEET, sheetLaser)) operations[OperationKind.LASER_SHEET] = laserSheet()
        if (chosen(OperationKind.TUBE_LASER, tubeCut)) operations[OperationKind.TUBE_LASER] = TubeLaserForm(cuts = integer(1, 6))
        if (chosen(OperationKind.HOLES, if (read is Material.Sheet) sheetLaser else tubeLaser)) {
            operations[OperationKind.HOLES] = holes(material.kind, thickness, newId)
        }
        if (chosen(OperationKind.BENDING, bendRanges.isNotEmpty())) operations[OperationKind.BENDING] = bending(bendRanges, newId)
        return operations
    }

    private fun laserSheet() = when (random.nextBoolean()) {
        true -> LaserSheetForm(CutMode.BY_SHAPE)
        false -> LaserSheetForm(CutMode.CONTOUR, contourLength = integer(100, 10_000), pierces = integer(1, 10))
    }

    /** Holes at least as wide as the part is thick: a narrower one is a warning in the editor. */
    private fun holes(kind: SortamentKind, thickness: BigDecimal, newId: () -> Long): HolesForm {
        val narrowest = thickness.setScale(0, RoundingMode.UP).toInt() + 1
        return HolesForm(
            List(random.nextInt(1, MAX_ROWS + 1)) {
                HoleRowForm(
                    newId(),
                    diameter = integer(narrowest, maxOf(narrowest, 60)),
                    count = integer(1, 20),
                    throughBothWalls = kind.hasTwoWalls && random.nextBoolean(),
                )
            },
        )
    }

    private fun bending(ranges: List<BendRange>, newId: () -> Long) = BendingForm(
        List(random.nextInt(1, MAX_ROWS + 1)) { CountRowForm(newId(), length = lengthIn(ranges.random(random)), count = integer(1, 10)) },
    )

    /** A length of the range, which is open at its lower end and closed at its upper one; the last range is 500 mm wide. */
    private fun lengthIn(range: BendRange): String {
        val from = range.fromMm ?: BigDecimal.ZERO
        val to = range.toMm ?: (from + LAST_RANGE_WIDTH)
        val length = (from + (to - from) * random.nextDouble().toBigDecimal()).setScale(1, RoundingMode.DOWN)
        return (if (length > from) length else to).toInputText()
    }

    /** Welding of a type, group and thickness the settings have a price for, and painting where it has one. */
    private fun productOperations(): Map<OperationKind, ProductOperationForm> {
        val operations = mutableMapOf<OperationKind, ProductOperationForm>()
        val thicknesses = book.sortament.allThicknesses()
        val welds = book.weldingPerCm.mapNotNull { price ->
            val thickness = thicknesses.firstOrNull { it.compareTo(price.thicknessMm) == 0 }
            if (thickness != null && price.weldType in book.general.weldTypes && book.groups.any { it.name == price.group }) {
                Triple(price.weldType, price.group, thickness)
            } else {
                null
            }
        }
        val weld = welds.randomOrNull(random)
        if (weld != null && random.nextBoolean()) {
            operations[OperationKind.WELDING] = AssemblyWeldingForm(weld.first, weld.second, weld.third, seamLength = integer(50, 5_000))
        }
        if (book.paintingPerM2 != null && random.nextBoolean()) {
            operations[OperationKind.PAINTING] = PaintingForm(areaOverride = if (random.nextInt(3) == 0) tenths(1, 50) else "")
        }
        return operations
    }

    /** Blank (none) or a whole percent up to 15. */
    private fun discount() = if (random.nextBoolean()) "" else integer(1, 15)

    private fun name() = "Тест " + String(CharArray(NAME_LENGTH) { NAME_CHARS.random(random) })

    private fun counterparty() = COUNTERPARTIES.random(random)

    private fun integer(min: Int, max: Int) = random.nextInt(min, max + 1).toString()

    /** [min]–[max] in tenths, as typed: 5–100 is «0,5»–«10». */
    private fun tenths(min: Int, max: Int) = BigDecimal.valueOf(random.nextInt(min, max + 1).toLong(), 1).toInputText()

    private companion object {
        const val MAX_PARTS = 4
        const val MAX_ROWS = 3
        const val NAME_LENGTH = 6
        val NAME_CHARS = ('A'..'Z') + ('0'..'9')
        val LAST_RANGE_WIDTH = BigDecimal(500)
        val COUNTERPARTIES = listOf(
            "ООО «Металлснаб»",
            "ООО «СтальМонтаж»",
            "ООО «Уралпрофиль»",
            "ООО «Техносплав»",
            "ООО «Северный металл»",
            "ООО «ПромКонструкция»",
            "ООО «Прогресс-М»",
            "ООО «Сибмашкомплект»",
        )
    }
}
