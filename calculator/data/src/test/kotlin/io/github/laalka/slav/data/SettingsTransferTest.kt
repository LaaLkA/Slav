package io.github.laalka.slav.data

import io.github.laalka.slav.core.AngleSize
import io.github.laalka.slav.core.BendPrice
import io.github.laalka.slav.core.GeneralSettings
import io.github.laalka.slav.core.Grade
import io.github.laalka.slav.core.GradePrice
import io.github.laalka.slav.core.GroupPrice
import io.github.laalka.slav.core.MaterialGroup
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.Sortament
import io.github.laalka.slav.core.WeldPrice
import io.github.laalka.slav.core.priceFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTransferTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var transfer: SettingsTransfer
    private val paths by lazy { AppPaths(dir.resolve("data")) }

    @BeforeTest
    fun open() {
        db = DatabaseFactory(paths, clock).open()
        settings = SettingsRepository(db, clock)
        transfer = SettingsTransfer(settings, paths, appVersion = "1.2.3", clock = clock)
    }

    @AfterTest
    fun close() = db.close()

    /** The default settings plus a grade and a painting price the other user does not have. */
    private val local = PriceBook.default().let { book ->
        book.copy(
            grades = book.grades + Grade("09Г2С", "Сталь", dec("7850")),
            paintingPerM2 = dec("350"),
        )
    }

    /**
     * Settings another user created on their own: the same steel and Ст3 typed differently, a
     * thickness written as 2.0, other prices, a new 25 mm thickness and a new weld type.
     */
    private val independent: PriceBook = run {
        val steel = " сталь "
        PriceBook(
            general = GeneralSettings.default().copy(weldTypes = listOf("Аргон ", "лазер", "газ")),
            groups = listOf(MaterialGroup(steel)),
            grades = listOf(Grade("ст3", steel, dec("7860.0"))),
            sortament = Sortament(
                sheetThicknessesMm = listOf(dec("2.0"), dec("25")),
                roundTubes = emptyList(),
                profileTubes = emptyList(),
                angles = listOf(AngleSize("63×63×6", dec("63"), dec("63"), dec("6"), dec("5.72"))),
                channels = emptyList(),
                iBeams = emptyList(),
            ),
            materialPricesPerKg = listOf(GradePrice("СТ3", dec("2.0"), dec("250"))),
            laserSheetCutPerCm = listOf(GroupPrice(steel, dec("2.0"), dec("25")), GroupPrice(steel, dec("25"), dec("300"))),
            laserSheetPiercePerPiece = emptyList(),
            tubeLaserCutPerCm = emptyList(),
            tubeLaserPiercePerPiece = emptyList(),
            bendingPerBend = listOf(BendPrice(steel, dec("2.00"), dec("100.0"), dec("40"))),
            weldingPerCm = listOf(WeldPrice("АРГОН", steel, dec("3"), dec("12"))),
            paintingPerM2 = null,
        )
    }

    private fun exported(book: PriceBook): Path {
        settings.save(book)
        return dir.resolve("export.json").also(transfer::export)
    }

    @Test
    fun `export then import gives back the same settings`() {
        val file = exported(local)

        val header = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(PriceBook.FORMAT_VERSION, header.getValue("formatVersion").jsonPrimitive.int)
        assertEquals("1.2.3", header.getValue("appVersion").jsonPrimitive.content)
        assertEquals(clock.now.toString(), header.getValue("exportedAt").jsonPrimitive.content)

        val imported = transfer.read(file)
        assertEquals(local, imported.settings)
        assertEquals(PriceBook.FORMAT_VERSION, imported.formatVersion)
        assertEquals("1.2.3", imported.appVersion)
        assertEquals(clock.now, imported.exportedAt)
        assertFalse(transfer.previewReplace(imported.settings).hasChanges)
    }

    @Test
    fun `a file from a newer program is rejected with a request to update`() {
        val file = dir.resolve("newer.json")
        // Everything but the version may have changed in a newer format.
        file.writeText("""{"formatVersion": ${PriceBook.FORMAT_VERSION + 1}, "catalogue": {}}""")

        val error = assertFailsWith<SettingsImportException.NewerFormat> { transfer.read(file) }
        assertTrue("Обновите программу" in error.message.orEmpty(), error.message)
        assertEquals(PriceBook.FORMAT_VERSION + 1, error.fileVersion)
    }

    @Test
    fun `a file that is not a settings export is rejected without touching the settings`() {
        val file = dir.resolve("other.json")
        for (text in listOf("просчет", "[1, 2]", """{"name": "x"}""", """{"formatVersion": 1, "appVersion": "1.0", "exportedAt": "вчера"}""")) {
            file.writeText(text)
            assertFailsWith<SettingsImportException.Invalid>(text) { transfer.read(file) }
        }
        val truncated = exported(local).readText().dropLast(40)
        file.writeText(truncated)
        assertFailsWith<SettingsImportException.Invalid> { transfer.read(file) }
        assertFailsWith<SettingsImportException.Unreadable> { transfer.read(dir.resolve("missing.json")) }
        assertEquals(local, settings.load())
    }

    @Test
    fun `a file saved with a byte-order mark is read`() {
        val file = exported(local)
        file.writeText("\uFEFF" + file.readText())

        assertEquals(local, transfer.read(file).settings)
    }

    @Test
    fun `replace writes a backup of the current settings first, which imports back`() {
        val incoming = transfer.read(exported(independent)).settings
        settings.save(local)

        val summary = transfer.previewReplace(incoming)
        assertEquals(local, settings.load(), "a preview applies nothing")
        assertEquals(SectionChanges(added = 0, changed = 1, removed = 1), summary[SettingsSection.GRADES])
        assertEquals(SectionChanges(added = 1, changed = 0, removed = 12), summary[SettingsSection.SHEET_THICKNESSES])
        assertEquals(SectionChanges(added = 0, changed = 0, removed = 1), summary[SettingsSection.PAINTING])
        // The four sample angles of the default settings are not in the file; its 63×63×6 is new.
        assertEquals(SectionChanges(added = 1, changed = 0, removed = 4), summary[SettingsSection.ANGLES])

        val backup = transfer.replace(incoming)

        assertTrue(backup.exists())
        assertEquals(paths.backupsDir, backup.parent)
        assertEquals(incoming, settings.load())
        assertEquals(local, transfer.read(backup).settings)
    }

    @Test
    fun `merge joins two independently created Ст3 into one and keeps local-only elements`() {
        settings.save(local)

        val summary = transfer.previewMerge(independent)
        assertEquals(local, settings.load(), "a preview applies nothing")
        val merged = transfer.merge(independent)
        assertEquals(merged, settings.load())

        // One steel group and one Ст3, spelled as locally, with the density from the file.
        assertEquals(listOf(MaterialGroup("Сталь")), merged.groups)
        assertEquals(listOf(Grade("Ст3", "Сталь", dec("7860.0")), Grade("09Г2С", "Сталь", dec("7850"))), merged.grades)
        assertEquals(listOf("полуавтомат", "аргон", "лазер", "газ"), merged.general.weldTypes)
        assertEquals(dec("350"), merged.paintingPerM2)

        assertEquals(SectionChanges(added = 0, changed = 0, removed = 0), summary[SettingsSection.GROUPS])
        assertEquals(SectionChanges(added = 0, changed = 1, removed = 0), summary[SettingsSection.GRADES])
        assertEquals(SectionChanges(added = 1, changed = 0, removed = 0), summary[SettingsSection.WELD_TYPES])
        assertEquals(SectionChanges(added = 0, changed = 0, removed = 0), summary[SettingsSection.PAINTING])
        assertEquals(0, summary.removed)
    }

    @Test
    fun `merge treats 2 and 2,0 as one thickness in the sortament and in every price table`() {
        settings.save(local)

        val summary = transfer.previewMerge(independent)
        val merged = transfer.merge(independent)

        fun cellsAt(thickness: String, cells: List<BigDecimal>) = cells.count { it.compareTo(dec(thickness)) == 0 }
        assertEquals(1, cellsAt("2", merged.sortament.sheetThicknessesMm))
        assertEquals(local.sortament.sheetThicknessesMm.size + 1, merged.sortament.sheetThicknessesMm.size)
        assertEquals(1, cellsAt("2", merged.materialPricesPerKg.map { it.thicknessMm }))
        assertEquals(1, cellsAt("2", merged.laserSheetCutPerCm.map { it.thicknessMm }))
        // A bending cell is a thickness × range pair: the file's 2.00 / «до 100» cell replaced the local one, no twin is left.
        assertEquals(1, merged.bendingPerBend.count { it.thicknessMm.compareTo(dec("2")) == 0 && it.upToMm?.compareTo(dec("100")) == 0 })
        assertEquals(local.bendingPerBend.size, merged.bendingPerBend.size)
        assertEquals(1, cellsAt("2", merged.weldingPerCm.filter { it.weldType == "полуавтомат" }.map { it.thicknessMm }))

        // The values from the file, found by the exact names the calculator looks prices up by.
        assertEquals(dec("250"), merged.materialPricesPerKg.priceFor("Ст3", dec("2")))
        assertEquals(dec("25"), merged.laserSheetCutPerCm.priceFor("Сталь", dec("2")))
        assertEquals(dec("300"), merged.laserSheetCutPerCm.priceFor("Сталь", dec("25")))
        val upTo100 = merged.general.bendRanges().first()
        assertEquals(dec("40"), merged.bendingPerBend.priceFor("Сталь", dec("2"), upTo100))
        assertEquals(dec("12"), merged.weldingPerCm.priceFor("аргон", "Сталь", dec("3")))

        assertEquals(SectionChanges(added = 1, changed = 0, removed = 0), summary[SettingsSection.SHEET_THICKNESSES])
        assertEquals(SectionChanges(added = 0, changed = 1, removed = 0), summary[SettingsSection.MATERIAL_PRICES])
        assertEquals(SectionChanges(added = 1, changed = 1, removed = 0), summary[SettingsSection.LASER_SHEET_CUT])
        assertEquals(SectionChanges(added = 0, changed = 1, removed = 0), summary[SettingsSection.BENDING])
        // The file's «АРГОН» / 3 mm cell is the local argon cell, with another price.
        assertEquals(SectionChanges(added = 0, changed = 1, removed = 0), summary[SettingsSection.WELDING])
    }

    @Test
    fun `merging the same settings again changes nothing`() {
        settings.save(local)
        val once = transfer.merge(independent)

        assertFalse(transfer.previewMerge(independent).hasChanges)
        assertEquals(once, transfer.merge(independent))
    }

    @Test
    fun `the preview of a merge counts what the merge does`() {
        settings.save(local)

        val summary = transfer.previewMerge(independent)
        val merged = transfer.merge(independent)

        assertEquals(merged.grades.size, local.grades.size + summary[SettingsSection.GRADES].added)
        assertEquals(merged.laserSheetCutPerCm.size, local.laserSheetCutPerCm.size + summary[SettingsSection.LASER_SHEET_CUT].added)
        assertEquals(merged.sortament.angles.size, local.sortament.angles.size + summary[SettingsSection.ANGLES].added)
        assertEquals(SettingsSection.entries.toSet(), summary.sections.keys)
    }
}
