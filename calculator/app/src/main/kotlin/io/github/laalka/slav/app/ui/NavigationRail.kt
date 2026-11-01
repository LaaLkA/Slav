package io.github.laalka.slav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.extendedColors

private val RAIL_WIDTH = 88.dp
private val ITEM_WIDTH = 72.dp
private val ITEM_RADIUS = 12.dp
private val ICON_SIZE = 22.dp

/**
 * The navigation rail: a dark strip along the left edge, in both themes, with the logo at the top and then the
 * [RailItem]s. In the dark theme the strip is darker than the page and has a border on its right side.
 */
@Composable
fun SlavNavigationRail(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.extendedColors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .background(colors.rail)
            .endBorder(colors.railBorder)
            .padding(vertical = 20.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SlavLogo()
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** One destination of the rail: [icon] over [label], tinted with the accent while [selected]. */
@Composable
fun RailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.extendedColors
    val content = if (selected) colors.onRailSelected else colors.onRail
    Column(
        modifier = modifier
            .width(ITEM_WIDTH)
            .clip(RoundedCornerShape(ITEM_RADIUS))
            .background(if (selected) colors.railSelected else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = colors.onRailSelected),
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE), tint = content)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** A 1 dp line down the right edge; nothing is drawn when [color] is transparent. */
private fun Modifier.endBorder(color: Color): Modifier = drawBehind {
    val width = 1.dp.toPx()
    drawRect(color, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height))
}
