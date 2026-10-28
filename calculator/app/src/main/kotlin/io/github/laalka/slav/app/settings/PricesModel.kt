package io.github.laalka.slav.app.settings

import io.github.laalka.slav.core.PriceBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CellState(val cell: PriceCell, val text: String, val error: String?)

data class GridRow(val label: String, val cells: List<CellState>)

/** One price table as the editor draws it; [emptyHint] replaces it while it has no rows or no columns. */
data class PriceGrid(
    val title: String,
    val unit: String,
    val rowHeader: String,
    val columnHeader: String,
    val columns: List<String>,
    val rows: List<GridRow>,
    val emptyHint: String?,
)

data class PricesState(
    /** «Цены материала» (spec 4.4). */
    val material: PriceGrid,
    /** «Цены операций» (spec 4.5): the group × thickness tables, bending per group, welding per weld type. */
    val groupTables: List<PriceGrid>,
    val bending: List<PriceGrid>,
    val welding: List<PriceGrid>,
    val painting: CellState,
    val paintingUnit: String,
    /** «Лазер, ₽/мин» of the simplified calculation. */
    val laserCutPerMinute: CellState,
    val laserCutPerMinuteTitle: String,
    /** Grows when the fields must show the model's text again, dropping what was typed. */
    val epoch: Int,
)

/**
 * The price tables of «Цены материала» and «Цены операций». Rows and columns come from the
 * directories and the sortament; a valid number is saved at once, an empty cell removes the price,
 * and text that is not a number stays in its cell, flagged and unsaved.
 */
class PricesModel(private val store: SettingsStore) {
    /** Cells holding text that is not a number, with that text. */
    private var invalid: Map<PriceCell, String> = emptyMap()
    private var epoch = 0

    private val _state = MutableStateFlow(build(store.book.value))
    val state: StateFlow<PricesState> = _state.asStateFlow()

    init {
        store.addListener { replaced ->
            if (replaced) {
                invalid = emptyMap()
                epoch++
            }
            refresh()
        }
    }

    fun onCellChange(cell: PriceCell, text: String) {
        when (val input = readPrice(text)) {
            is NumberInput.Invalid -> invalid = invalid + (cell to text)
            NumberInput.Empty -> {
                invalid = invalid - cell
                store.update { it.withPrice(cell, null) }
            }
            is NumberInput.Valid -> {
                invalid = invalid - cell
                store.update { it.withPrice(cell, input.value) }
            }
        }
        refresh()
    }

    private fun refresh() {
        val state = build(store.book.value)
        // Text typed into a cell that is no longer shown (its group was deleted, say) is forgotten.
        val shown = state.allCells()
        invalid = invalid.filterKeys { it in shown }
        _state.value = state
    }

    private fun build(book: PriceBook): PricesState {
        val currency = book.general.currencySymbol
        val groups = book.groups.map { it.name }
        val sheet = book.sortament.sheetThicknesses()
        val all = book.sortament.allThicknesses()

        fun cell(cell: PriceCell): CellState {
            val typed = invalid[cell]
            return CellState(cell, typed ?: book.priceAt(cell)?.toInputText().orEmpty(), if (typed != null) NOT_A_NUMBER else null)
        }

        val groupTables = GroupTable.entries.map { table ->
            val thicknesses = if (table.isSheetTable) sheet else book.sortament.longProductThicknesses()
            grid(
                title = table.title, unit = "$currency/${table.unit}", rowHeader = GROUP, columnHeader = THICKNESS,
                rows = groups, rowLabel = { it }, columns = thicknesses, columnLabel = { it.toInputText() },
                noRows = NO_GROUPS,
                noColumns = if (table.isSheetTable) NO_SHEETS else NO_LONG_PRODUCTS,
            ) { group, t -> cell(PriceCell.Group(table, group, t)) }
        }
        val ranges = book.general.bendRanges()
        val bending = groups.map { group ->
            grid(
                title = "Гибка — $group", unit = "$currency/гиб", rowHeader = THICKNESS, columnHeader = "Длина гиба, мм",
                rows = sheet, rowLabel = { it.toInputText() }, columns = ranges, columnLabel = { it.label },
                noRows = NO_SHEETS, noColumns = NO_SHEETS,
            ) { t, range -> cell(PriceCell.Bending(group, t, range)) }
        }.ifEmpty { listOf(hintOnly("Гибка", "$currency/гиб", NO_GROUPS)) }
        val welding = book.general.weldTypes.map { weldType ->
            grid(
                title = "Сварка — $weldType", unit = "$currency/см", rowHeader = GROUP, columnHeader = THICKNESS,
                rows = groups, rowLabel = { it }, columns = all, columnLabel = { it.toInputText() },
                noRows = NO_GROUPS, noColumns = NO_SIZES,
            ) { group, t -> cell(PriceCell.Welding(weldType, group, t)) }
        }.ifEmpty { listOf(hintOnly("Сварка", "$currency/см", "Добавьте тип сварки во вкладке «Общие»")) }

        return PricesState(
            material = grid(
                title = "Цены материала", unit = "$currency/кг", rowHeader = "Марка", columnHeader = THICKNESS,
                rows = book.grades, rowLabel = { it.name }, columns = all, columnLabel = { it.toInputText() },
                noRows = "Добавьте марку во вкладке «Материалы»", noColumns = NO_SIZES,
            ) { grade, t -> cell(PriceCell.Material(grade.name, t)) },
            groupTables = groupTables,
            bending = bending,
            welding = welding,
            painting = cell(PriceCell.Painting),
            paintingUnit = "$currency/м²",
            laserCutPerMinute = cell(PriceCell.LaserCutPerMinute),
            laserCutPerMinuteTitle = laserCutPerMinuteTitle(currency),
            epoch = epoch,
        )
    }

    private companion object {
        const val GROUP = "Группа"
        const val THICKNESS = "Толщина, мм"
        const val NO_GROUPS = "Добавьте группу материалов во вкладке «Материалы»"
        const val NO_SIZES = "Добавьте размеры во вкладке «Сортамент»"
        const val NO_SHEETS = "Добавьте толщины листа во вкладке «Сортамент»"
        const val NO_LONG_PRODUCTS = "Добавьте трубы или фасонный прокат во вкладке «Сортамент»"

        fun <R, C> grid(
            title: String,
            unit: String,
            rowHeader: String,
            columnHeader: String,
            rows: List<R>,
            rowLabel: (R) -> String,
            columns: List<C>,
            columnLabel: (C) -> String,
            noRows: String,
            noColumns: String,
            cell: (R, C) -> CellState,
        ) = PriceGrid(
            title = title,
            unit = unit,
            rowHeader = rowHeader,
            columnHeader = columnHeader,
            columns = columns.map(columnLabel),
            rows = rows.map { row -> GridRow(rowLabel(row), columns.map { column -> cell(row, column) }) },
            emptyHint = when {
                rows.isEmpty() -> noRows
                columns.isEmpty() -> noColumns
                else -> null
            },
        )

        fun hintOnly(title: String, unit: String, hint: String) =
            PriceGrid(title, unit, rowHeader = "", columnHeader = "", columns = emptyList(), rows = emptyList(), emptyHint = hint)

        fun PricesState.allCells(): Set<PriceCell> =
            (listOf(material) + groupTables + bending + welding)
                .flatMap { grid -> grid.rows.flatMap { row -> row.cells.map { it.cell } } }
                .toSet() + painting.cell + laserCutPerMinute.cell
    }
}
