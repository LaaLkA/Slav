package io.github.laalka.slav.app.settings

import io.github.laalka.slav.app.AppPreferences
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.SettingsTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SettingsTab(val title: String) {
    MATERIALS("Материалы"),
    SORTAMENT("Сортамент"),
    MATERIAL_PRICES("Цены материала"),
    OPERATION_PRICES("Цены операций"),
    GENERAL("Общие"),
    TRANSFER("Импорт / экспорт"),
    ;

    val testTag: String get() = "settings-tab/$name"
}

/** The Settings section: one model per tab, all editing the same [SettingsStore]. */
class SettingsModel(repository: SettingsRepository, transfer: SettingsTransfer, preferences: AppPreferences) {
    private val store = SettingsStore(repository)
    val materials = MaterialsModel(store)
    val sortament = SortamentModel(store)
    val prices = PricesModel(store)
    val general = GeneralModel(store, preferences)
    val transfer = TransferModel(store, transfer)

    private val _tab = MutableStateFlow(SettingsTab.MATERIALS)
    val tab: StateFlow<SettingsTab> = _tab.asStateFlow()

    fun selectTab(tab: SettingsTab) {
        _tab.value = tab
    }
}
