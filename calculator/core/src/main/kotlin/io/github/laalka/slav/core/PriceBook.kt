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
    /**
     * «Лазер, ₽/мин»: the laser by the minute, the only operation price of the simplified calculation;
     * null when not set. Settings saved before it existed have no such field and take null.
     */
    @EncodeDefault val laserCutPerMinute: Decimal? = null,
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
        /**
         * 3: the laser rate per minute of the simplified calculation. 2: part welding and painting are
         * gone, the sheet has a shape and the general settings have the minimum painted area of a part.
         * An older program reads a newer file as «обновите программу»; files of formats 1 and 2 still import.
         */
        const val FORMAT_VERSION = 3

        /**
         * First-run settings: steel St3 at 8000 kg/m³, the example prices of the old spreadsheet
         * (sheet «данные») and test values for everything the spreadsheet had no data for, so every
         * operation can be tried on every size of the sample sortament. All of these are test
         * values, to be replaced with the real prices in the settings.
         */
        fun default(): PriceBook {
            val steel = "Сталь"
            val st3 = "Ст3"
            val sheetThicknesses = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 14, 16, 18, 20).map(Int::toBigDecimal)
            val sortament = Sortament(
                sheetThicknessesMm = sheetThicknesses,
                roundTubes = listOf(20 to 2, 32 to 2, 57 to 3, 108 to 4, 219 to 6)
                    .map { (d, t) -> RoundTubeSize(BigDecimal(d), BigDecimal(t)) },
                profileTubes = listOf(Triple(20, 20, 2), Triple(40, 20, 2), Triple(60, 40, 3), Triple(100, 100, 4), Triple(200, 200, 6))
                    .map { (a, b, t) -> ProfileTubeSize(BigDecimal(a), BigDecimal(b), BigDecimal(t)) },
                angles = listOf(
                    AngleSize("25×25×3", BigDecimal(25), BigDecimal(25), BigDecimal(3), BigDecimal("1.12")),
                    AngleSize("50×50×5", BigDecimal(50), BigDecimal(50), BigDecimal(5), BigDecimal("3.77")),
                    AngleSize("75×75×6", BigDecimal(75), BigDecimal(75), BigDecimal(6), BigDecimal("6.89")),
                    AngleSize("100×100×8", BigDecimal(100), BigDecimal(100), BigDecimal(8), BigDecimal("12.25")),
                ),
                channels = listOf(
                    BeamSize("10П", BigDecimal(100), BigDecimal(46), BigDecimal("4.5"), BigDecimal("8.59")),
                    BeamSize("16П", BigDecimal(160), BigDecimal(64), BigDecimal("5.0"), BigDecimal("14.2")),
                    BeamSize("20П", BigDecimal(200), BigDecimal(76), BigDecimal("5.2"), BigDecimal("18.4")),
                ),
                iBeams = listOf(
                    BeamSize("10Б1", BigDecimal(100), BigDecimal(55), BigDecimal("4.1"), BigDecimal("8.1")),
                    BeamSize("20Б1", BigDecimal(200), BigDecimal(100), BigDecimal("5.6"), BigDecimal("22.4")),
                    BeamSize("30Б1", BigDecimal(296), BigDecimal(140), BigDecimal("5.8"), BigDecimal("32.9")),
                ),
            )
            // The tube-laser tables, material prices and welding apply to every thickness of the
            // sortament, a section's included (spec 4.4, 4.5); laser on a sheet and bending only to sheets.
            val sectionThicknesses = sortament.roundTubes.map { it.wallMm } + sortament.profileTubes.map { it.wallMm } +
                sortament.angles.map { it.thicknessMm } + (sortament.channels + sortament.iBeams).map { it.thicknessMm }
            val allThicknesses = (sheetThicknesses + sectionThicknesses).distinctBy { it.stripTrailingZeros() }.sorted()

            // [prices] are per sheet thickness; a section thickness between two of them takes the lower one's price.
            fun cells(thicknesses: List<BigDecimal>, vararg prices: Int): List<Pair<BigDecimal, BigDecimal>> {
                check(prices.size == sheetThicknesses.size)
                return thicknesses.map { t -> t to BigDecimal(prices[sheetThicknesses.indexOfLast { it <= t }]) }
            }

            val general = GeneralSettings.default()
            val ranges = general.bendRanges()
            val semiAutomatic = general.weldTypes[0]
            val argon = general.weldTypes[1]
            val laser = general.weldTypes[2]
            val laserCut = intArrayOf(10, 20, 30, 40, 50, 60, 80, 100, 120, 140, 160, 180, 200)
            val bendUpTo100 = cells(sheetThicknesses, 20, 25, 30, 35, 40, 60, 70, 100, 120, 140, 160, 180, 200)
            val weldSemiAutomatic = cells(allThicknesses, 10, 10, 10, 10, 15, 15, 15, 15, 20, 20, 25, 25, 30)
            return PriceBook(
                general = general,
                groups = listOf(MaterialGroup(steel)),
                grades = listOf(Grade(st3, steel, BigDecimal(8000))),
                sortament = sortament,
                materialPricesPerKg = cells(allThicknesses, 100, 200, 300, 400, 500, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000)
                    .map { (t, price) -> GradePrice(st3, t, price) },
                laserSheetCutPerCm = cells(sheetThicknesses, *laserCut).map { (t, price) -> GroupPrice(steel, t, price) },
                laserSheetPiercePerPiece = cells(sheetThicknesses, 0, 0, 0, 10, 10, 15, 15, 20, 20, 25, 25, 30, 35)
                    .map { (t, price) -> GroupPrice(steel, t, price) },
                // The tube laser takes one and a half times the sheet laser's price per cm.
                tubeLaserCutPerCm = cells(allThicknesses, *laserCut.map { it * 3 / 2 }.toIntArray())
                    .map { (t, price) -> GroupPrice(steel, t, price) },
                tubeLaserPiercePerPiece = cells(allThicknesses, 5, 5, 5, 10, 10, 15, 15, 20, 20, 25, 25, 30, 35)
                    .map { (t, price) -> GroupPrice(steel, t, price) },
                // A longer bend costs more: range k is «до 100» × (1 + 0.5 × k).
                bendingPerBend = ranges.withIndex().flatMap { (k, range) ->
                    bendUpTo100.map { (t, price) -> BendPrice(steel, t, range.toMm, price.timesRangeFactor(k)) }
                },
                weldingPerCm = weldSemiAutomatic.map { (t, price) -> WeldPrice(semiAutomatic, steel, t, price) } +
                    weldSemiAutomatic.map { (t, price) -> WeldPrice(argon, steel, t, price * BigDecimal(2)) } +
                    weldSemiAutomatic.map { (t, price) -> WeldPrice(laser, steel, t, price * BigDecimal(3)) },
                paintingPerM2 = BigDecimal(450),
                laserCutPerMinute = BigDecimal(50),
            )
        }

        /** price × (1 + 0.5 × k), written without trailing zeros: 20 stays "20", 25 × 1.5 is "37.5". */
        private fun BigDecimal.timesRangeFactor(k: Int): BigDecimal =
            (this * (BigDecimal.ONE + BigDecimal("0.5") * BigDecimal(k))).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    }
}
