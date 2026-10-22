package io.github.laalka.slav.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.laalka.slav.app.theme.Spacing

const val NAV_CALCULATIONS_TAG = "nav/calculations"
const val NAV_SETTINGS_TAG = "nav/settings"
const val LEAVE_KEEP_TAG = "leave/keep"
const val LEAVE_DISCARD_TAG = "leave/discard"
const val LEAVE_STAY_TAG = "leave/stay"

@Composable
fun App(deps: Deps, filePicker: FilePicker, navigator: Navigator = rememberNavigator(deps)) {
    val page by navigator.page.collectAsState()
    val leaveQuestion by navigator.leaveQuestion.collectAsState()

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
                    EditorScreen(current.model, onBack = { navigator.navigate(Screen.Calculations) })
                }
                is Page.Card -> key(current.model) {
                    CardScreen(
                        current.model,
                        onBack = { navigator.navigate(Screen.Calculations) },
                        onEdit = { navigator.navigate(Screen.Editor(current.model.id)) },
                    )
                }
                Page.Settings -> SettingsScreen(navigator.settings, filePicker)
            }
        }
    }

    leaveQuestion?.let { name -> LeaveDialog(name, navigator::answerLeave) }
}

/** Spec 8.3: a calculated calculation was edited and is being left without recalculating. */
@Composable
private fun LeaveDialog(name: String, onAnswer: (LeaveAnswer) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(LeaveAnswer.STAY) },
        title = { Text("Расчёт изменён, но не пересчитан") },
        text = {
            Text(
                "Изменения в расчёте «${name.ifEmpty { "без названия" }}» можно сохранить как черновик — " +
                    "тогда у него не будет цены до нового расчёта — или отменить, и расчёт останется с прежним результатом.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(LeaveAnswer.KEEP_DRAFT) }, modifier = Modifier.testTag(LEAVE_KEEP_TAG)) {
                Text("Сохранить как черновик")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextButton(onClick = { onAnswer(LeaveAnswer.STAY) }, modifier = Modifier.testTag(LEAVE_STAY_TAG)) {
                    Text("Вернуться к расчёту")
                }
                TextButton(onClick = { onAnswer(LeaveAnswer.DISCARD) }, modifier = Modifier.testTag(LEAVE_DISCARD_TAG)) {
                    Text("Отменить изменения")
                }
            }
        },
    )
}
