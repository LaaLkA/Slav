package io.github.laalka.slav.app.library

import io.github.laalka.slav.data.PartTemplateRepository
import io.github.laalka.slav.data.TemplateWriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

const val NAME_REQUIRED = "Введите название"

/** A name is taken when another template has it, trimmed and in any letter case. */
fun nameTakenMessage(name: String) = "Деталь «${name.trim()}» уже есть в библиотеке"

/** The rename in progress: [name] is the current name, [error] says what is wrong with the one typed. */
data class RenameQuestion(val name: String, val error: String?)

data class LibraryState(
    val query: String,
    val rows: List<LibraryRow>,
    /** Nothing is stored at all: the screen says how the library is filled instead of showing the search. */
    val isEmpty: Boolean,
    val renameQuestion: RenameQuestion?,
    /** The name of the template waiting for the confirmation of its deletion. */
    val deleteQuestion: String?,
)

/**
 * «Детали» (the library of ready parts): search, rename and delete. A template is not a
 * calculation, so deleting one asks for a confirmation but no administrator password. Created once
 * per window, so the search stays while the user visits other screens; [reload] reads the list again
 * when the page is shown.
 */
class LibraryModel(private val templates: PartTemplateRepository) {
    private var query = ""
    private var renaming: Renaming? = null
    private var deleting: UUID? = null

    private class Renaming(val id: UUID, val name: String, var text: String, var error: String? = null)

    private val _state = MutableStateFlow(build())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    fun reload() = refresh()

    fun onQueryChange(text: String) {
        query = text
        refresh()
    }

    /** «Переименовать»: the name typed starts as the current one. */
    fun requestRename(id: UUID) {
        renaming = templates.get(id)?.let { Renaming(id, it.name, text = it.name) }
        refresh()
    }

    fun onRenameChange(text: String) {
        renaming?.let {
            it.text = text
            it.error = null
        }
        refresh()
    }

    /** Renames the template; a blank name or one another template has stays in the question with its error. */
    fun confirmRename() {
        val rename = renaming ?: return
        when {
            rename.text.isBlank() -> rename.error = NAME_REQUIRED
            rename.text.trim() == rename.name -> renaming = null
            else -> when (templates.rename(rename.id, rename.text)) {
                is TemplateWriteResult.Written -> renaming = null
                is TemplateWriteResult.NameTaken -> rename.error = nameTakenMessage(rename.text)
            }
        }
        refresh()
    }

    fun cancelRename() {
        renaming = null
        refresh()
    }

    /** «Удалить»: asks first, deletes nothing yet. */
    fun requestDelete(id: UUID) {
        deleting = id.takeIf { templates.get(it) != null }
        refresh()
    }

    fun confirmDelete() {
        deleting?.let(templates::delete)
        deleting = null
        refresh()
    }

    fun cancelDelete() {
        deleting = null
        refresh()
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): LibraryState {
        val rows = templates.list(query).map { it.toRow() }
        return LibraryState(
            query = query,
            rows = rows,
            isEmpty = rows.isEmpty() && (query.isBlank() || templates.list().isEmpty()),
            renameQuestion = renaming?.let { RenameQuestion(it.name, it.error) },
            deleteQuestion = deleting?.let(templates::get)?.name,
        )
    }
}
