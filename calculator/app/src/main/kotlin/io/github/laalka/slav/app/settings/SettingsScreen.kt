package io.github.laalka.slav.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.laalka.slav.app.FilePicker
import io.github.laalka.slav.app.theme.Spacing

@Composable
fun SettingsScreen(model: SettingsModel, filePicker: FilePicker) {
    val tab by model.tab.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = Spacing.large, top = Spacing.large, end = Spacing.large, bottom = Spacing.small),
        )
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = entry == tab,
                    onClick = { model.selectTab(entry) },
                    text = { Text(entry.title) },
                    modifier = Modifier.testTag(entry.testTag),
                )
            }
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
