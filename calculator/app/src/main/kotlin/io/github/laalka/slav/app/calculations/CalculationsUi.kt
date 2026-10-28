package io.github.laalka.slav.app.calculations

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.data.CalculationSortColumn
import io.github.laalka.slav.data.CalculationStatus

object CalculationTags {
    const val NEW = "calculations/new"
    const val SEARCH = "calculations/search"
    const val PERIOD_FROM = "calculations/period-from"
    const val PERIOD_TO = "calculations/period-to"
    const val CLEAR_FILTERS = "calculations/clear-filters"
    const val EMPTY = "calculations/empty"
    const val NO_MATCHES = "calculations/no-matches"
    const val MESSAGE = "calculations/message"
    const val DELETE_CONFIRM = "calculations/delete/confirm"
    const val DELETE_CANCEL = "calculations/delete/cancel"
    const val DELETE_PASSWORD = "calculations/delete/password"

    const val CARD_NAME = "card/name"
    const val CARD_STATUS = "card/status"
    const val CARD_SNAPSHOT_DATE = "card/snapshot-date"
    const val CARD_UNIT_PRICE = "card/unit-price"
    const val CARD_QUANTITY = "card/quantity"
    const val CARD_QUANTITY_CHANGE = "card/quantity/change"
    const val CARD_QUANTITY_FIELD = "card/quantity/field"
    const val CARD_QUANTITY_CONFIRM = "card/quantity/confirm"
    const val CARD_QUANTITY_CANCEL = "card/quantity/cancel"
    const val CARD_SOURCE = "card/source"
    const val CARD_DELETE = "card/delete"
    const val CARD_TOTAL = "card/total"
    const val CARD_UNIT_MASS = "card/unit-mass"
    const val CARD_RUN_MASS = "card/run-mass"
    const val CARD_EDIT = "card/edit"
    const val CARD_RECALC = "card/recalc"
    const val CARD_RECALC_CONFIRM = "card/recalc/confirm"
    const val CARD_RECALC_CANCEL = "card/recalc/cancel"
    const val CARD_RECALC_ERRORS = "card/recalc/errors"
    const val CARD_BACK = "card/back"

    fun status(status: CalculationStatus) = "calculations/status/${status.name}"

    /** A link to a newer version of the card's calculation, by its position. */
    fun cardRevision(index: Int) = "card/revision/$index"

    fun sort(column: CalculationSortColumn) = "calculations/sort/${column.name}"

    /** A row of the list by its position; its cells and actions are "<row>/name", "<row>/menu", "<row>/copy"… */
    fun row(index: Int) = "calculations/row/$index"
}

/** The status as a chip; the colour tells the stage apart at a glance in the list. */
@Composable
fun StatusChip(status: CalculationStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (status) {
        CalculationStatus.DRAFT -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        CalculationStatus.CALCULATED -> scheme.primaryContainer to scheme.onPrimaryContainer
        CalculationStatus.SENT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        CalculationStatus.IN_WORK -> scheme.secondaryContainer to scheme.onSecondaryContainer
        CalculationStatus.ARCHIVED -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content, modifier = modifier) {
        Text(
            status.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
