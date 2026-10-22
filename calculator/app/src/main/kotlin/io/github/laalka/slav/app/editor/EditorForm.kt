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

/** [size] is null until picked and always matches [kind]; [width] is used by a sheet only. */
data class MaterialForm(
    val kind: SortamentKind = SortamentKind.SHEET,
    val grade: String? = null,
    val size: SizeChoice? = null,
    val length: String = "",
    val width: String = "",
)

/** The «Прямоугольник / Длина контура» switch of the sheet laser (spec 6.2). */
enum class CutMode(val title: String) {
    RECTANGLE("Прямоугольник"),
    CONTOUR("Длина контура"),
}

data class HoleRowForm(val id: Long, val diameter: String = "", val count: String = "1", val throughBothWalls: Boolean = false)

/** A bend or a weld seam: a length and how many of them. */
data class CountRowForm(val id: Long, val length: String = "", val count: String = "1")

sealed interface OperationForm {
    val extra: ExtraForm
}

sealed interface PartOperationForm : OperationForm

sealed interface ProductOperationForm : OperationForm

data class LaserSheetForm(
    val mode: CutMode = CutMode.RECTANGLE,
    val contourLength: String = "",
    val pierces: String = "1",
    override val extra: ExtraForm = ExtraForm(),
) : PartOperationForm

data class TubeLaserForm(val cuts: String = "2", override val extra: ExtraForm = ExtraForm()) : PartOperationForm

data class HolesForm(val rows: List<HoleRowForm>, override val extra: ExtraForm = ExtraForm()) : PartOperationForm

data class BendingForm(val rows: List<CountRowForm>, override val extra: ExtraForm = ExtraForm()) : PartOperationForm

data class WeldingForm(
    val weldType: String?,
    val rows: List<CountRowForm>,
    override val extra: ExtraForm = ExtraForm(),
) : PartOperationForm

/** A blank [areaOverride] means the area by the dimensions (spec 6.7). */
data class PaintingForm(
    val areaOverride: String = "",
    override val extra: ExtraForm = ExtraForm(),
) : PartOperationForm, ProductOperationForm

/** Assembly welding: there is no single material, so the group and the thickness are picked (spec 6.6). */
data class AssemblyWeldingForm(
    val weldType: String?,
    val group: String?,
    val thicknessMm: BigDecimal?,
    val rows: List<CountRowForm>,
    override val extra: ExtraForm = ExtraForm(),
) : ProductOperationForm

/** [operations] holds the checked operations only. */
data class PartForm(
    val id: Long,
    val name: String,
    val quantity: String = "1",
    val material: MaterialForm = MaterialForm(),
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
    is WeldingForm -> copy(extra = extra)
    is PaintingForm -> copy(extra = extra)
    is AssemblyWeldingForm -> copy(extra = extra)
}

/** Bending, welding and assembly welding keep their rows as [CountRowForm]; other forms are returned as they are. */
fun OperationForm.withCountRows(transform: (List<CountRowForm>) -> List<CountRowForm>): OperationForm = when (this) {
    is BendingForm -> copy(rows = transform(rows))
    is WeldingForm -> copy(rows = transform(rows))
    is AssemblyWeldingForm -> copy(rows = transform(rows))
    is LaserSheetForm, is TubeLaserForm, is HolesForm, is PaintingForm -> this
}
