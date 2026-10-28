package io.github.laalka.slav.app.library

import io.github.laalka.slav.core.Part
import io.github.laalka.slav.data.PartTemplateRepository
import io.github.laalka.slav.data.TemplateWriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * «В библиотеку»: [name] is what the field starts with. [replacing] is the name of the template
 * that already has the name typed: the dialog then asks «Заменить?» instead of showing the field.
 */
data class SavePartDialog(val name: String, val error: String?, val replacing: String?)

/** «Добавить из библиотеки»: the templates whose name has [query]; [libraryIsEmpty] when there is none at all. */
data class PickPartDialog(val query: String, val rows: List<LibraryRow>, val libraryIsEmpty: Boolean)

data class EditorLibraryState(
    val save: SavePartDialog?,
    val pick: PickPartDialog?,
    /** What the last save or insert did, shown in the editor until it is dismissed. */
    val notice: String?,
)

/**
 * The two dialogs of the editor that go to the library. [partOf] reads the part [id] as the form
 * holds it now (null when it is gone); [insert] adds a part to the form. A part is copied both
 * ways, so the library and the calculation never share it.
 */
class EditorLibraryModel(
    private val templates: PartTemplateRepository,
    private val partOf: (Long) -> Part?,
    private val insert: (Part) -> Unit,
) {
    private var saving: Saving? = null
    private var picking: String? = null
    private var notice: String? = null

    /** The part is taken when the dialog opens: the dialog is modal, so the part cannot change meanwhile. */
    private class Saving(val part: Part, var name: String, var error: String? = null, var replacing: String? = null)

    private val _state = MutableStateFlow(build())
    val state: StateFlow<EditorLibraryState> = _state.asStateFlow()

    /** «В библиотеку» on the part card: the name asked for starts as the part's name. */
    fun requestSave(part: Long) {
        val taken = partOf(part) ?: return
        saving = Saving(taken, name = taken.name)
        refresh()
    }

    fun onSaveNameChange(text: String) {
        saving?.let {
            it.name = text
            it.error = null
        }
        refresh()
    }

    /** Saves under the name typed; when the library has that name the dialog asks «Заменить?» and saves nothing yet. */
    fun confirmSave() {
        val dialog = saving ?: return
        if (dialog.name.isBlank()) {
            dialog.error = NAME_REQUIRED
            refresh()
            return
        }
        when (val result = templates.save(dialog.name, dialog.part)) {
            is TemplateWriteResult.Written -> finishSave("Деталь «${result.template.name}» сохранена в библиотеку")
            is TemplateWriteResult.NameTaken -> {
                dialog.replacing = result.existing.name
                refresh()
            }
        }
    }

    /** «Заменить»: the template that has the name typed gets this part and keeps its name. */
    fun confirmReplace() {
        val dialog = saving ?: return
        val existing = dialog.replacing ?: return
        templates.save(dialog.name, dialog.part, overwrite = true)
        finishSave("Деталь «$existing» в библиотеке заменена")
    }

    /** Back from «Заменить?» to the name, to type another one. */
    fun backToName() {
        saving?.replacing = null
        refresh()
    }

    fun cancelSave() {
        saving = null
        refresh()
    }

    fun openPick() {
        picking = ""
        refresh()
    }

    fun onPickQueryChange(text: String) {
        if (picking == null) return
        picking = text
        refresh()
    }

    /** Adds a copy of the template [id] to the calculation and closes the dialog; a template that is gone adds nothing. */
    fun pick(id: UUID) {
        val template = templates.get(id)
        picking = null
        if (template != null) {
            insert(template.part)
            notice = "Деталь «${template.name}» добавлена в конец списка деталей"
        }
        refresh()
    }

    fun closePick() {
        picking = null
        refresh()
    }

    fun dismissNotice() {
        notice = null
        refresh()
    }

    private fun finishSave(message: String) {
        saving = null
        notice = message
        refresh()
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build() = EditorLibraryState(
        save = saving?.let { SavePartDialog(it.name, it.error, it.replacing) },
        pick = picking?.let { query ->
            val rows = templates.list(query).map { it.toRow() }
            PickPartDialog(query, rows, libraryIsEmpty = rows.isEmpty() && (query.isBlank() || templates.list().isEmpty()))
        },
        notice = notice,
    )
}
