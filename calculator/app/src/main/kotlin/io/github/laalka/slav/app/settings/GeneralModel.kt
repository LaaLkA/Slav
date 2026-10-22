package io.github.laalka.slav.app.settings

import io.github.laalka.slav.app.AppPreferences
import io.github.laalka.slav.app.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GeneralState(
    val currency: FieldState,
    /** Bend-length range bounds, ascending, and the ranges they make: «до 100», «100–300», … */
    val bendBounds: List<String>,
    val bendRanges: List<String>,
    val boundForm: FieldState,
    val weldTypes: List<String>,
    val weldTypeForm: FieldState,
    val tubeLaserMaxSection: FieldState,
    val theme: ThemeMode,
    /** Grows when the fields must show the model's text again, dropping what was typed. */
    val epoch: Int,
)

/** «Общие» (spec 4.6) and the colour theme (spec 11). */
class GeneralModel(private val store: SettingsStore, private val preferences: AppPreferences) {
    /** Text of a field that cannot be saved as it is, null while the field shows the saved value. */
    private var currencyEdit: String? = null
    private var maxSectionEdit: String? = null
    private var boundForm = FieldState()
    private var weldTypeForm = FieldState()
    private var epoch = 0

    private val _state = MutableStateFlow(build())
    val state: StateFlow<GeneralState> = _state.asStateFlow()

    init {
        store.addListener { replaced ->
            if (replaced) {
                currencyEdit = null
                maxSectionEdit = null
                epoch++
            }
            refresh()
        }
    }

    fun onCurrencyChange(text: String) {
        if (text.isBlank()) {
            currencyEdit = text
        } else {
            currencyEdit = null
            store.update { it.copy(general = it.general.copy(currencySymbol = text.trim())) }
        }
        refresh()
    }

    fun onMaxSectionChange(text: String) {
        when (val input = readPositive(text)) {
            is NumberInput.Valid -> {
                maxSectionEdit = null
                store.update { it.copy(general = it.general.copy(tubeLaserMaxSectionMm = input.value)) }
            }
            else -> maxSectionEdit = text
        }
        refresh()
    }

    fun onBoundChange(text: String) {
        boundForm = FieldState(text)
        refresh()
    }

    /** Bending prices stay attached to the range they were entered for (core [io.github.laalka.slav.core.BendRange]). */
    fun addBound() {
        val bounds = store.book.value.general.bendRangeBoundsMm
        val input = readPositive(boundForm.text)
        val error = when {
            input !is NumberInput.Valid -> requiredPositiveError(input)
            bounds.any { it.compareTo(input.value) == 0 } -> "Граница ${input.value.toInputText()} уже есть"
            else -> null
        }
        if (input is NumberInput.Valid && error == null) {
            boundForm = FieldState()
            epoch++
            store.update { it.copy(general = it.general.copy(bendRangeBoundsMm = (bounds + input.value).sorted())) }
        } else {
            boundForm = boundForm.copy(error = error)
        }
        refresh()
    }

    /** [index] is the position in [GeneralState.bendBounds]. */
    fun deleteBound(index: Int) {
        val bound = store.book.value.general.bendRangeBoundsMm.sorted()[index]
        store.update { book ->
            book.copy(general = book.general.copy(bendRangeBoundsMm = book.general.bendRangeBoundsMm.filterNot { it.compareTo(bound) == 0 }))
        }
        refresh()
    }

    fun onWeldTypeChange(text: String) {
        weldTypeForm = FieldState(text)
        refresh()
    }

    fun addWeldType() {
        val name = weldTypeForm.text.trim()
        val error = nameError(name, store.book.value.general.weldTypes, "Тип сварки")
        if (error != null) {
            weldTypeForm = weldTypeForm.copy(error = error)
        } else {
            weldTypeForm = FieldState()
            epoch++
            store.update { it.copy(general = it.general.copy(weldTypes = it.general.weldTypes + name)) }
        }
        refresh()
    }

    /** Its welding table goes with it. */
    fun deleteWeldType(name: String) {
        store.update { book ->
            book.copy(general = book.general.copy(weldTypes = book.general.weldTypes - name)).withoutWeldTypePrices(name)
        }
        refresh()
    }

    fun setTheme(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        refresh()
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): GeneralState {
        val general = store.book.value.general
        val currency = currencyEdit
        val maxSection = maxSectionEdit
        return GeneralState(
            currency = if (currency != null) FieldState(currency, "Введите обозначение валюты") else FieldState(general.currencySymbol),
            bendBounds = general.bendRangeBoundsMm.sorted().map { it.toInputText() },
            bendRanges = general.bendRanges().map { it.label },
            boundForm = boundForm,
            weldTypes = general.weldTypes,
            weldTypeForm = weldTypeForm,
            tubeLaserMaxSection = if (maxSection != null) {
                FieldState(maxSection, requiredPositiveError(readPositive(maxSection)))
            } else {
                FieldState(general.tubeLaserMaxSectionMm.toInputText())
            },
            theme = preferences.themeMode.value,
            epoch = epoch,
        )
    }
}
