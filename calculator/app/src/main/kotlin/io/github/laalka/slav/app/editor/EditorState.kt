package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.core.OperationKind
import java.math.BigDecimal

/** A line of the calculation that has a «Доп. операции» amount: a part's material, a part operation or a product operation. */
sealed interface LineRef {
    data class MaterialOf(val part: Long) : LineRef

    data class OfPart(val part: Long, val kind: OperationKind) : LineRef

    data class OfProduct(val kind: OperationKind) : LineRef
}

/** A text field: [tag] is its test tag and the key of [error]. */
data class FieldView(val tag: String, val text: String, val error: String?)

/** A pick from [options]; [selected] is the label of the current choice, which may be missing from the options. */
data class ChoiceView(val tag: String, val label: String, val selected: String?, val options: List<String>, val error: String?)

data class ExtraView(val amount: FieldView, val comment: FieldView)

data class EditorState(
    /** Opened by «Новый расчёт» rather than on a saved calculation. */
    val isNew: Boolean,
    /** Which prices «Рассчитать» uses: the current settings or the calculation's snapshot (spec 8.3). */
    val priceSource: String,
    val currency: String,
    val name: FieldView,
    val quantity: FieldView,
    val parts: List<PartView>,
    val partsError: String?,
    val productOperations: List<OperationView>,
    /** How many fields keep «Рассчитать» disabled; each of them is highlighted. */
    val missingCount: Int,
    /** Why the last «Рассчитать» stopped (spec 7.3); empty when it did not. */
    val calcErrors: List<ReadableError>,
    val calcErrorsHint: String?,
) {
    val canCalculate: Boolean get() = missingCount == 0
}

data class PartView(
    val id: Long,
    val tag: String,
    val title: String,
    val name: FieldView,
    val kind: SortamentKind,
    val grade: ChoiceView,
    val size: ChoiceView,
    /** The sizes behind [size]'s options, in the same order. */
    val sizeOptions: List<SizeChoice>,
    /** The outline of a sheet; null for any other kind. */
    val shape: ShapeKind?,
    /** The fields of the material's dimensions: the sheet's shape, or the length of a long product. */
    val dimensions: List<DimensionView>,
    /** The mass from the drawing, an optional field of every kind. */
    val massOverride: FieldView,
    /** The mass of the part: the one from the drawing when it is typed, else the computed one, null until the material is complete. */
    val mass: String?,
    /** The mass shown is the one from the drawing. */
    val massFromDrawing: Boolean,
    val materialExtra: ExtraView,
    val operations: List<OperationView>,
)

data class DimensionView(val field: DimensionField, val value: FieldView)

data class OperationView(
    val ref: LineRef,
    val tag: String,
    val title: String,
    val checked: Boolean,
    /** Set when the operation is shown but cannot be chosen (spec 6.1). */
    val unavailableHint: String?,
    /** Present while the operation is checked. */
    val params: OperationParams?,
    val extra: ExtraView?,
)

sealed interface OperationParams {
    data class LaserSheet(val mode: CutMode, val contourLength: FieldView, val pierces: FieldView, val modeTag: String) : OperationParams

    /** [cutLength] is the length of one cut, the section perimeter, once the section is chosen. */
    data class TubeLaser(val cuts: FieldView, val cutLength: String?) : OperationParams

    /** «Через обе стенки» exists for tubes only (spec 6.4). */
    data class Holes(
        val rows: List<HoleRowView>,
        val rowsError: String?,
        val bothWallsAvailable: Boolean,
        val addTag: String,
    ) : OperationParams

    data class Bending(val rows: List<CountRowView>, val rowsError: String?, val addTag: String) : OperationParams

    data class AssemblyWelding(
        val weldType: ChoiceView,
        val group: ChoiceView,
        val thickness: ChoiceView,
        /** The thicknesses behind [thickness]'s options, in the same order. */
        val thicknessOptions: List<BigDecimal>,
        /** The total length of all seams. */
        val seamLength: FieldView,
    ) : OperationParams

    /**
     * [autoArea] is the area by the dimensions, null until they are filled; [areaOverride] replaces
     * it when typed. [minAreaHint] says what the settings do to a small part's area.
     */
    data class Painting(val autoArea: String?, val areaOverride: FieldView, val minAreaHint: String) : OperationParams
}

/** [warning] does not block the calculation: a hole narrower than the part is thick (spec 6.4). */
data class HoleRowView(
    val id: Long,
    val tag: String,
    val diameter: FieldView,
    val count: FieldView,
    val throughBothWalls: Boolean,
    val warning: String?,
)

data class CountRowView(val id: Long, val tag: String, val length: FieldView, val count: FieldView)
