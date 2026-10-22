package io.github.laalka.slav.data

import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BeamSize
import io.github.laalka.slav.core.GeneralSettings
import io.github.laalka.slav.core.GroupPrice
import io.github.laalka.slav.core.MaterialGroup
import io.github.laalka.slav.core.PriceBook
import java.math.BigDecimal

/** A part of the settings (spec 4.1–4.6) that an import summary counts on its own. */
enum class SettingsSection(val title: String) {
    GROUPS("Группы материалов"),
    GRADES("Марки"),
    SHEET_THICKNESSES("Лист: толщины"),
    ROUND_TUBES("Трубы круглые"),
    PROFILE_TUBES("Трубы профильные"),
    ANGLES("Уголки"),
    CHANNELS("Швеллеры"),
    I_BEAMS("Двутавры"),
    MATERIAL_PRICES("Цены материала"),
    LASER_SHEET_CUT("Лазер, лист"),
    LASER_SHEET_PIERCE("Пробитие, лист"),
    TUBE_LASER_CUT("Лазер, труборез"),
    TUBE_LASER_PIERCE("Пробитие, труборез"),
    BENDING("Гибка"),
    WELDING("Сварка"),
    PAINTING("Покраска порошковая"),
    BEND_RANGE_BOUNDS("Границы диапазонов гиба"),
    WELD_TYPES("Типы сварки"),

    /** Currency symbol and the tube-laser section limit. */
    GENERAL("Общие"),
}

/** How many elements of one section an import adds, changes and removes. */
data class SectionChanges(val added: Int, val changed: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && changed == 0 && removed == 0
}

/** What an import would do, shown before it is applied (spec 9); every section is listed. */
data class ImportSummary(val sections: Map<SettingsSection, SectionChanges>) {
    val added: Int get() = sections.values.sumOf { it.added }
    val changed: Int get() = sections.values.sumOf { it.changed }
    val removed: Int get() = sections.values.sumOf { it.removed }
    val hasChanges: Boolean get() = sections.values.any { !it.isEmpty }

    operator fun get(section: SettingsSection): SectionChanges = sections.getValue(section)
}

internal class MergeResult(val merged: PriceBook, val summary: ImportSummary)

/** The summary of replacing [local] by [incoming] whole: names spelled differently count as changes. */
internal fun replaceSummary(local: PriceBook, incoming: PriceBook): ImportSummary {
    val changes = sections.associate { it.id to it.replaceChanges(local, incoming) }
    return summaryOf(changes + (SettingsSection.GENERAL to generalChanges(local.general, incoming.general)))
}

/**
 * Merges [incoming] into [local] by name (spec 9): elements that match take the values from the
 * file, new ones are added, ones the file lacks stay. A matched element keeps its local spelling,
 * and the references to it in the file are respelled the same way: saved calculations and price lookups
 * compare names exactly, so a merged «ст3 » must stay «Ст3».
 */
internal fun mergeSettings(local: PriceBook, incoming: PriceBook): MergeResult {
    val respelled = incoming.spelledAs(local)
    val changes = LinkedHashMap<SettingsSection, SectionChanges>()
    var merged = local
    for (section in sections) {
        val (book, sectionChanges) = section.merge(merged, local, respelled)
        merged = book
        changes[section.id] = sectionChanges
    }
    changes[SettingsSection.GENERAL] = generalChanges(local.general, respelled.general)
    merged = merged.copy(
        general = merged.general.copy(
            currencySymbol = respelled.general.currencySymbol,
            tubeLaserMaxSectionMm = respelled.general.tubeLaserMaxSectionMm,
        ),
    )
    return MergeResult(merged, summaryOf(changes))
}

/** Names match trimmed and in any letter case. */
private fun nameKey(name: String) = name.trim().lowercase()

/** Sizes match by value: 2 and 2.0 are one thickness, though BigDecimal.equals tells them apart. */
private fun sizeKey(size: BigDecimal): BigDecimal = size.stripTrailingZeros()

/**
 * One list of the settings. [key] identifies an element across two price books; [value] is what a
 * matched element is compared on to count a change, its decimals normalized like the keys.
 */
private class Section<T>(
    val id: SettingsSection,
    val elements: (PriceBook) -> List<T>,
    val withElements: PriceBook.(List<T>) -> PriceBook,
    val key: (T) -> Any?,
    val value: (T) -> Any?,
)

private fun groupTable(
    id: SettingsSection,
    elements: (PriceBook) -> List<GroupPrice>,
    withElements: PriceBook.(List<GroupPrice>) -> PriceBook,
) = Section(
    id, elements, withElements,
    key = { listOf(nameKey(it.group), sizeKey(it.thicknessMm)) },
    value = { listOf(it.group, sizeKey(it.thicknessMm), sizeKey(it.price)) },
)

private fun angleKey(size: AngleSize) = nameKey(size.designation)

private fun angleValue(size: AngleSize) = listOf(
    size.designation, sizeKey(size.flangeAMm), sizeKey(size.flangeBMm), sizeKey(size.thicknessMm), sizeKey(size.massPerMeterKg),
)

private fun beamKey(size: BeamSize) = nameKey(size.designation)

private fun beamValue(size: BeamSize) = listOf(
    size.designation, sizeKey(size.heightMm), sizeKey(size.flangeWidthMm), sizeKey(size.thicknessMm), sizeKey(size.massPerMeterKg),
)

private val sections: List<Section<*>> = listOf(
    Section(
        SettingsSection.GROUPS, { it.groups }, { copy(groups = it) },
        key = { nameKey(it.name) }, value = { it.name },
    ),
    Section(
        SettingsSection.GRADES, { it.grades }, { copy(grades = it) },
        key = { nameKey(it.name) }, value = { listOf(it.name, it.group, sizeKey(it.densityKgM3)) },
    ),
    Section(
        SettingsSection.SHEET_THICKNESSES, { it.sortament.sheetThicknessesMm },
        { copy(sortament = sortament.copy(sheetThicknessesMm = it)) },
        key = ::sizeKey, value = ::sizeKey,
    ),
    Section(
        SettingsSection.ROUND_TUBES, { it.sortament.roundTubes }, { copy(sortament = sortament.copy(roundTubes = it)) },
        key = { listOf(sizeKey(it.diameterMm), sizeKey(it.wallMm)) },
        value = { listOf(sizeKey(it.diameterMm), sizeKey(it.wallMm)) },
    ),
    Section(
        SettingsSection.PROFILE_TUBES, { it.sortament.profileTubes }, { copy(sortament = sortament.copy(profileTubes = it)) },
        key = { listOf(sizeKey(it.sideAMm), sizeKey(it.sideBMm), sizeKey(it.wallMm)) },
        value = { listOf(sizeKey(it.sideAMm), sizeKey(it.sideBMm), sizeKey(it.wallMm)) },
    ),
    Section(
        SettingsSection.ANGLES, { it.sortament.angles }, { copy(sortament = sortament.copy(angles = it)) },
        key = ::angleKey, value = ::angleValue,
    ),
    Section(
        SettingsSection.CHANNELS, { it.sortament.channels }, { copy(sortament = sortament.copy(channels = it)) },
        key = ::beamKey, value = ::beamValue,
    ),
    Section(
        SettingsSection.I_BEAMS, { it.sortament.iBeams }, { copy(sortament = sortament.copy(iBeams = it)) },
        key = ::beamKey, value = ::beamValue,
    ),
    Section(
        SettingsSection.MATERIAL_PRICES, { it.materialPricesPerKg }, { copy(materialPricesPerKg = it) },
        key = { listOf(nameKey(it.grade), sizeKey(it.thicknessMm)) },
        value = { listOf(it.grade, sizeKey(it.thicknessMm), sizeKey(it.pricePerKg)) },
    ),
    groupTable(SettingsSection.LASER_SHEET_CUT, { it.laserSheetCutPerCm }, { copy(laserSheetCutPerCm = it) }),
    groupTable(SettingsSection.LASER_SHEET_PIERCE, { it.laserSheetPiercePerPiece }, { copy(laserSheetPiercePerPiece = it) }),
    groupTable(SettingsSection.TUBE_LASER_CUT, { it.tubeLaserCutPerCm }, { copy(tubeLaserCutPerCm = it) }),
    groupTable(SettingsSection.TUBE_LASER_PIERCE, { it.tubeLaserPiercePerPiece }, { copy(tubeLaserPiercePerPiece = it) }),
    Section(
        SettingsSection.BENDING, { it.bendingPerBend }, { copy(bendingPerBend = it) },
        key = { listOf(nameKey(it.group), sizeKey(it.thicknessMm), it.upToMm?.let(::sizeKey)) },
        value = { listOf(it.group, sizeKey(it.thicknessMm), it.upToMm?.let(::sizeKey), sizeKey(it.price)) },
    ),
    Section(
        SettingsSection.WELDING, { it.weldingPerCm }, { copy(weldingPerCm = it) },
        key = { listOf(nameKey(it.weldType), nameKey(it.group), sizeKey(it.thicknessMm)) },
        value = { listOf(it.weldType, it.group, sizeKey(it.thicknessMm), sizeKey(it.pricePerCm)) },
    ),
    // A single value: a file without it leaves the local price, as any element the file lacks.
    Section(
        SettingsSection.PAINTING, { listOfNotNull(it.paintingPerM2) }, { copy(paintingPerM2 = it.singleOrNull()) },
        key = { Unit }, value = ::sizeKey,
    ),
    Section(
        SettingsSection.BEND_RANGE_BOUNDS, { it.general.bendRangeBoundsMm },
        { copy(general = general.copy(bendRangeBoundsMm = it)) },
        key = ::sizeKey, value = ::sizeKey,
    ),
    Section(
        SettingsSection.WELD_TYPES, { it.general.weldTypes }, { copy(general = general.copy(weldTypes = it)) },
        key = ::nameKey, value = { it },
    ),
)

/** [into] with this section merged; [local] and [incoming] are compared whole. */
private fun <T> Section<T>.merge(into: PriceBook, local: PriceBook, incoming: PriceBook): Pair<PriceBook, SectionChanges> {
    val localElements = elements(local)
    val localKeys = localElements.mapTo(HashSet(), key)
    val incomingByKey = elements(incoming).firstByKey(key)
    val taken = HashSet<Any?>()
    var changed = 0
    val kept = localElements.map { element ->
        val k = key(element)
        val replacement = incomingByKey[k]
        // Of local elements sharing a key only the first takes the file's value; the rest stay as they are.
        if (replacement == null || !taken.add(k)) {
            element
        } else {
            if (value(replacement) != value(element)) changed++
            replacement
        }
    }
    val added = incomingByKey.filterKeys { it !in localKeys }.values
    return into.withElements(kept + added) to SectionChanges(added = added.size, changed = changed, removed = 0)
}

private fun <T> Section<T>.replaceChanges(local: PriceBook, incoming: PriceBook): SectionChanges {
    val localByKey = elements(local).firstByKey(key)
    val incomingByKey = elements(incoming).firstByKey(key)
    return SectionChanges(
        added = incomingByKey.keys.count { it !in localByKey },
        changed = incomingByKey.count { (k, element) -> localByKey[k]?.let { value(it) != value(element) } ?: false },
        removed = localByKey.keys.count { it !in incomingByKey },
    )
}

/** The first element of each key, in list order. */
private fun <T> List<T>.firstByKey(key: (T) -> Any?): Map<Any?, T> {
    val byKey = LinkedHashMap<Any?, T>()
    for (element in this) {
        val k = key(element)
        if (k !in byKey) byKey[k] = element
    }
    return byKey
}

private fun generalChanges(local: GeneralSettings, incoming: GeneralSettings) = SectionChanges(
    added = 0,
    changed = listOf(
        local.currencySymbol != incoming.currencySymbol,
        local.tubeLaserMaxSectionMm.compareTo(incoming.tubeLaserMaxSectionMm) != 0,
    ).count { it },
    removed = 0,
)

private fun summaryOf(changes: Map<SettingsSection, SectionChanges>) =
    ImportSummary(SettingsSection.entries.associateWith { changes.getValue(it) })

/**
 * This price book with every group, grade, weld type and section designation that matches a name
 * of [local] spelled as in [local].
 */
private fun PriceBook.spelledAs(local: PriceBook): PriceBook {
    val group = spelling(local.groups.map { it.name })
    val grade = spelling(local.grades.map { it.name })
    val weldType = spelling(local.general.weldTypes)
    val angle = spelling(local.sortament.angles.map { it.designation })
    val channel = spelling(local.sortament.channels.map { it.designation })
    val iBeam = spelling(local.sortament.iBeams.map { it.designation })
    fun List<GroupPrice>.respelled() = map { it.copy(group = group(it.group)) }
    return copy(
        general = general.copy(weldTypes = general.weldTypes.map(weldType)),
        groups = groups.map { MaterialGroup(group(it.name)) },
        grades = grades.map { it.copy(name = grade(it.name), group = group(it.group)) },
        sortament = sortament.copy(
            angles = sortament.angles.map { it.copy(designation = angle(it.designation)) },
            channels = sortament.channels.map { it.copy(designation = channel(it.designation)) },
            iBeams = sortament.iBeams.map { it.copy(designation = iBeam(it.designation)) },
        ),
        materialPricesPerKg = materialPricesPerKg.map { it.copy(grade = grade(it.grade)) },
        laserSheetCutPerCm = laserSheetCutPerCm.respelled(),
        laserSheetPiercePerPiece = laserSheetPiercePerPiece.respelled(),
        tubeLaserCutPerCm = tubeLaserCutPerCm.respelled(),
        tubeLaserPiercePerPiece = tubeLaserPiercePerPiece.respelled(),
        bendingPerBend = bendingPerBend.map { it.copy(group = group(it.group)) },
        weldingPerCm = weldingPerCm.map { it.copy(weldType = weldType(it.weldType), group = group(it.group)) },
    )
}

/** Maps a name to the first of [localNames] it matches, or leaves it as it is. */
private fun spelling(localNames: List<String>): (String) -> String {
    val byKey = HashMap<String, String>()
    for (name in localNames) byKey.putIfAbsent(nameKey(name), name)
    return { name -> byKey[nameKey(name)] ?: name }
}
