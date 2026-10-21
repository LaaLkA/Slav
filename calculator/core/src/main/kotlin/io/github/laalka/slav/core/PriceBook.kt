package io.github.laalka.slav.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class MaterialGroup(val name: String)

/** A metal grade (spec 4.2): belongs to a group by name, density in kg/m³. */
@Serializable
data class Grade(val name: String, val group: String, val densityKgM3: Decimal)

/**
 * All settings of spec 4: directories, sortament and prices. The same object is the price
 * snapshot stored with a calculation (spec 8.4) and the payload of a settings export (spec 9).
 *
 * Grades, groups and weld types are referenced by name, the key the settings merge matches on
 * (spec 9); renaming must therefore go through [renameGroup] / [renameGrade].
 */
@Serializable
data class PriceBook(
    @EncodeDefault val formatVersion: Int = FORMAT_VERSION,
    val general: GeneralSettings,
    val groups: List<MaterialGroup>,
    val grades: List<Grade>,
    val sortament: Sortament,
    val materialPricesPerKg: List<GradePrice>,
    /** «Лазер, лист», ₽/cm. */
    val laserSheetCutPerCm: List<GroupPrice>,
    /** «Пробитие, лист», ₽ per pierce. */
    val laserSheetPiercePerPiece: List<GroupPrice>,
    /** «Лазер, труборез», ₽/cm. */
    val tubeLaserCutPerCm: List<GroupPrice>,
    /** «Пробитие, труборез», ₽ per pierce. */
    val tubeLaserPiercePerPiece: List<GroupPrice>,
    val bendingPerBend: List<BendPrice>,
    val weldingPerCm: List<WeldPrice>,
    /** Powder painting, ₽/m²; null when not set. */
    val paintingPerM2: Decimal?,
) {
    fun renameGroup(old: String, new: String): PriceBook {
        require(groups.any { it.name == old }) { "Unknown material group '$old'" }
        if (old == new) return this
        require(groups.none { it.name == new }) { "Material group '$new' already exists" }
        fun rename(name: String) = if (name == old) new else name
        return copy(
            groups = groups.map { it.copy(name = rename(it.name)) },
            grades = grades.map { it.copy(group = rename(it.group)) },
            laserSheetCutPerCm = laserSheetCutPerCm.map { it.copy(group = rename(it.group)) },
            laserSheetPiercePerPiece = laserSheetPiercePerPiece.map { it.copy(group = rename(it.group)) },
            tubeLaserCutPerCm = tubeLaserCutPerCm.map { it.copy(group = rename(it.group)) },
            tubeLaserPiercePerPiece = tubeLaserPiercePerPiece.map { it.copy(group = rename(it.group)) },
            bendingPerBend = bendingPerBend.map { it.copy(group = rename(it.group)) },
            weldingPerCm = weldingPerCm.map { it.copy(group = rename(it.group)) },
        )
    }

    fun renameGrade(old: String, new: String): PriceBook {
        require(grades.any { it.name == old }) { "Unknown grade '$old'" }
        if (old == new) return this
        require(grades.none { it.name == new }) { "Grade '$new' already exists" }
        return copy(
            grades = grades.map { if (it.name == old) it.copy(name = new) else it },
            materialPricesPerKg = materialPricesPerKg.map { if (it.grade == old) it.copy(grade = new) else it },
        )
    }

    companion object {
        const val FORMAT_VERSION = 1

        /**
         * First-run settings: steel St3 and the example prices of the old spreadsheet
         * (sheet «данные»). Tables the spreadsheet has no data for are left empty.
         */
        fun default(): PriceBook {
            val steel = "Сталь"
            val st3 = "Ст3"
            val thicknesses = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 14, 16, 18, 20).map(Int::toBigDecimal)
            fun byThickness(vararg prices: Int): List<Pair<BigDecimal, BigDecimal>> {
                check(prices.size == thicknesses.size)
                return thicknesses.zip(prices.map(Int::toBigDecimal))
            }
            val general = GeneralSettings.default()
            val upTo100 = general.bendRanges().first().toMm
            val semiAutomatic = general.weldTypes.first()
            return PriceBook(
                general = general,
                groups = listOf(MaterialGroup(steel)),
                grades = listOf(Grade(st3, steel, BigDecimal(7850))),
                sortament = Sortament(
                    sheetThicknessesMm = thicknesses,
                    roundTubes = emptyList(),
                    profileTubes = emptyList(),
                    angles = emptyList(),
                    channels = emptyList(),
                    iBeams = emptyList(),
                ),
                materialPricesPerKg = byThickness(100, 200, 300, 400, 500, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000)
                    .map { (t, price) -> GradePrice(st3, t, price) },
                laserSheetCutPerCm = byThickness(10, 20, 30, 40, 50, 60, 80, 100, 120, 140, 160, 180, 200)
                    .map { (t, price) -> GroupPrice(steel, t, price) },
                laserSheetPiercePerPiece = byThickness(0, 0, 0, 10, 10, 15, 15, 20, 20, 25, 25, 30, 35)
                    .map { (t, price) -> GroupPrice(steel, t, price) },
                tubeLaserCutPerCm = emptyList(),
                tubeLaserPiercePerPiece = emptyList(),
                bendingPerBend = byThickness(20, 25, 30, 35, 40, 60, 70, 100, 120, 140, 160, 180, 200)
                    .map { (t, price) -> BendPrice(steel, t, upTo100, price) },
                weldingPerCm = byThickness(10, 10, 10, 10, 15, 15, 15, 15, 20, 20, 25, 25, 30)
                    .map { (t, price) -> WeldPrice(semiAutomatic, steel, t, price) },
                paintingPerM2 = null,
            )
        }
    }
}
