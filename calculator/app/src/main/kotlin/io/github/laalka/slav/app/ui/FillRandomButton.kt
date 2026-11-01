package io.github.laalka.slav.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.laalka.slav.app.theme.Spacing

/** Developer mode: the button that fills an editor or the settings tables with random data. */
@Composable
fun FillRandomButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Заполнить случайными данными") {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = Spacing.small))
    }
}
