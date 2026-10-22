package io.github.laalka.slav.app.settings

import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BeamSize
import io.github.laalka.slav.core.ProfileTubeSize
import io.github.laalka.slav.core.RoundTubeSize
import io.github.laalka.slav.core.Sortament
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

/** A column of a sortament list; every column but a designation holds a size above zero. */
data class SizeField(val label: String, val isDesignation: Boolean = false)

private val beamFields = listOf(
    SizeField("Обозначение", isDesignation = true),
    SizeField("Высота h, мм"),
    SizeField("Полка b, мм"),
    SizeField("Толщина s, мм"),
    SizeField("Масса 1 м, кг"),
)

/** The rolled kinds of spec 4.3 and the columns of their sortament lists. */
enum class SortamentKind(val title: String, val fields: List<SizeField>) {
    SHEET("Лист", listOf(SizeField("Толщина t, мм"))),
    ROUND_TUBE("Труба круглая", listOf(SizeField("Диаметр D, мм"), SizeField("Стенка t, мм"))),
    PROFILE_TUBE("Труба профильная", listOf(SizeField("Сторона A, мм"), SizeField("Сторона B, мм"), SizeField("Стенка t, мм"))),
    ANGLE(
        "Уголок",
        listOf(
            SizeField("Обозначение", isDesignation = true),
            SizeField("Полка a, мм"),
            SizeField("Полка b, мм"),
            SizeField("Толщина s, мм"),
            SizeField("Масса 1 м, кг"),
        ),
    ),
    CHANNEL("Швеллер", beamFields),
    I_BEAM("Двутавр", beamFields),
}

/** The add form of one list: a field per column, and [error] for the row as a whole. */
data class SizeForm(val fields: List<FieldState>, val error: String? = null)

data class SortamentSection(val kind: SortamentKind, val rows: List<List<String>>, val form: SizeForm)

data class SortamentState(
    val sections: List<SortamentSection>,
    /** Grows when the fields must show the model's text again, dropping what was typed. */
    val epoch: Int,
)

/** «Сортамент»: the size lists the user picks from when entering a part (spec 4.3). */
class SortamentModel(private val store: SettingsStore) {
    private var forms: Map<SortamentKind, SizeForm> = SortamentKind.entries.associateWith(::emptyForm)
    private var epoch = 0

    private val _state = MutableStateFlow(build())
    val state: StateFlow<SortamentState> = _state.asStateFlow()

    init {
        store.addListener { replaced ->
            if (replaced) epoch++
            refresh()
        }
    }

    fun onFieldChange(kind: SortamentKind, index: Int, text: String) {
        val form = forms.getValue(kind)
        forms = forms + (kind to SizeForm(form.fields.toMutableList().also { it[index] = FieldState(text) }))
        refresh()
    }

    fun add(kind: SortamentKind) {
        val form = forms.getValue(kind)
        val checked = kind.fields.zip(form.fields).map { (field, state) ->
            if (field.isDesignation) {
                state.copy(error = if (state.text.isBlank()) REQUIRED else null)
            } else {
                state.copy(error = requiredPositiveError(readPositive(state.text)))
            }
        }
        if (checked.any { it.error != null }) {
            forms = forms + (kind to SizeForm(checked))
        } else {
            val columns = kind.fields.zip(form.fields)
            val row = SizeRow(
                designation = columns.singleOrNull { it.first.isDesignation }?.second?.text?.trim().orEmpty(),
                sizes = columns.filterNot { it.first.isDesignation }.map { checkNotNull(parseDecimal(it.second.text)) },
            )
            val sortament = store.book.value.sortament
            if (kind.contains(sortament, row)) {
                forms = forms + (kind to form.copy(error = "Такой размер уже есть в списке"))
            } else {
                forms = forms + (kind to emptyForm(kind))
                epoch++
                store.update { it.copy(sortament = kind.withRow(it.sortament, row)) }
            }
        }
        refresh()
    }

    /** Prices entered for the size stay in the settings: a size added back finds them again. */
    fun delete(kind: SortamentKind, index: Int) {
        store.update { it.copy(sortament = kind.withoutRow(it.sortament, index)) }
        refresh()
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): SortamentState {
        val sortament = store.book.value.sortament
        return SortamentState(
            sections = SortamentKind.entries.map { SortamentSection(it, it.rows(sortament), forms.getValue(it)) },
            epoch = epoch,
        )
    }

    private companion object {
        fun emptyForm(kind: SortamentKind) = SizeForm(kind.fields.map { FieldState() })
    }
}

/** A parsed form row: [designation] is empty for kinds without one, [sizes] are in column order. */
private class SizeRow(val designation: String, val sizes: List<BigDecimal>)

private fun SortamentKind.rows(s: Sortament): List<List<String>> = when (this) {
    SortamentKind.SHEET -> s.sheetThicknessesMm.map { listOf(it.toInputText()) }
    SortamentKind.ROUND_TUBE -> s.roundTubes.map { sizes(it.diameterMm, it.wallMm) }
    SortamentKind.PROFILE_TUBE -> s.profileTubes.map { sizes(it.sideAMm, it.sideBMm, it.wallMm) }
    SortamentKind.ANGLE -> s.angles.map { listOf(it.designation) + sizes(it.flangeAMm, it.flangeBMm, it.thicknessMm, it.massPerMeterKg) }
    SortamentKind.CHANNEL -> s.channels.map(::beamRow)
    SortamentKind.I_BEAM -> s.iBeams.map(::beamRow)
}

/** Numeric lists are kept sorted by size; designated sections stay in the order the user entered them. */
private fun SortamentKind.withRow(s: Sortament, row: SizeRow): Sortament {
    val n = row.sizes
    return when (this) {
        SortamentKind.SHEET -> s.copy(sheetThicknessesMm = (s.sheetThicknessesMm + n[0]).sorted())
        SortamentKind.ROUND_TUBE -> s.copy(
            roundTubes = (s.roundTubes + RoundTubeSize(n[0], n[1]))
                .sortedWith(compareBy<RoundTubeSize> { it.diameterMm }.thenBy { it.wallMm }),
        )
        SortamentKind.PROFILE_TUBE -> s.copy(
            profileTubes = (s.profileTubes + ProfileTubeSize(n[0], n[1], n[2]))
                .sortedWith(compareBy<ProfileTubeSize> { it.sideAMm }.thenBy { it.sideBMm }.thenBy { it.wallMm }),
        )
        SortamentKind.ANGLE -> s.copy(angles = s.angles + AngleSize(row.designation, n[0], n[1], n[2], n[3]))
        SortamentKind.CHANNEL -> s.copy(channels = s.channels + BeamSize(row.designation, n[0], n[1], n[2], n[3]))
        SortamentKind.I_BEAM -> s.copy(iBeams = s.iBeams + BeamSize(row.designation, n[0], n[1], n[2], n[3]))
    }
}

private fun SortamentKind.withoutRow(s: Sortament, index: Int): Sortament = when (this) {
    SortamentKind.SHEET -> s.copy(sheetThicknessesMm = s.sheetThicknessesMm.without(index))
    SortamentKind.ROUND_TUBE -> s.copy(roundTubes = s.roundTubes.without(index))
    SortamentKind.PROFILE_TUBE -> s.copy(profileTubes = s.profileTubes.without(index))
    SortamentKind.ANGLE -> s.copy(angles = s.angles.without(index))
    SortamentKind.CHANNEL -> s.copy(channels = s.channels.without(index))
    SortamentKind.I_BEAM -> s.copy(iBeams = s.iBeams.without(index))
}

/** Matched the way an import matches sizes (spec 9): numbers by value, designations trimmed in any case. */
private fun SortamentKind.contains(s: Sortament, row: SizeRow): Boolean {
    val n = row.sizes
    fun same(a: BigDecimal, b: BigDecimal) = a.compareTo(b) == 0
    fun sameName(a: String) = a.trim().equals(row.designation, ignoreCase = true)
    return when (this) {
        SortamentKind.SHEET -> s.sheetThicknessesMm.any { same(it, n[0]) }
        SortamentKind.ROUND_TUBE -> s.roundTubes.any { same(it.diameterMm, n[0]) && same(it.wallMm, n[1]) }
        SortamentKind.PROFILE_TUBE -> s.profileTubes.any { same(it.sideAMm, n[0]) && same(it.sideBMm, n[1]) && same(it.wallMm, n[2]) }
        SortamentKind.ANGLE -> s.angles.any { sameName(it.designation) }
        SortamentKind.CHANNEL -> s.channels.any { sameName(it.designation) }
        SortamentKind.I_BEAM -> s.iBeams.any { sameName(it.designation) }
    }
}

private fun sizes(vararg values: BigDecimal) = values.map { it.toInputText() }

private fun beamRow(size: BeamSize) = listOf(size.designation) + sizes(size.heightMm, size.flangeWidthMm, size.thicknessMm, size.massPerMeterKg)

private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }
