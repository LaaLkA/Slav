package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.FilledColors
import io.github.laalka.slav.app.theme.extendedColors
import io.github.laalka.slav.data.CalculationStatus

private val CHIP_HEIGHT = 26.dp
private val CHIP_PADDING = 10.dp

/** The colours of the chip of [status]: one hue per stage, so the stage is told apart at a glance in the list. */
@Composable
fun statusColors(status: CalculationStatus): FilledColors {
    val colors = MaterialTheme.extendedColors
    return when (status) {
        CalculationStatus.DRAFT -> colors.statusDraft
        CalculationStatus.CALCULATED -> FilledColors(colors.success, colors.onSuccess)
        CalculationStatus.SENT -> colors.statusSent
        CalculationStatus.IN_WORK -> colors.statusInWork
        CalculationStatus.ARCHIVED -> colors.statusFinished
    }
}

/**
 * The status as a chip: 26 dp high, fully rounded, 12 sp semibold, in the colours of its stage. A chip that opens a
 * menu says so with a [trailing] mark after its text.
 */
@Composable
fun StatusChip(status: CalculationStatus, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val colors = statusColors(status)
    // One node that says its status, whether or not it is a menu.
    Surface(shape = CircleShape, color = colors.container, contentColor = colors.content, modifier = modifier.semantics(mergeDescendants = true) {}) {
        Row(
            modifier = Modifier.height(CHIP_HEIGHT).padding(horizontal = CHIP_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                status.title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}
