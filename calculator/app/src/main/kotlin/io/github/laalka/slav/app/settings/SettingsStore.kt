package io.github.laalka.slav.app.settings

import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The settings the tabs edit (spec 4). Every change is saved through [SettingsRepository] at once,
 * and every tab model is told about it, whichever tab made it: a thickness added in «Сортамент»
 * is a new column in the price tables right away.
 */
class SettingsStore(private val repository: SettingsRepository) {
    private val _book = MutableStateFlow(repository.load())
    val book: StateFlow<PriceBook> = _book.asStateFlow()

    private val listeners = mutableListOf<(replaced: Boolean) -> Unit>()

    /**
     * [listener] runs after every change; `replaced` is true when the settings were replaced from
     * outside the editors (an import), so text typed into the fields no longer applies.
     */
    fun addListener(listener: (replaced: Boolean) -> Unit) {
        listeners += listener
    }

    fun update(transform: (PriceBook) -> PriceBook) {
        val next = transform(_book.value)
        if (next == _book.value) return
        repository.save(next)
        _book.value = next
        listeners.forEach { it(false) }
    }

    /** Takes the stored settings after something else saved them. */
    fun reload() {
        _book.value = repository.load()
        listeners.forEach { it(true) }
    }
}
