package io.github.laalka.slav.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.laalka.slav.app.theme.SlavTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Slav — расчёт стоимости",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        SlavTheme {
            App()
        }
    }
}
