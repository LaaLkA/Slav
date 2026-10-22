package io.github.laalka.slav.app.settings

import io.github.laalka.slav.core.Grade
import io.github.laalka.slav.core.MaterialGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GroupItem(val name: String, val gradeNames: List<String>)

data class GradeItem(val name: String, val group: String, val density: FieldState)

/** [group] is the group the new grade goes into; null while there are no groups. */
data class GradeForm(val name: FieldState = FieldState(), val group: String? = null, val density: FieldState = FieldState())

enum class RenameTarget { GROUP, GRADE }

data class Rename(val target: RenameTarget, val oldName: String, val field: FieldState)

data class MaterialsState(
    val groups: List<GroupItem>,
    val grades: List<GradeItem>,
    val groupForm: FieldState,
    val gradeForm: GradeForm,
    val rename: Rename?,
    /** Why the last action was refused, such as deleting a group that grades belong to (spec 4.1). */
    val notice: String?,
    /** Grows when the fields must show the model's text again, dropping what was typed. */
    val epoch: Int,
)

/** «Материалы»: material groups and grades (spec 4.1, 4.2). Renaming goes through the core helpers. */
class MaterialsModel(private val store: SettingsStore) {
    private var groupForm = FieldState()
    private var gradeForm = GradeForm()
    private var rename: Rename? = null
    private var notice: String? = null

    /** Grades whose density field holds text that is not a valid density, with that text. */
    private var densityEdits: Map<String, String> = emptyMap()
    private var epoch = 0

    private val _state = MutableStateFlow(build())
    val state: StateFlow<MaterialsState> = _state.asStateFlow()

    init {
        store.addListener { replaced ->
            if (replaced) {
                rename = null
                densityEdits = emptyMap()
                epoch++
            }
            refresh()
        }
    }

    fun onGroupNameChange(text: String) {
        groupForm = FieldState(text)
        refresh()
    }

    fun addGroup() {
        val name = groupForm.text.trim()
        val error = nameError(name, store.book.value.groups.map { it.name }, "Группа")
        if (error != null) {
            groupForm = groupForm.copy(error = error)
        } else {
            notice = null
            groupForm = FieldState()
            epoch++
            store.update { it.copy(groups = it.groups + MaterialGroup(name)) }
        }
        refresh()
    }

    /** Refused while grades belong to the group (spec 4.1); otherwise its prices go with it. */
    fun deleteGroup(name: String) {
        val members = store.book.value.grades.filter { it.group == name }
        if (members.isNotEmpty()) {
            notice = "Группу «$name» нельзя удалить: к ней привязаны марки ${members.joinToString { "«${it.name}»" }}. " +
                "Перенесите их в другую группу или удалите."
        } else {
            notice = null
            store.update { book -> book.copy(groups = book.groups.filterNot { it.name == name }).withoutGroupPrices(name) }
        }
        refresh()
    }

    fun startRename(target: RenameTarget, name: String) {
        rename = Rename(target, name, FieldState(name))
        notice = null
        refresh()
    }

    fun onRenameChange(text: String) {
        rename = rename?.copy(field = FieldState(text))
        refresh()
    }

    fun confirmRename() {
        val current = checkNotNull(rename) { "No rename in progress" }
        val newName = current.field.text.trim()
        val book = store.book.value
        val error = when (current.target) {
            RenameTarget.GROUP -> nameError(newName, book.groups.map { it.name }.filterNot { it == current.oldName }, "Группа")
            RenameTarget.GRADE -> nameError(newName, book.grades.map { it.name }.filterNot { it == current.oldName }, "Марка")
        }
        if (error != null) {
            rename = current.copy(field = current.field.copy(error = error))
        } else {
            rename = null
            if (current.target == RenameTarget.GRADE) {
                densityEdits[current.oldName]?.let { densityEdits = densityEdits - current.oldName + (newName to it) }
            }
            store.update {
                when (current.target) {
                    RenameTarget.GROUP -> it.renameGroup(current.oldName, newName)
                    RenameTarget.GRADE -> it.renameGrade(current.oldName, newName)
                }
            }
        }
        refresh()
    }

    fun cancelRename() {
        rename = null
        refresh()
    }

    fun dismissNotice() {
        notice = null
        refresh()
    }

    fun onGradeNameChange(text: String) {
        gradeForm = gradeForm.copy(name = FieldState(text))
        refresh()
    }

    fun onGradeGroupChange(group: String) {
        gradeForm = gradeForm.copy(group = group)
        refresh()
    }

    fun onGradeDensityChange(text: String) {
        gradeForm = gradeForm.copy(density = FieldState(text))
        refresh()
    }

    fun addGrade() {
        val book = store.book.value
        val name = gradeForm.name.text.trim()
        val nameError = nameError(name, book.grades.map { it.name }, "Марка")
        val density = readPositive(gradeForm.density.text)
        val densityError = requiredPositiveError(density)
        val group = effectiveGroup()
        when {
            group == null -> notice = "Сначала добавьте группу материалов"
            nameError != null || density !is NumberInput.Valid -> gradeForm = gradeForm.copy(
                name = gradeForm.name.copy(error = nameError),
                density = gradeForm.density.copy(error = densityError),
            )
            else -> {
                notice = null
                gradeForm = GradeForm(group = group)
                epoch++
                store.update { it.copy(grades = it.grades + Grade(name, group, density.value)) }
            }
        }
        refresh()
    }

    fun setGradeGroup(grade: String, group: String) {
        store.update { book -> book.copy(grades = book.grades.map { if (it.name == grade) it.copy(group = group) else it }) }
        refresh()
    }

    fun onDensityChange(grade: String, text: String) {
        when (val input = readPositive(text)) {
            is NumberInput.Valid -> {
                densityEdits = densityEdits - grade
                store.update { book ->
                    book.copy(grades = book.grades.map { if (it.name == grade) it.copy(densityKgM3 = input.value) else it })
                }
            }
            else -> densityEdits = densityEdits + (grade to text)
        }
        refresh()
    }

    /** Saved calculations keep their own copy of the prices (spec 8.4), so a grade can always be deleted. */
    fun deleteGrade(name: String) {
        densityEdits = densityEdits - name
        notice = null
        store.update { book -> book.copy(grades = book.grades.filterNot { it.name == name }).withoutGradePrices(name) }
        refresh()
    }

    /** The chosen group while it exists, otherwise the first one. */
    private fun effectiveGroup(): String? {
        val groups = store.book.value.groups.map { it.name }
        return gradeForm.group?.takeIf { it in groups } ?: groups.firstOrNull()
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): MaterialsState {
        val book = store.book.value
        val grades = book.grades.map { grade ->
            val typed = densityEdits[grade.name]
            val density = if (typed != null) {
                FieldState(typed, requiredPositiveError(readPositive(typed)))
            } else {
                FieldState(grade.densityKgM3.toInputText())
            }
            GradeItem(grade.name, grade.group, density)
        }
        return MaterialsState(
            groups = book.groups.map { group -> GroupItem(group.name, book.grades.filter { it.group == group.name }.map { it.name }) },
            grades = grades,
            groupForm = groupForm,
            gradeForm = gradeForm.copy(group = effectiveGroup()),
            rename = rename,
            notice = notice,
            epoch = epoch,
        )
    }
}
