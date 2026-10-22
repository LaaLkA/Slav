package io.github.laalka.slav.app

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.laalka.slav.app.settings.SettingsModel
import io.github.laalka.slav.app.settings.SettingsScreen

/** The destinations of the navigation rail. */
sealed interface Screen {
    data object Calculations : Screen

    data object Settings : Screen
}

const val NAV_CALCULATIONS_TAG = "nav/calculations"
const val NAV_SETTINGS_TAG = "nav/settings"

@Composable
fun App(deps: Deps, filePicker: FilePicker) {
    var screen by remember { mutableStateOf<Screen>(Screen.Calculations) }
    // Created once: the settings tabs keep their state while the user visits other screens.
    val settings = remember(deps) { SettingsModel(deps.settings, deps.settingsTransfer, deps.preferences) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                NavigationRailItem(
                    selected = screen == Screen.Calculations,
                    onClick = { screen = Screen.Calculations },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Расчёты") },
                    modifier = Modifier.testTag(NAV_CALCULATIONS_TAG),
                )
                NavigationRailItem(
                    selected = screen == Screen.Settings,
                    onClick = { screen = Screen.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                    modifier = Modifier.testTag(NAV_SETTINGS_TAG),
                )
            }
            VerticalDivider()
            when (screen) {
                Screen.Calculations -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Расчётов пока нет",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Screen.Settings -> SettingsScreen(settings, filePicker)
            }
        }
    }
}
