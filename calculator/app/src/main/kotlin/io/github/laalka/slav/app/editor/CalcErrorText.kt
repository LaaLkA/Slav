package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.settings.GroupTable
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.core.CalcError
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.PriceTable

/** An empty price cell as the settings show it: the table's title and the headers of its row and column. */
data class MissingCell(val table: String, val row: String, val column: String, val tab: SettingsTab)

/** One reason the calculation stopped (spec 7.3); [cell] is set when a price cell is empty. */
data class ReadableError(val message: String, val cell: MissingCell? = null)

/** The titles of spec 6.1, the names the operation checklist shows. */
val OperationKind.title: String
    get() = when (this) {
        OperationKind.LASER_SHEET -> "Лазер: рез листа"
        OperationKind.TUBE_LASER -> "Лазер: труборез"
        OperationKind.HOLES -> "Лазер: отверстия"
        OperationKind.BENDING -> "Гибка"
        OperationKind.WELDING -> "Сварка"
        OperationKind.PAINTING -> "Покраска порошковая"
    }

val Material.kind: SortamentKind
    get() = when (this) {
        is Material.Sheet -> SortamentKind.SHEET
        is Material.RoundTube -> SortamentKind.ROUND_TUBE
        is Material.ProfileTube -> SortamentKind.PROFILE_TUBE
        is Material.Angle -> SortamentKind.ANGLE
        is Material.Channel -> SortamentKind.CHANNEL
        is Material.IBeam -> SortamentKind.I_BEAM
    }

/** [onSnapshot]: the calculation is priced on its snapshot, so a missing grade is missing there, not in the settings. */
fun CalcError.readable(onSnapshot: Boolean): ReadableError = when (this) {
    is CalcError.MissingPrice -> {
        val cell = MissingCell(
            table = table.title,
            row = row?.let(::decimalHeader) ?: "—",
            column = column?.let(::decimalHeader) ?: "—",
            tab = if (table == PriceTable.MaterialPerKg) SettingsTab.MATERIAL_PRICES else SettingsTab.OPERATION_PRICES,
        )
        val place = if (row == null && column == null) "" else ": строка «${cell.row}», столбец «${cell.column}»"
        ReadableError("Нет цены в таблице «${cell.table}»$place", cell)
    }
    is CalcError.UnknownGrade ->
        ReadableError("Марки «$name» нет ${if (onSnapshot) "в снимке цен расчёта" else "в настройках"}")
    is CalcError.NotApplicable ->
        ReadableError("Операция «${operation.title}» не выполняется для проката «${material.kind.title}»")
    is CalcError.SectionTooLarge ->
        ReadableError("Операция «${operation.title}» недоступна: сечение больше ${limitMm.toInputText()} мм")
}

/** The titles of the tables in «Цены материала» and «Цены операций». */
private val PriceTable.title: String
    get() = when (this) {
        PriceTable.MaterialPerKg -> "Цены материала"
        PriceTable.LaserSheetCut -> GroupTable.LASER_SHEET_CUT.title
        PriceTable.LaserSheetPierce -> GroupTable.LASER_SHEET_PIERCE.title
        PriceTable.TubeLaserCut -> GroupTable.TUBE_LASER_CUT.title
        PriceTable.TubeLaserPierce -> GroupTable.TUBE_LASER_PIERCE.title
        is PriceTable.Bending -> "Гибка — $group"
        is PriceTable.Welding -> "Сварка — $weldType"
        PriceTable.Painting -> "Покраска порошковая"
    }

// Core headers write decimals with a dot ("1.5", "100.5–300"); the settings tables show a comma.
private val decimalPoint = Regex("""(\d)\.(\d)""")

private fun decimalHeader(header: String) = header.replace(decimalPoint, "$1,$2")
