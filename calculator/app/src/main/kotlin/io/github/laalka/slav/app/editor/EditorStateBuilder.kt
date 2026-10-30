package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.settings.NumberInput
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.allThicknesses
import io.github.laalka.slav.app.settings.parseDecimal
import io.github.laalka.slav.app.settings.readPositive
import io.github.laalka.slav.app.settings.sheetThicknesses
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.app.ui.formatMeasure
import io.github.laalka.slav.core.Applicability
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.core.applicability
import io.github.laalka.slav.core.paintAreaM2

/** The label a size is shown with in the picker and in the part. */
fun SizeChoice.label(): String = when (this) {
    is SizeChoice.Sheet -> thicknessMm.toInputText()
    is SizeChoice.RoundTube -> "${size.diameterMm.toInputText()} × ${size.wallMm.toInputText()}"
    is SizeChoice.ProfileTube -> "${size.sideAMm.toInputText()} × ${size.sideBMm.toInputText()} × ${size.wallMm.toInputText()}"
    is SizeChoice.Angle -> size.designation
    is SizeChoice.Beam -> size.designation
}

/** The sizes of [kind] in the sortament of [book], the only ones a part can take (spec 5). */
fun PriceBook.sizeOptions(kind: SortamentKind): List<SizeChoice> = when (kind) {
    SortamentKind.SHEET -> sortament.sheetThicknesses().map(SizeChoice::Sheet)
    SortamentKind.ROUND_TUBE -> sortament.roundTubes.map(SizeChoice::RoundTube)
    SortamentKind.PROFILE_TUBE -> sortament.profileTubes.map(SizeChoice::ProfileTube)
    SortamentKind.ANGLE -> sortament.angles.map(SizeChoice::Angle)
    SortamentKind.CHANNEL -> sortament.channels.map(SizeChoice::Beam)
    SortamentKind.I_BEAM -> sortament.iBeams.map(SizeChoice::Beam)
}

private fun SortamentKind.sizeLabel(): String = when (this) {
    SortamentKind.SHEET -> "Толщина t, мм"
    SortamentKind.ROUND_TUBE -> "D × t, мм"
    SortamentKind.PROFILE_TUBE -> "A × B × t, мм"
    SortamentKind.ANGLE, SortamentKind.CHANNEL, SortamentKind.I_BEAM -> "Типоразмер"
}

/** Tubes: a hole can go through both of their walls (spec 6.4). */
val SortamentKind.hasTwoWalls: Boolean get() = this == SortamentKind.ROUND_TUBE || this == SortamentKind.PROFILE_TUBE

/** What the screen draws for [form]: [read]'s errors highlight the fields, [book] supplies the choices. */
internal fun editorState(
    form: CalculationForm,
    read: FormRead,
    book: PriceBook,
    isNew: Boolean,
    priceSource: String,
    calcErrors: List<ReadableError>,
    calcErrorsHint: String?,
): EditorState {
    val builder = StateBuilder(book, read.errors)
    return EditorState(
        isNew = isNew,
        priceSource = priceSource,
        currency = book.general.currencySymbol,
        name = builder.field(EditorTags.NAME, form.name),
        quantity = builder.field(EditorTags.QUANTITY, form.quantity),
        parts = form.parts.mapIndexed(builder::part),
        partsError = read.errors[EditorTags.PARTS],
        productOperations = listOf(OperationKind.WELDING, OperationKind.PAINTING).map { builder.productOperation(form, it) },
        missingCount = read.errors.size,
        calcErrors = calcErrors,
        calcErrorsHint = calcErrorsHint.takeIf { calcErrors.isNotEmpty() },
    )
}

private class StateBuilder(private val book: PriceBook, private val errors: Map<String, String>) {
    fun field(tag: String, text: String) = FieldView(tag, text, errors[tag])

    private fun choice(tag: String, label: String, selected: String?, options: List<String>, emptyHint: String) =
        ChoiceView(tag, label, selected, options, errors[tag]?.let { if (options.isEmpty()) emptyHint else it })

    private fun extra(lineTag: String, form: ExtraForm) =
        ExtraView(field("$lineTag/extra/amount", form.amount), field("$lineTag/extra/comment", form.comment))

    fun part(index: Int, form: PartForm): PartView {
        val tag = EditorTags.part(index)
        val kind = form.material.kind
        val material = form.material.read()
        val grade = book.grades.firstOrNull { it.name == form.material.grade }
        val sizes = book.sizeOptions(kind)
        // The mass from the drawing, once it is a valid number, is the mass of the part whatever the dimensions say.
        val drawingMass = (readPositive(form.massOverride) as? NumberInput.Valid)?.value
        // The weight of a sheet set by weight is the mass once it is a valid number, whatever the grade says.
        val weightMass = ((material.material as? Material.Sheet)?.shape as? SheetShape.ByMass)?.massKg?.takeIf { it.signum() > 0 }
        val computedMass = if (material.complete && grade != null) material.material.massKg(grade.densityKgM3) else null
        return PartView(
            id = form.id,
            tag = tag,
            title = "Деталь ${index + 1}",
            name = field("$tag/name", form.name),
            kind = kind,
            grade = choice("$tag/grade", "Марка", form.material.grade, book.grades.map { it.name }, NO_GRADES),
            size = choice("$tag/size", kind.sizeLabel(), form.material.size?.label(), sizes.map { it.label() }, NO_SIZES),
            sizeOptions = sizes,
            shape = form.material.shape.takeIf { kind == SortamentKind.SHEET },
            dimensions = form.material.dimensions.map { DimensionView(it, field(EditorTags.dimension(tag, it), form.material.dimension(it))) },
            massOverride = field("$tag/mass-override", form.massOverride),
            massOverrideShown = kind != SortamentKind.SHEET || form.massOverride.isNotBlank(),
            mass = (drawingMass ?: weightMass ?: computedMass)?.let { "${formatMeasure(it)} кг" },
            massFromDrawing = drawingMass != null,
            massByWeight = drawingMass == null && weightMass != null,
            materialExtra = extra(EditorTags.materialLine(tag), form.materialExtra),
            operations = OperationKind.entries.mapNotNull { partOperation(tag, form, it, material) },
        )
    }

    /** Null for an operation the rolled kind does not have: it is not shown at all (spec 6.1). */
    private fun partOperation(partTag: String, part: PartForm, kind: OperationKind, material: MaterialRead): OperationView? {
        val unavailable = when (applicability(kind, material.material, book)) {
            Applicability.NOT_APPLICABLE -> return null
            Applicability.SECTION_TOO_LARGE -> "сечение больше ${book.general.tubeLaserMaxSectionMm.toInputText()} мм"
            Applicability.APPLICABLE -> null
        }
        val tag = EditorTags.operation(partTag, kind)
        val form = part.operations[kind]?.takeIf { unavailable == null }
        return OperationView(
            ref = LineRef.OfPart(part.id, kind),
            tag = tag,
            title = kind.title,
            checked = form != null,
            unavailableHint = unavailable,
            params = form?.let { partParams(tag, it, part.material, material) },
            extra = form?.let { extra(tag, it.extra) },
        )
    }

    private fun partParams(tag: String, form: PartOperationForm, materialForm: MaterialForm, material: MaterialRead): OperationParams {
        val sizeChosen = materialForm.size != null
        return when (form) {
            is LaserSheetForm -> OperationParams.LaserSheet(
                form.mode, field("$tag/contour-length", form.contourLength), field("$tag/pierces", form.pierces), "$tag/mode",
            )
            is TubeLaserForm -> OperationParams.TubeLaser(
                field("$tag/cuts", form.cuts),
                (material.material as? Material.LongProduct)?.takeIf { sizeChosen }?.let { "${formatMeasure(it.sectionPerimeterMm, 1)} мм" },
            )
            is HolesForm -> {
                val thickness = material.material.thicknessMm.takeIf { sizeChosen }
                OperationParams.Holes(
                    rows = form.rows.mapIndexed { index, row ->
                        val rowTag = EditorTags.row(tag, index)
                        val diameter = parseDecimal(row.diameter)
                        HoleRowView(
                            id = row.id,
                            tag = rowTag,
                            diameter = field("$rowTag/diameter", row.diameter),
                            count = field("$rowTag/count", row.count),
                            throughBothWalls = row.throughBothWalls,
                            warning = if (thickness != null && diameter != null && diameter.signum() > 0 && diameter < thickness) {
                                "Диаметр меньше толщины детали (${thickness.toInputText()} мм)"
                            } else {
                                null
                            },
                        )
                    },
                    rowsError = errors["$tag/rows"],
                    bothWallsAvailable = materialForm.kind.hasTwoWalls,
                    addTag = "$tag/add-row",
                )
            }
            is BendingForm -> OperationParams.Bending(countRows(tag, form.rows), errors["$tag/rows"], "$tag/add-row")
        }
    }

    fun productOperation(form: CalculationForm, kind: OperationKind): OperationView {
        val tag = EditorTags.operation(EditorTags.PRODUCT, kind)
        val op = form.productOperations[kind]
        val params = when (op) {
            null -> null
            is AssemblyWeldingForm -> {
                val thicknesses = book.sortament.allThicknesses()
                OperationParams.AssemblyWelding(
                    weldType = choice("$tag/weld-type", "Тип сварки", op.weldType, book.general.weldTypes, NO_WELD_TYPES),
                    group = choice("$tag/group", "Группа", op.group, book.groups.map { it.name }, NO_GROUPS),
                    thickness = choice("$tag/thickness", "Толщина, мм", op.thicknessMm?.toInputText(), thicknesses.map { it.toInputText() }, NO_SIZES),
                    thicknessOptions = thicknesses,
                    seamLength = field("$tag/seam-length", op.seamLength),
                )
            }
            is PaintingForm -> OperationParams.Painting(productArea(form), field("$tag/area", op.areaOverride), minAreaHint())
        }
        return OperationView(
            ref = LineRef.OfProduct(kind),
            tag = tag,
            title = if (kind == OperationKind.WELDING) "Сварка сборки" else "Покраска изделия",
            checked = op != null,
            unavailableHint = null,
            params = params,
            extra = op?.let { extra(tag, it.extra) },
        )
    }

    /**
     * Σ part areas, each at least the minimum of the settings (spec 6.7), once every part's dimensions are filled.
     * A sheet set by weight takes its area from the density of its grade, so every grade must be known too.
     */
    private fun productArea(form: CalculationForm): String? {
        val parts = form.parts.map { part ->
            val material = part.material.read()
            if (!material.dimensionsComplete) return null
            Part(part.name, material.material, operations = emptyList())
        }
        val densityOf = { part: Part -> book.grades.firstOrNull { it.name == part.material.grade }?.densityKgM3 }
        if (parts.isEmpty() || parts.any { densityOf(it) == null }) return null
        return "${formatMeasure(parts.paintAreaM2(book.general.minPaintAreaPerPartM2, densityOf))} м²"
    }

    private fun minAreaHint(): String {
        val min = formatMeasure(book.general.minPaintAreaPerPartM2)
        return "детали меньше $min м² считаются как $min м²"
    }

    private fun countRows(tag: String, rows: List<CountRowForm>) = rows.mapIndexed { index, row ->
        val rowTag = EditorTags.row(tag, index)
        CountRowView(row.id, rowTag, field("$rowTag/length", row.length), field("$rowTag/count", row.count))
    }

    private companion object {
        const val NO_GRADES = "Нет марок — добавьте их в «Настройки → Материалы»"
        const val NO_SIZES = "Нет размеров — добавьте их в «Настройки → Сортамент»"
        const val NO_GROUPS = "Нет групп — добавьте их в «Настройки → Материалы»"
        const val NO_WELD_TYPES = "Нет типов сварки — добавьте их в «Настройки → Общие»"
    }
}
