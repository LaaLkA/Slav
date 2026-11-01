package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.theme.Spacing

private val TAB_HEIGHT = 44.dp
private val TAB_PADDING = 16.dp
private val UNDERLINE = 2.dp

@Composable
fun SettingsScreen(model: SettingsModel, filePicker: FilePicker) {
    val tab by model.tab.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = Spacing.page, end = Spacing.page, top = Spacing.pageTight)) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = Spacing.medium))
            SettingsTabs(tab, model::selectTab)
        }
        when (tab) {
            SettingsTab.MATERIALS -> MaterialsTab(model.materials)
            SettingsTab.SORTAMENT -> SortamentTab(model.sortament)
            SettingsTab.MATERIAL_PRICES -> MaterialPricesTab(model.prices)
            SettingsTab.OPERATION_PRICES -> OperationPricesTab(model.prices)
            SettingsTab.GENERAL -> GeneralTab(model.general)
            SettingsTab.TRANSFER -> TransferTab(model.transfer, filePicker)
        }
    }
}

/** The tabs as a row of text over one line, the chosen one underlined in the accent; a row that does not fit wraps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTabs(selected: SettingsTab, onSelect: (SettingsTab) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.selectableGroup()) {
            SettingsTab.entries.forEach { entry ->
                Tab(entry.title, entry == selected, { onSelect(entry) }, Modifier.testTag(entry.testTag))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Tab(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(TAB_HEIGHT)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val line = UNDERLINE.toPx()
                    drawRect(accent, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = TAB_PADDING),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
