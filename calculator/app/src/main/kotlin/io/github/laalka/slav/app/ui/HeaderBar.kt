package io.github.laalka.slav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.extendedColors

/** The title of a bar over an editor or a card: 22 sp bold, a step below the 26 sp of the title of a page. */
val BarTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp)

/**
 * The title bar of an editor and of a card: the way back, the [title] block (a title and the lines under it) and the
 * [actions] at the right end, on the page colour with a line under it. [backTag] is the test tag of the way back.
 */
@Composable
fun TitleBar(
    onBack: () -> Unit,
    backTag: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.pageTight, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            BackButton(onBack, tag = backTag)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = title)
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.extendedColors.headerBorder))
    }
}

/** The way back to the list: a 44 dp square with the arrow, tagged [tag]. */
@Composable
private fun BackButton(onClick: () -> Unit, tag: String) {
    Box(
        modifier = Modifier
            .size(ControlHeight)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LineIcons.Back, contentDescription = "К расчётам", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}
