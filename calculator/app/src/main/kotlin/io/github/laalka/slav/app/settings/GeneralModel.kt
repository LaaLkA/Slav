package io.github.laalka.slav.app.settings

import io.github.laalka.slav.app.AppPreferences
import io.github.laalka.slav.app.ThemeMode
import io.github.laalka.slav.data.AdminPassword
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is wrong with the password form, field by field; [success] confirms the last change. */
data class PasswordFormState(
    val currentError: String? = null,
    val newError: String? = null,
    val repeatError: String? = null,
    val success: String? = null,
)

const val WRONG_CURRENT_PASSWORD = "Неверный текущий пароль"
const val PASSWORD_TOO_SHORT = "Не короче ${AdminPassword.MIN_LENGTH} символов"
const val PASSWORDS_DIFFER = "Пароли не совпадают"
const val PASSWORD_CHANGED = "Пароль администратора изменён"

data class GeneralState(
    val currency: FieldState,
    /** Bend-length range bounds, ascending, and the ranges they make: «до 100», «100–300», … */
    val bendBounds: List<String>,
    val bendRanges: List<String>,
    val boundForm: FieldState,
    val weldTypes: List<String>,
    val weldTypeForm: FieldState,
    val tubeLaserMaxSection: FieldState,
    /** The least painted area a part is charged for in product painting, m². */
    val minPaintAreaPerPart: FieldState,
    val theme: ThemeMode,
    val password: PasswordFormState,
    /** Grows when the fields must show the model's text again, dropping what was typed. */
    val epoch: Int,
)

/**
 * «Общие» (spec 4.6), the colour theme (spec 11) and the administrator password, which deleting
 * a calculation asks for. The password is not part of the settings: it is not exported, imported or saved with them.
 */
class GeneralModel(
    private val store: SettingsStore,
    private val preferences: AppPreferences,
    private val adminPassword: AdminPassword,
) {
    /** Text of a field that cannot be saved as it is, null while the field shows the saved value. */
    private var currencyEdit: String? = null
    private var maxSectionEdit: String? = null
    private var minPaintAreaEdit: String? = null
    private var boundForm = FieldState()
    private var weldTypeForm = FieldState()
    private var epoch = 0

    // The texts of the password fields are kept only until the form is submitted.
    private var currentPassword = ""
    private var newPassword = ""
    private var repeatPassword = ""
    private var passwordForm = PasswordFormState()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<GeneralState> = _state.asStateFlow()

    init {
        store.addListener { replaced ->
            if (replaced) {
                currencyEdit = null
                maxSectionEdit = null
                minPaintAreaEdit = null
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

    /** Zero is allowed: it switches the minimum off. */
    fun onMinPaintAreaChange(text: String) {
        when (val input = readPrice(text)) {
            is NumberInput.Valid -> {
                minPaintAreaEdit = null
                store.update { it.copy(general = it.general.copy(minPaintAreaPerPartM2 = input.value)) }
            }
            else -> minPaintAreaEdit = text
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

    fun onCurrentPasswordChange(text: String) {
        currentPassword = text
        passwordForm = passwordForm.copy(currentError = null, success = null)
        refresh()
    }

    fun onNewPasswordChange(text: String) {
        newPassword = text
        passwordForm = passwordForm.copy(newError = null, repeatError = null, success = null)
        refresh()
    }

    fun onRepeatPasswordChange(text: String) {
        repeatPassword = text
        passwordForm = passwordForm.copy(repeatError = null, success = null)
        refresh()
    }

    /** Every wrong field is flagged at once; nothing is changed until the new password is long enough, repeated and the current one is right. */
    fun changePassword() {
        val newError = if (newPassword.length < AdminPassword.MIN_LENGTH) PASSWORD_TOO_SHORT else null
        val repeatError = if (repeatPassword != newPassword) PASSWORDS_DIFFER else null
        if (newError != null || repeatError != null) {
            passwordForm = PasswordFormState(newError = newError, repeatError = repeatError)
        } else if (!adminPassword.change(currentPassword.toCharArray(), newPassword.toCharArray())) {
            passwordForm = PasswordFormState(currentError = WRONG_CURRENT_PASSWORD)
        } else {
            currentPassword = ""
            newPassword = ""
            repeatPassword = ""
            passwordForm = PasswordFormState(success = PASSWORD_CHANGED)
            // The fields are re-created empty.
            epoch++
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
        val minPaintArea = minPaintAreaEdit
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
            minPaintAreaPerPart = if (minPaintArea != null) {
                FieldState(minPaintArea, requiredPositiveError(readPrice(minPaintArea)))
            } else {
                FieldState(general.minPaintAreaPerPartM2.toInputText())
            },
            theme = preferences.themeMode.value,
            password = passwordForm,
            epoch = epoch,
        )
    }
}
