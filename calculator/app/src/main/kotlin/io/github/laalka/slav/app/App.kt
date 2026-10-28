package io.github.laalka.slav.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.laalka.slav.app.calculations.CalculationsScreen
import io.github.laalka.slav.app.calculations.CardScreen
import io.github.laalka.slav.app.editor.EditorScreen
import io.github.laalka.slav.app.settings.SettingsScreen

const val NAV_CALCULATIONS_TAG = "nav/calculations"
const val NAV_SETTINGS_TAG = "nav/settings"

@Composable
fun App(deps: Deps, filePicker: FilePicker, navigator: Navigator = rememberNavigator(deps)) {
    val page by navigator.page.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                NavigationRailItem(
                    selected = page != Page.Settings,
                    onClick = navigator::selectCalculations,
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Расчёты") },
                    modifier = Modifier.testTag(NAV_CALCULATIONS_TAG),
                )
                NavigationRailItem(
                    selected = page == Page.Settings,
                    onClick = { navigator.navigate(Screen.Settings) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                    modifier = Modifier.testTag(NAV_SETTINGS_TAG),
                )
            }
            VerticalDivider()
            when (val current = page) {
                Page.Calculations -> CalculationsScreen(
                    navigator.calculationList,
                    filePicker,
                    onNew = { navigator.navigate(Screen.Editor(null)) },
                    onOpen = navigator::open,
                    onCopy = navigator::copy,
                )
                // Keyed by the model: another calculation's editor starts with fresh fields.
                is Page.Editor -> key(current.model) {
                    EditorScreen(current.model, filePicker, onBack = { navigator.navigate(Screen.Calculations) })
                }
                is Page.Card -> key(current.model) {
                    CardScreen(
                        current.model,
                        filePicker,
                        onBack = { navigator.navigate(Screen.Calculations) },
                        // A calculated record is not edited itself: its revision draft is.
                        onEdit = { navigator.navigate(Screen.Editor(current.model.edit())) },
                        onOpenLink = navigator::open,
                    )
                }
                Page.Settings -> SettingsScreen(navigator.settings, filePicker)
            }
        }
    }
}
