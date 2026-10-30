package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BeamSize
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.ProfileTubeSize
import io.github.laalka.slav.core.RoundTubeSize
import java.math.BigDecimal

// What the editor holds: the fields as typed and the choices as made. EditorRead turns it into a
// CalculationInput; ids only tell parts and rows apart on screen and are not stored.

/** «Доп. операции, ₽» of one line (spec 3). */
data class ExtraForm(val amount: String = "", val comment: String = "")

/** A size picked from the sortament (spec 5): a part's thickness and section always come from it. */
sealed interface SizeChoice {
    data class Sheet(val thicknessMm: BigDecimal) : SizeChoice

    data class RoundTube(val size: RoundTubeSize) : SizeChoice

    data class ProfileTube(val size: ProfileTubeSize) : SizeChoice

    data class Angle(val size: AngleSize) : SizeChoice

    /** A channel or an I-beam: both have the same sortament row. */
    data class Beam(val size: BeamSize) : SizeChoice
}

/** How a sheet is set, the «Форма» picker of the material block (spec 5): by the outline of a shape, or by the weight alone. */
enum class ShapeKind(val title: String) {
    RECTANGLE("Прямоугольник / полоса"),
    CIRCLE("Круг"),
    RING("Кольцо"),
    FREE("Произвольная"),
    BY_MASS("По весу"),
}

/** A dimension of the material the user types, in the order the fields are shown; [tag] ends the field's test tag. */
enum class DimensionField(val label: String, val tag: String) {
    LENGTH("Длина, мм", "length"),
    WIDTH("Ширина, мм", "width"),
    RADIUS("Радиус, мм", "radius"),
    OUTER_RADIUS("Наружный радиус, мм", "outer-radius"),
    INNER_RADIUS("Внутренний радиус, мм", "inner-radius"),
    AREA("Площадь, мм²", "area"),
    PERIMETER("Периметр реза, мм", "perimeter"),
    // Not "mass": that tag is the readout «Масса детали» of the same part.
    MASS("Вес, кг", "weight"),
}

/**
 * [size] is null until picked and always matches [kind]. A long product has a [length]. A sheet has
 * a [shape], and the dimensions of the shape are the only ones read and shown; the others keep what
 * was typed, so going back to a shape finds its numbers again. The [perimeter] is shared by the free
 * shape and the weight.
 */
data class MaterialForm(
    val kind: SortamentKind = SortamentKind.SHEET,
    val grade: String? = null,
    val size: SizeChoice? = null,
    val length: String = "",
    val shape: ShapeKind = ShapeKind.RECTANGLE,
    val width: String = "",
    val radius: String = "",
    val outerRadius: String = "",
    val innerRadius: String = "",
    val area: String = "",
    val perimeter: String = "",
    val mass: String = "",
) {
    /** The dimensions of this material, in the order they are shown. */
    val dimensions: List<DimensionField>
        get() = when (kind) {
            SortamentKind.SHEET -> when (shape) {
                ShapeKind.RECTANGLE -> listOf(DimensionField.LENGTH, DimensionField.WIDTH)
                ShapeKind.CIRCLE -> listOf(DimensionField.RADIUS)
                ShapeKind.RING -> listOf(DimensionField.OUTER_RADIUS, DimensionField.INNER_RADIUS)
                ShapeKind.FREE -> listOf(DimensionField.AREA, DimensionField.PERIMETER)
                ShapeKind.BY_MASS -> listOf(DimensionField.MASS, DimensionField.PERIMETER)
            }
            else -> listOf(DimensionField.LENGTH)
        }

    fun dimension(field: DimensionField): String = when (field) {
        DimensionField.LENGTH -> length
        DimensionField.WIDTH -> width
        DimensionField.RADIUS -> radius
        DimensionField.OUTER_RADIUS -> outerRadius
        DimensionField.INNER_RADIUS -> innerRadius
        DimensionField.AREA -> area
        DimensionField.PERIMETER -> perimeter
        DimensionField.MASS -> mass
    }

    fun withDimension(field: DimensionField, text: String): MaterialForm = when (field) {
        DimensionField.LENGTH -> copy(length = text)
        DimensionField.WIDTH -> copy(width = text)
        DimensionField.RADIUS -> copy(radius = text)
        DimensionField.OUTER_RADIUS -> copy(outerRadius = text)
        DimensionField.INNER_RADIUS -> copy(innerRadius = text)
        DimensionField.AREA -> copy(area = text)
        DimensionField.PERIMETER -> copy(perimeter = text)
        DimensionField.MASS -> copy(mass = text)
    }

    /** What a rolled kind other than a sheet does not have: a shape. The length is the one dimension they share. */
    fun withoutSheetShape(): MaterialForm = copy(
        shape = ShapeKind.RECTANGLE, width = "", radius = "", outerRadius = "", innerRadius = "", area = "", perimeter = "", mass = "",
    )
}

/** The «По форме / Длина контура» switch of the sheet laser (spec 6.2). */
enum class CutMode(val title: String) {
    BY_SHAPE("По форме"),
    CONTOUR("Длина контура"),
}

data class HoleRowForm(val id: Long, val diameter: String = "", val count: String = "1", val throughBothWalls: Boolean = false)

/** A bend: a length and how many of them. */
data class CountRowForm(val id: Long, val length: String = "", val count: String = "1")

sealed interface OperationForm {
    val extra: ExtraForm
}

sealed interface PartOperationForm : OperationForm

sealed interface ProductOperationForm : OperationForm

data class LaserSheetForm(
    val mode: CutMode = CutMode.BY_SHAPE,
    val contourLength: String = "",
    val pierces: String = "1",
    override val extra: ExtraForm = ExtraForm(),
) : PartOperationForm

data class TubeLaserForm(val cuts: String = "2", override val extra: ExtraForm = ExtraForm()) : PartOperationForm

data class HolesForm(val rows: List<HoleRowForm>, override val extra: ExtraForm = ExtraForm()) : PartOperationForm

data class BendingForm(val rows: List<CountRowForm>, override val extra: ExtraForm = ExtraForm()) : PartOperationForm

/** A blank [areaOverride] means the area by the dimensions (spec 6.7). */
data class PaintingForm(
    val areaOverride: String = "",
    override val extra: ExtraForm = ExtraForm(),
) : ProductOperationForm

/**
 * Assembly welding: there is no single material, so the group and the thickness are picked, and
 * the seams are one total length (spec 6.6).
 */
data class AssemblyWeldingForm(
    val weldType: String?,
    val group: String?,
    val thicknessMm: BigDecimal?,
    val seamLength: String = "",
    override val extra: ExtraForm = ExtraForm(),
) : ProductOperationForm

/** One piece of the product; identical parts are copies. [massOverride] is the mass from the drawing, blank for the computed one. */
data class PartForm(
    val id: Long,
    val name: String,
    val material: MaterialForm = MaterialForm(),
    val massOverride: String = "",
    val materialExtra: ExtraForm = ExtraForm(),
    val operations: Map<OperationKind, PartOperationForm> = emptyMap(),
)

/** [productOperations] holds the checked ones: WELDING is assembly welding, PAINTING the product painting. */
data class CalculationForm(
    val name: String = "",
    val quantity: String = "1",
    val parts: List<PartForm>,
    val productOperations: Map<OperationKind, ProductOperationForm> = emptyMap(),
)

fun OperationForm.withExtra(extra: ExtraForm): OperationForm = when (this) {
    is LaserSheetForm -> copy(extra = extra)
    is TubeLaserForm -> copy(extra = extra)
    is HolesForm -> copy(extra = extra)
    is BendingForm -> copy(extra = extra)
    is PaintingForm -> copy(extra = extra)
    is AssemblyWeldingForm -> copy(extra = extra)
}

/** Bending keeps its rows as [CountRowForm]; other forms are returned as they are. */
fun OperationForm.withCountRows(transform: (List<CountRowForm>) -> List<CountRowForm>): OperationForm = when (this) {
    is BendingForm -> copy(rows = transform(rows))
    is LaserSheetForm, is TubeLaserForm, is HolesForm, is PaintingForm, is AssemblyWeldingForm -> this
}
