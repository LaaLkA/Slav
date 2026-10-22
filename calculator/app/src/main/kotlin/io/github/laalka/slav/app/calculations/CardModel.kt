package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.app.ui.formatMoney
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Money texts carry the currency label of the settings; a draft has no price and shows null (spec 7.2). */
data class CardState(
    val name: String,
    val status: String,
    val unitPrice: String?,
    val quantity: Int,
    val total: String?,
)

/** The card of one calculation (spec 8.2): where «Рассчитать» leads. */
class CardModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    val id: UUID,
) {
    private val _state = MutableStateFlow(build())
    val state: StateFlow<CardState> = _state.asStateFlow()

    /** Reads the calculation and the currency label again, after either may have changed elsewhere. */
    fun reload() {
        _state.value = build()
    }

    private fun build(): CardState {
        val calculation = requireNotNull(calculations.get(id)) { "No calculation $id" }
        val currency = settings.load().general.currencySymbol
        val result = calculation.result
        return CardState(
            name = calculation.name,
            status = calculation.status.title,
            unitPrice = result?.let { "${formatMoney(it.unitPrice)} $currency" },
            quantity = calculation.quantity,
            total = result?.let { "${formatMoney(it.total(calculation.quantity))} $currency" },
        )
    }
}
