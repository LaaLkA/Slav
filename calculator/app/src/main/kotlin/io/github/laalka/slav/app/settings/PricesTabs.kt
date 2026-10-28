package io.github.laalka.slav.app.settings

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.ui.HeaderText
import io.github.laalka.slav.app.ui.InputField
import io.github.laalka.slav.app.ui.SectionCard
import io.github.laalka.slav.app.ui.TabContent

private val ROW_HEADER_WIDTH = 144.dp
private val CELL_WIDTH = 96.dp

// Pushes a row label down to the text line of the outlined fields next to it.
private val LABEL_OFFSET = 18.dp

private const val PRICE_HINT = "Пустая ячейка — цены нет, расчёт с ней остановится; 0 — бесплатная операция. Дробные числа — через запятую или точку."

@Composable
fun MaterialPricesTab(model: PricesModel) {
    val state by model.state.collectAsState()
    TabContent {
        key(state.epoch) {
            PriceGridCard(state.material, model::onCellChange)
        }
    }
}

@Composable
fun OperationPricesTab(model: PricesModel) {
    val state by model.state.collectAsState()
    TabContent {
        key(state.epoch) {
            Text(PRICE_HINT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.groupTables.forEach { PriceGridCard(it, model::onCellChange) }
            state.bending.forEach { PriceGridCard(it, model::onCellChange) }
            state.welding.forEach { PriceGridCard(it, model::onCellChange) }
            SectionCard("Покраска порошковая", subtitle = "${state.paintingUnit} · одно значение") {
                PriceCellField(state.painting, model::onCellChange, Modifier.width(ROW_HEADER_WIDTH))
            }
            SectionCard(state.laserCutPerMinuteTitle, subtitle = "Упрощённый расчёт: время реза × ставка · одно значение") {
                PriceCellField(state.laserCutPerMinute, model::onCellChange, Modifier.width(ROW_HEADER_WIDTH))
            }
        }
    }
}

@Composable
private fun PriceGridCard(grid: PriceGrid, onCellChange: (PriceCell, String) -> Unit) {
    val subtitle = if (grid.emptyHint == null) {
        "${grid.unit} · строки — ${grid.rowHeader.lowercase()}, столбцы — ${grid.columnHeader.lowercase()}"
    } else {
        grid.unit
    }
    SectionCard(grid.title, subtitle = subtitle) {
        val hint = grid.emptyHint
        if (hint != null) {
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // A table wider than the window scrolls sideways; the scrollbar shows there is more of it.
            val scroll = rememberScrollState()
            Column(Modifier.horizontalScroll(scroll), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Bottom) {
                    HeaderText(grid.rowHeader, Modifier.width(ROW_HEADER_WIDTH))
                    grid.columns.forEach { HeaderText(it, Modifier.width(CELL_WIDTH).padding(end = Spacing.medium), numeric = true) }
                }
                grid.rows.forEach { row ->
                    key(row.label) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                            Text(row.label, Modifier.width(ROW_HEADER_WIDTH).padding(top = LABEL_OFFSET), style = MaterialTheme.typography.bodyLarge)
                            row.cells.forEach { cell -> key(cell.cell) { PriceCellField(cell, onCellChange, Modifier.width(CELL_WIDTH)) } }
                        }
                    }
                }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PriceCellField(cell: CellState, onCellChange: (PriceCell, String) -> Unit, modifier: Modifier) {
    InputField(
        cell.text, { onCellChange(cell.cell, it) }, modifier.testTag(cell.cell.testTag()),
        error = cell.error, numeric = true,
    )
}

/** Stable across runs and readable in a failing test. */
fun PriceCell.testTag(): String = when (this) {
    is PriceCell.Material -> "price/material/$grade/${thicknessMm.toPlainString()}"
    is PriceCell.Group -> "price/${table.name}/$group/${thicknessMm.toPlainString()}"
    is PriceCell.Bending -> "price/bending/$group/${thicknessMm.toPlainString()}/${range.toMm?.toPlainString() ?: "max"}"
    is PriceCell.Welding -> "price/welding/$weldType/$group/${thicknessMm.toPlainString()}"
    PriceCell.Painting -> "price/painting"
    PriceCell.LaserCutPerMinute -> "price/laser-per-minute"
}
