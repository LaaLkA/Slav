package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.settings.NumberInput
import io.github.laalka.slav.app.settings.REQUIRED
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.readPositive
import io.github.laalka.slav.app.settings.readPrice
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BeamSize
import io.github.laalka.slav.core.BendRow
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.HoleRow
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.ProfileTubeSize
import io.github.laalka.slav.core.RoundTubeSize
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import java.math.BigDecimal

// A draft is stored as a CalculationInput (spec 7.2), which has no empty fields. Every required
// number is above zero and every required choice is a non-empty name, so an unfilled number is
// stored as 0, an unchosen grade, weld type or group as "", an unchosen size as zeros, and loading
// turns them back into empty fields. Text that is not a number is not kept.

/** Test tags of the editor. A field's tag is also the key of its error; parts and rows are tagged by position. */
object EditorTags {
    const val NAME = "editor/name"
    const val QUANTITY = "editor/quantity"
    const val PARTS = "editor/parts"
    const val ADD_PART = "editor/add-part"
    const val CALCULATE = "editor/calculate"
    const val BACK = "editor/back"
    const val ERRORS = "editor/errors"
    const val PRODUCT = "product"

    fun part(index: Int) = "part/$index"

    /** [owner] is a part's tag or [PRODUCT]. */
    fun operation(owner: String, kind: OperationKind) = "$owner/op/${kind.name}"

    fun materialLine(part: String) = "$part/material"

    /** The field of [part]'s [dimension]; the radio button of its sheet shape is "[part]/shape/<kind>". */
    fun dimension(part: String, dimension: DimensionField) = "$part/${dimension.tag}"

    fun shape(part: String, kind: ShapeKind) = "$part/shape/${kind.name}"

    fun row(line: String, index: Int) = "$line/rows/$index"
}

const val NOT_CHOSEN = "Выберите"
const val NOT_A_COUNT = "Целое число больше нуля"
const val NO_ROWS = "Добавьте хотя бы одну строку"
const val NO_PARTS = "Добавьте деталь"
const val INNER_NOT_SMALLER = "Внутренний радиус должен быть меньше наружного"

/**
 * [input] is always complete enough to be stored as a draft; it can be calculated only when
 * [errors] is empty. [errors] maps the tag of every field that stops the calculation to its message.
 */
class FormRead(val input: CalculationInput, val errors: Map<String, String>)

/** Reads the form in one pass, so the fields highlighted are exactly the ones that keep «Рассчитать» disabled (spec 7.1). */
fun CalculationForm.read(): FormRead {
    val reader = FormReader()
    val input = reader.calculation(this)
    return FormRead(input, reader.errors)
}

/**
 * A part's material as far as it is filled; an empty field holds the zero a draft stores for it.
 * [dimensionsComplete]: the size and the dimensions are filled, so the area and the section are known.
 */
class MaterialRead(val material: Material, val gradeChosen: Boolean, val dimensionsComplete: Boolean) {
    val complete: Boolean get() = gradeChosen && dimensionsComplete
}

fun MaterialForm.read(): MaterialRead {
    val reader = FormReader()
    val material = reader.material(tag = "", form = this)
    return MaterialRead(material, gradeChosen = grade != null, dimensionsComplete = reader.errors.keys.all { it == "/grade" })
}

private class FormReader {
    val errors = linkedMapOf<String, String>()

    fun calculation(form: CalculationForm): CalculationInput {
        if (form.parts.isEmpty()) errors[EditorTags.PARTS] = NO_PARTS
        return CalculationInput(
            name = text(EditorTags.NAME, form.name),
            quantity = count(EditorTags.QUANTITY, form.quantity),
            parts = form.parts.mapIndexed { index, part -> part(EditorTags.part(index), part) },
            productOperations = OperationKind.entries.mapNotNull { kind ->
                form.productOperations[kind]?.let { productOperation(EditorTags.operation(EditorTags.PRODUCT, kind), it) }
            },
        )
    }

    private fun part(tag: String, form: PartForm) = Part(
        name = text("$tag/name", form.name),
        material = material(tag, form.material),
        operations = OperationKind.entries.mapNotNull { kind ->
            form.operations[kind]?.let { partOperation(EditorTags.operation(tag, kind), it) }
        },
        materialExtra = extra(EditorTags.materialLine(tag), form.materialExtra),
        massOverrideKg = optionalPositive("$tag/mass-override", form.massOverride),
    )

    fun material(tag: String, form: MaterialForm): Material {
        val grade = choice("$tag/grade", form.grade).orEmpty()
        val size = choice("$tag/size", form.size)
        // Only the dimensions of the sheet's shape are read, so a hidden field never blocks the calculation.
        fun dimension(field: DimensionField) = positive(EditorTags.dimension(tag, field), form.dimension(field))
        return when (form.kind) {
            SortamentKind.SHEET -> Material.Sheet(
                grade,
                thicknessMm = (size as? SizeChoice.Sheet)?.thicknessMm ?: BigDecimal.ZERO,
                shape = when (form.shape) {
                    ShapeKind.RECTANGLE -> SheetShape.Rectangle(dimension(DimensionField.LENGTH), dimension(DimensionField.WIDTH))
                    ShapeKind.CIRCLE -> SheetShape.Circle(dimension(DimensionField.RADIUS))
                    ShapeKind.RING -> ring(tag, dimension(DimensionField.OUTER_RADIUS), dimension(DimensionField.INNER_RADIUS))
                    ShapeKind.FREE -> SheetShape.Free(dimension(DimensionField.AREA), dimension(DimensionField.PERIMETER))
                },
            )
            SortamentKind.ROUND_TUBE -> {
                val length = dimension(DimensionField.LENGTH)
                val tube = (size as? SizeChoice.RoundTube)?.size ?: UNSET_ROUND_TUBE
                Material.RoundTube(grade, tube.diameterMm, tube.wallMm, length)
            }
            SortamentKind.PROFILE_TUBE -> {
                val length = dimension(DimensionField.LENGTH)
                val tube = (size as? SizeChoice.ProfileTube)?.size ?: UNSET_PROFILE_TUBE
                Material.ProfileTube(grade, tube.sideAMm, tube.sideBMm, tube.wallMm, length)
            }
            SortamentKind.ANGLE -> Material.Angle(grade, (size as? SizeChoice.Angle)?.size ?: UNSET_ANGLE, dimension(DimensionField.LENGTH))
            SortamentKind.CHANNEL -> Material.Channel(grade, (size as? SizeChoice.Beam)?.size ?: UNSET_BEAM, dimension(DimensionField.LENGTH))
            SortamentKind.I_BEAM -> Material.IBeam(grade, (size as? SizeChoice.Beam)?.size ?: UNSET_BEAM, dimension(DimensionField.LENGTH))
        }
    }

    /** A ring needs its inner radius smaller than the outer one; the error sits on the inner field. */
    private fun ring(tag: String, outer: BigDecimal, inner: BigDecimal): SheetShape.Ring {
        val innerTag = EditorTags.dimension(tag, DimensionField.INNER_RADIUS)
        if (outer.signum() > 0 && inner.signum() > 0 && inner >= outer && innerTag !in errors) errors[innerTag] = INNER_NOT_SMALLER
        return SheetShape.Ring(outer, inner)
    }

    private fun partOperation(tag: String, form: PartOperationForm): PartOperation = when (form) {
        is LaserSheetForm -> PartOperation.LaserSheet(
            mode = when (form.mode) {
                CutMode.BY_SHAPE -> SheetCutMode.ByShape
                CutMode.CONTOUR -> SheetCutMode.ContourLength(
                    lengthMm = positive("$tag/contour-length", form.contourLength),
                    pierces = count("$tag/pierces", form.pierces),
                )
            },
            manualExtra = extra(tag, form.extra),
        )
        is TubeLaserForm -> PartOperation.TubeLaser(count("$tag/cuts", form.cuts), extra(tag, form.extra))
        is HolesForm -> PartOperation.Holes(
            rows = rows(tag, form.rows) { rowTag, row ->
                HoleRow(positive("$rowTag/diameter", row.diameter), count("$rowTag/count", row.count), row.throughBothWalls)
            },
            manualExtra = extra(tag, form.extra),
        )
        is BendingForm -> PartOperation.Bending(
            rows = rows(tag, form.rows) { rowTag, row -> BendRow(positive("$rowTag/length", row.length), count("$rowTag/count", row.count)) },
            manualExtra = extra(tag, form.extra),
        )
    }

    private fun productOperation(tag: String, form: ProductOperationForm): ProductOperation = when (form) {
        is AssemblyWeldingForm -> ProductOperation.AssemblyWelding(
            weldType = choice("$tag/weld-type", form.weldType).orEmpty(),
            group = choice("$tag/group", form.group).orEmpty(),
            thicknessMm = choice("$tag/thickness", form.thicknessMm) ?: BigDecimal.ZERO,
            seamLengthMm = positive("$tag/seam-length", form.seamLength),
            manualExtra = extra(tag, form.extra),
        )
        is PaintingForm -> ProductOperation.Painting(optionalPositive("$tag/area", form.areaOverride), extra(tag, form.extra))
    }

    private fun <R, T> rows(tag: String, rows: List<R>, read: (String, R) -> T): List<T> {
        if (rows.isEmpty()) errors["$tag/rows"] = NO_ROWS
        return rows.mapIndexed { index, row -> read(EditorTags.row(tag, index), row) }
    }

    private fun text(tag: String, text: String): String = text.trim().also { if (it.isEmpty()) errors[tag] = REQUIRED }

    private fun count(tag: String, text: String): Int {
        if (text.isBlank()) {
            errors[tag] = REQUIRED
            return 0
        }
        val count = text.trim().toIntOrNull()?.takeIf { it > 0 }
        if (count == null) errors[tag] = NOT_A_COUNT
        return count ?: 0
    }

    private fun positive(tag: String, text: String): BigDecimal {
        val input = readPositive(text)
        if (input is NumberInput.Valid) return input.value
        errors[tag] = if (input is NumberInput.Invalid) input.message else REQUIRED
        return BigDecimal.ZERO
    }

    /** Blank is allowed and means "not set". */
    private fun optionalPositive(tag: String, text: String): BigDecimal? = when (val input = readPositive(text)) {
        NumberInput.Empty -> null
        is NumberInput.Valid -> input.value
        is NumberInput.Invalid -> null.also { errors[tag] = input.message }
    }

    private fun <T> choice(tag: String, value: T?): T? = value.also { if (it == null) errors[tag] = NOT_CHOSEN }

    /** Optional; a comment without an amount is kept with a zero amount. */
    private fun extra(lineTag: String, form: ExtraForm): ManualExtra? {
        val comment = form.comment.trim()
        val amount = when (val input = readPrice(form.amount)) {
            NumberInput.Empty -> null
            is NumberInput.Valid -> input.value
            is NumberInput.Invalid -> null.also { errors["$lineTag/extra/amount"] = input.message }
        }
        return when {
            amount != null -> ManualExtra(Money(amount), comment)
            comment.isNotEmpty() -> ManualExtra(Money.ZERO, comment)
            else -> null
        }
    }
}

private val UNSET_ROUND_TUBE = RoundTubeSize(BigDecimal.ZERO, BigDecimal.ZERO)
private val UNSET_PROFILE_TUBE = ProfileTubeSize(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
private val UNSET_ANGLE = AngleSize("", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
private val UNSET_BEAM = BeamSize("", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

/** The form of a stored input, drafts included; [newId] numbers the parts and rows. */
fun CalculationInput.toForm(newId: () -> Long): CalculationForm = CalculationForm(
    name = name,
    quantity = countText(quantity),
    parts = parts.map { it.toForm(newId) },
    productOperations = productOperations.associate { op ->
        op.kind to when (op) {
            is ProductOperation.AssemblyWelding -> AssemblyWeldingForm(
                weldType = op.weldType.ifEmpty { null },
                group = op.group.ifEmpty { null },
                thicknessMm = op.thicknessMm.takeIf { it.signum() > 0 },
                seamLength = positiveText(op.seamLengthMm),
                extra = op.manualExtra.toForm(),
            )
            is ProductOperation.Painting -> PaintingForm(op.areaOverrideM2?.let(::positiveText).orEmpty(), op.manualExtra.toForm())
        }
    },
)

/** The form of a stored part, for a part taken from the library; [newId] numbers the part and its rows. */
internal fun Part.toForm(newId: () -> Long) = PartForm(
    id = newId(),
    name = name,
    material = material.toForm(),
    massOverride = massOverrideKg?.let(::positiveText).orEmpty(),
    materialExtra = materialExtra.toForm(),
    operations = operations.associate { op ->
        op.kind to when (op) {
            is PartOperation.LaserSheet -> when (val mode = op.mode) {
                SheetCutMode.ByShape -> LaserSheetForm(CutMode.BY_SHAPE, extra = op.manualExtra.toForm())
                is SheetCutMode.ContourLength ->
                    LaserSheetForm(CutMode.CONTOUR, positiveText(mode.lengthMm), countText(mode.pierces), op.manualExtra.toForm())
            }
            is PartOperation.TubeLaser -> TubeLaserForm(countText(op.cuts), op.manualExtra.toForm())
            is PartOperation.Holes -> HolesForm(
                op.rows.map { HoleRowForm(newId(), positiveText(it.diameterMm), countText(it.count), it.throughBothWalls) },
                op.manualExtra.toForm(),
            )
            is PartOperation.Bending -> BendingForm(
                op.rows.map { CountRowForm(newId(), positiveText(it.lengthMm), countText(it.count)) },
                op.manualExtra.toForm(),
            )
        }
    },
)

private fun Material.toForm(): MaterialForm {
    val grade = grade.ifEmpty { null }
    return when (this) {
        is Material.Sheet -> {
            val sheet = MaterialForm(SortamentKind.SHEET, grade, thicknessMm.takeIf { it.signum() > 0 }?.let(SizeChoice::Sheet))
            when (val outline = shape) {
                is SheetShape.Rectangle ->
                    sheet.copy(shape = ShapeKind.RECTANGLE, length = positiveText(outline.lengthMm), width = positiveText(outline.widthMm))
                is SheetShape.Circle -> sheet.copy(shape = ShapeKind.CIRCLE, radius = positiveText(outline.radiusMm))
                is SheetShape.Ring -> sheet.copy(
                    shape = ShapeKind.RING, outerRadius = positiveText(outline.outerRadiusMm), innerRadius = positiveText(outline.innerRadiusMm),
                )
                is SheetShape.Free -> sheet.copy(shape = ShapeKind.FREE, area = positiveText(outline.areaMm2), perimeter = positiveText(outline.perimeterMm))
            }
        }
        is Material.RoundTube -> MaterialForm(
            SortamentKind.ROUND_TUBE, grade,
            RoundTubeSize(diameterMm, wallMm).takeUnless { it == UNSET_ROUND_TUBE }?.let(SizeChoice::RoundTube),
            positiveText(lengthMm),
        )
        is Material.ProfileTube -> MaterialForm(
            SortamentKind.PROFILE_TUBE, grade,
            ProfileTubeSize(sideAMm, sideBMm, wallMm).takeUnless { it == UNSET_PROFILE_TUBE }?.let(SizeChoice::ProfileTube),
            positiveText(lengthMm),
        )
        is Material.Angle -> MaterialForm(SortamentKind.ANGLE, grade, size.takeUnless { it == UNSET_ANGLE }?.let(SizeChoice::Angle), positiveText(lengthMm))
        is Material.Channel -> MaterialForm(SortamentKind.CHANNEL, grade, size.takeUnless { it == UNSET_BEAM }?.let(SizeChoice::Beam), positiveText(lengthMm))
        is Material.IBeam -> MaterialForm(SortamentKind.I_BEAM, grade, size.takeUnless { it == UNSET_BEAM }?.let(SizeChoice::Beam), positiveText(lengthMm))
    }
}

private fun ManualExtra?.toForm() = if (this == null) ExtraForm() else ExtraForm(amount.amount.toInputText(), comment)

private fun positiveText(value: BigDecimal) = if (value.signum() > 0) value.toInputText() else ""

private fun countText(value: Int) = if (value > 0) value.toString() else ""
