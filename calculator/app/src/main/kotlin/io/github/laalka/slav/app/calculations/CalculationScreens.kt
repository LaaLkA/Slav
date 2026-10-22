package io.github.laalka.slav.app.calculations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.theme.Spacing
import io.github.laalka.slav.app.theme.tabular
import io.github.laalka.slav.app.ui.SectionCard

object CalculationTags {
    const val NEW = "calculations/new"
    const val CARD_NAME = "card/name"
    const val CARD_UNIT_PRICE = "card/unit-price"
    const val CARD_TOTAL = "card/total"
    const val CARD_EDIT = "card/edit"
    const val CARD_BACK = "card/back"
}

/** The start page of «Расчёты». */
@Composable
fun CalculationsScreen(onNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text("Готовые расчёты", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Новый расчёт: название и тираж, детали с материалом и операциями, затем «Рассчитать». " +
                "Ввод сохраняется черновиком автоматически.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onNew, modifier = Modifier.testTag(CalculationTags.NEW)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Новый расчёт", modifier = Modifier.padding(start = Spacing.small))
        }
    }
}

/** The calculation card (spec 8.2): name, status, unit price, run size and total. */
@Composable
fun CardScreen(model: CardModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(CalculationTags.CARD_BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К расчётам")
            }
            Text(state.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(CalculationTags.CARD_NAME))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(state.status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        SectionCard("Стоимость", modifier = Modifier.widthIn(max = 560.dp)) {
            AmountRow("Цена изделия", state.unitPrice ?: "—", CalculationTags.CARD_UNIT_PRICE, emphasized = true)
            AmountRow("Тираж", "${state.quantity} шт", tag = null)
            AmountRow("Итог", state.total ?: "—", CalculationTags.CARD_TOTAL)
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.testTag(CalculationTags.CARD_EDIT)) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Text("Изменить", modifier = Modifier.padding(start = Spacing.small))
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, tag: String?, emphasized: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        val style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
        Text(
            value,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag),
            style = style.tabular(),
            textAlign = TextAlign.End,
            softWrap = false,
        )
    }
}
