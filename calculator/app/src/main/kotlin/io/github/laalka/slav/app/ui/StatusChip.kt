package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** The status as a chip: 26 dp high, fully rounded, 12 sp semibold, in the colours of its stage. */
@Composable
fun StatusChip(status: CalculationStatus, modifier: Modifier = Modifier) {
    val colors = statusColors(status)
    Surface(shape = CircleShape, color = colors.container, contentColor = colors.content, modifier = modifier) {
        Box(Modifier.height(CHIP_HEIGHT).padding(horizontal = CHIP_PADDING), contentAlignment = Alignment.Center) {
            Text(
                status.title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
