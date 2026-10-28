package io.github.laalka.slav.app.settings

import io.github.laalka.slav.app.AppPreferences
import io.github.laalka.slav.app.ADMIN_PASSWORD
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.app.ThemeMode
import io.github.laalka.slav.core.GroupPrice
import io.github.laalka.slav.core.MaterialGroup
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.priceFor
import io.github.laalka.slav.data.AppPaths
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private lateinit var model: SettingsModel

    private val stored: PriceBook get() = deps.settings.load()

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
        model = SettingsModel(deps.settings, deps.settingsTransfer, deps.preferences, deps.adminPassword)
    }

    @AfterTest
    fun close() = deps.close()

    @Test
    fun `a price typed into a cell is saved to the repository`() {
        val cell = PriceCell.Group(GroupTable.LASER_SHEET_CUT, STEEL, dec("2"))

        model.prices.onCellChange(cell, "37")

        assertSame(dec("37"), stored.laserSheetCutPerCm.priceFor(STEEL, dec("2")))
        assertEquals("37", model.prices.state.value.cellAt(cell).text)
    }

    @Test
    fun `the laser rate per minute is a single price, saved when typed, removed when emptied, kept unsaved when not a number`() {
        val cell = PriceCell.LaserCutPerMinute
        assertEquals("50", model.prices.state.value.laserCutPerMinute.text, "the first-run test value")
        assertEquals("Лазер, ₽/мин", model.prices.state.value.laserCutPerMinuteTitle)

        model.prices.onCellChange(cell, "62,5")
        assertSame(dec("62.5"), stored.laserCutPerMinute)
        assertEquals("62,5", model.prices.state.value.laserCutPerMinute.text)

        model.prices.onCellChange(cell, "abc")
        assertSame(dec("62.5"), stored.laserCutPerMinute)
        assertEquals("abc", model.prices.state.value.laserCutPerMinute.text)
        assertEquals(NOT_A_NUMBER, model.prices.state.value.laserCutPerMinute.error)

        // Zero is a price (a free laser), an empty field is not one.
        model.prices.onCellChange(cell, "0")
        assertSame(dec("0"), stored.laserCutPerMinute)
        assertNull(model.prices.state.value.laserCutPerMinute.error)
        model.prices.onCellChange(cell, "")
        assertNull(stored.laserCutPerMinute)
        assertEquals("", model.prices.state.value.laserCutPerMinute.text)
    }

    @Test
    fun `the title of the laser rate follows the currency of the settings`() {
        model.general.onCurrencyChange("руб.")

        assertEquals("Лазер, руб./мин", model.prices.state.value.laserCutPerMinuteTitle)
    }

    @Test
    fun `a comma decimal is the same number as a dot decimal`() {
        assertEquals(dec("2.5"), parseDecimal("2,5"))
        assertEquals(dec("2.5"), parseDecimal("2.5"))
        assertEquals(dec("1200.75"), parseDecimal(" 1 200,75 "))
        listOf("", "abc", "2,5,1", "-1", "1e3").forEach { assertNull(parseDecimal(it), "'$it' is not a number") }

        model.prices.onCellChange(PriceCell.Material(ST3, dec("2")), "2,5")

        assertSame(dec("2.5"), stored.materialPricesPerKg.priceFor(ST3, dec("2")))
    }

    @Test
    fun `an emptied cell has no price while zero stays a price`() {
        model.prices.onCellChange(PriceCell.Group(GroupTable.LASER_SHEET_CUT, STEEL, dec("2")), "")
        model.prices.onCellChange(PriceCell.Group(GroupTable.LASER_SHEET_PIERCE, STEEL, dec("3")), "0")

        assertNull(stored.laserSheetCutPerCm.priceFor(STEEL, dec("2")))
        assertSame(BigDecimal.ZERO, stored.laserSheetPiercePerPiece.priceFor(STEEL, dec("3")))
    }

    @Test
    fun `text that is not a number is flagged in its cell and not saved`() {
        val before = stored
        val cell = PriceCell.Bending(STEEL, dec("2"), stored.general.bendRanges().first())

        model.prices.onCellChange(cell, "25р")

        assertEquals(before, stored)
        assertEquals(CellState(cell, "25р", NOT_A_NUMBER), model.prices.state.value.cellAt(cell))
    }

    @Test
    fun `renaming a group renames it in its grades and every price table`() {
        val renamed = "Сталь конструкционная"

        model.materials.startRename(RenameTarget.GROUP, STEEL)
        model.materials.onRenameChange(renamed)
        model.materials.confirmRename()

        val book = stored
        assertEquals(listOf(MaterialGroup(renamed)), book.groups)
        assertEquals(listOf(renamed), book.grades.map { it.group })
        val referenced = book.laserSheetCutPerCm.map { it.group } + book.laserSheetPiercePerPiece.map { it.group } +
            book.bendingPerBend.map { it.group } + book.weldingPerCm.map { it.group }
        assertEquals(setOf(renamed), referenced.toSet())
        assertSame(dec("20"), book.laserSheetCutPerCm.priceFor(renamed, dec("2")))
        assertNull(model.materials.state.value.rename)
        assertEquals(renamed, model.prices.state.value.groupTables.first().rows.single().label)
    }

    @Test
    fun `renaming a grade moves its material prices`() {
        model.materials.startRename(RenameTarget.GRADE, ST3)
        model.materials.onRenameChange("Ст3сп")
        model.materials.confirmRename()

        assertSame(dec("200"), stored.materialPricesPerKg.priceFor("Ст3сп", dec("2")))
        assertNull(stored.materialPricesPerKg.priceFor(ST3, dec("2")))
    }

    @Test
    fun `a name that exists in another letter case is refused`() {
        model.materials.onGroupNameChange("Алюминий")
        model.materials.addGroup()
        val before = stored

        model.materials.startRename(RenameTarget.GROUP, STEEL)
        model.materials.onRenameChange(" алюминий ")
        model.materials.confirmRename()

        assertEquals(before, stored)
        assertEquals("Группа «алюминий» уже есть", model.materials.state.value.rename?.field?.error)
    }

    @Test
    fun `deleting a group that grades belong to is refused with a message`() {
        model.materials.deleteGroup(STEEL)

        assertEquals(listOf(MaterialGroup(STEEL)), stored.groups)
        val notice = assertNotNull(model.materials.state.value.notice)
        assertTrue("«$ST3»" in notice, notice)
    }

    @Test
    fun `deleting an unused group removes its prices too`() {
        model.materials.onGroupNameChange("Алюминий")
        model.materials.addGroup()
        model.prices.onCellChange(PriceCell.Group(GroupTable.LASER_SHEET_CUT, "Алюминий", dec("2")), "15")

        model.materials.deleteGroup("Алюминий")

        assertEquals(listOf(MaterialGroup(STEEL)), stored.groups)
        assertTrue(stored.laserSheetCutPerCm.none { it.group == "Алюминий" })
    }

    @Test
    fun `a density typed with a comma is saved and an empty one is flagged`() {
        model.materials.onDensityChange(ST3, "7850,5")
        assertSame(dec("7850.5"), stored.grades.single().densityKgM3)

        model.materials.onDensityChange(ST3, "")
        assertSame(dec("7850.5"), stored.grades.single().densityKgM3)
        assertEquals(FieldState("", REQUIRED), model.materials.state.value.grades.single().density)
    }

    @Test
    fun `a sheet thickness added to the sortament becomes a price column`() {
        model.sortament.onFieldChange(SortamentKind.SHEET, 0, "2,5")
        model.sortament.add(SortamentKind.SHEET)

        assertTrue(stored.sortament.sheetThicknessesMm.any { it.compareTo(dec("2.5")) == 0 })
        assertTrue("2,5" in model.prices.state.value.groupTables.first().columns)

        model.sortament.onFieldChange(SortamentKind.SHEET, 0, "2.50")
        model.sortament.add(SortamentKind.SHEET)
        assertEquals("Такой размер уже есть в списке", model.sortament.state.value.sections.first().form.error)
    }

    @Test
    fun `a weld type added in general settings gets its own welding table`() {
        model.general.onWeldTypeChange("контактная")
        model.general.addWeldType()

        assertEquals(listOf("полуавтомат", "аргон", "лазер", "контактная"), stored.general.weldTypes)
        assertTrue(model.prices.state.value.welding.any { it.title == "Сварка — контактная" })
    }

    @Test
    fun `the minimum painted area of a part is a general setting, zero switches it off and a wrong text is flagged and not saved`() {
        assertEquals("0,1", model.general.state.value.minPaintAreaPerPart.text)

        model.general.onMinPaintAreaChange("0,25")
        assertSame(dec("0.25"), stored.general.minPaintAreaPerPartM2)
        assertEquals(FieldState("0,25"), model.general.state.value.minPaintAreaPerPart)

        model.general.onMinPaintAreaChange("0")
        assertSame(dec("0"), stored.general.minPaintAreaPerPartM2)

        model.general.onMinPaintAreaChange("abc")
        assertEquals(FieldState("abc", NOT_A_NUMBER), model.general.state.value.minPaintAreaPerPart)
        model.general.onMinPaintAreaChange("")
        assertEquals(FieldState("", REQUIRED), model.general.state.value.minPaintAreaPerPart)
        assertSame(dec("0"), stored.general.minPaintAreaPerPartM2)

        model.general.onMinPaintAreaChange("0.3")
        assertSame(dec("0.3"), stored.general.minPaintAreaPerPartM2)
        assertEquals(FieldState("0,3"), model.general.state.value.minPaintAreaPerPart)
    }

    @Test
    fun `the chosen theme survives a restart`() {
        model.general.setTheme(ThemeMode.DARK)

        val reopened = AppPreferences(temp.root.toPath().resolve("data").resolve("preferences.properties"))
        assertEquals(ThemeMode.DARK, reopened.themeMode.value)
    }

    @Test
    fun `a full replace is previewed and applied only after confirmation`() {
        val file = exportedSettings()
        val before = stored

        model.transfer.prepareImport(file, ImportMode.REPLACE)

        val pending = assertNotNull(model.transfer.state.value.pending)
        assertEquals(SectionChangeRow("Группы материалов", added = 1, changed = 0, removed = 0), pending.changes.first())
        assertEquals(SectionChangeRow("Лазер, лист", added = 0, changed = 1, removed = 0), pending.changes.single { it.title == "Лазер, лист" })
        assertEquals(before, stored)

        model.transfer.confirmImport()

        assertSame(dec("99"), stored.laserSheetCutPerCm.priceFor(STEEL, dec("2")))
        assertEquals(listOf(STEEL, COPPER), stored.groups.map { it.name })
        val backup = assertNotNull(model.transfer.state.value.backupPath)
        assertTrue(Path(backup).exists())
        assertNull(model.transfer.state.value.pending)
        val laser = model.prices.state.value.groupTables.first()
        assertEquals("99", laser.rows.first().cells[laser.columns.indexOf("2")].text)
    }

    @Test
    fun `a cancelled import leaves the settings unchanged`() {
        val file = exportedSettings()
        val before = stored

        model.transfer.prepareImport(file, ImportMode.MERGE)
        model.transfer.cancelImport()

        assertEquals(before, stored)
        assertNull(model.transfer.state.value.pending)
    }

    @Test
    fun `a merge keeps local elements the file lacks`() {
        model.materials.onGroupNameChange("Латунь")
        model.materials.addGroup()
        val file = exportedSettings()

        model.transfer.prepareImport(file, ImportMode.MERGE)
        model.transfer.confirmImport()

        assertEquals(listOf(STEEL, "Латунь", COPPER), stored.groups.map { it.name })
        assertSame(dec("99"), stored.laserSheetCutPerCm.priceFor(STEEL, dec("2")))
    }

    @Test
    fun `a file from a newer program asks to update it`() {
        val file = temp.root.toPath().resolve("newer.json")
        file.writeText("""{"formatVersion": ${PriceBook.FORMAT_VERSION + 1}, "appVersion": "9.0.0", "exportedAt": "2030-01-01T00:00:00Z"}""")

        model.transfer.prepareImport(file, ImportMode.REPLACE)

        val message = assertNotNull(model.transfer.state.value.message)
        assertTrue(message.isError)
        assertTrue("Обновите программу" in message.text, message.text)
        assertNull(model.transfer.state.value.pending)
    }

    private fun GeneralModel.fillPassword(current: String, new: String, repeat: String) {
        onCurrentPasswordChange(current)
        onNewPasswordChange(new)
        onRepeatPasswordChange(repeat)
    }

    private val passwordForm get() = model.general.state.value.password

    @Test
    fun `the administrator password is changed with the current one, a new one of eight characters and its repeat`() {
        model.general.fillPassword(ADMIN_PASSWORD, "brand-new-pass", "brand-new-pass")

        model.general.changePassword()

        assertEquals(PasswordFormState(success = PASSWORD_CHANGED), passwordForm)
        assertTrue(deps.adminPassword.verify("brand-new-pass".toCharArray()))
        assertTrue(!deps.adminPassword.verify(ADMIN_PASSWORD.toCharArray()), "the old password no longer works")
        // The fields are re-created empty: the old texts are not asked for again.
        assertEquals(1, model.general.state.value.epoch)
        model.general.changePassword()
        assertEquals(PASSWORD_TOO_SHORT, passwordForm.newError, "the cleared form is not submitted again")
    }

    @Test
    fun `a wrong current password is flagged and the password stays`() {
        model.general.fillPassword("not-the-password", "brand-new-pass", "brand-new-pass")

        model.general.changePassword()

        assertEquals(PasswordFormState(currentError = WRONG_CURRENT_PASSWORD), passwordForm)
        assertTrue(deps.adminPassword.verify(ADMIN_PASSWORD.toCharArray()))
        assertTrue(!deps.adminPassword.verify("brand-new-pass".toCharArray()))
        // The next try with the right one works, and typing clears the error.
        model.general.onCurrentPasswordChange(ADMIN_PASSWORD)
        assertNull(passwordForm.currentError)
        model.general.changePassword()
        assertEquals(PASSWORD_CHANGED, passwordForm.success)
    }

    @Test
    fun `a mismatching repeat and a new password shorter than eight are flagged and change nothing`() {
        model.general.fillPassword(ADMIN_PASSWORD, "brand-new-pass", "brand-new-pasS")
        model.general.changePassword()
        assertEquals(PasswordFormState(repeatError = PASSWORDS_DIFFER), passwordForm)

        model.general.fillPassword(ADMIN_PASSWORD, "1234567", "1234567")
        model.general.changePassword()
        assertEquals(PasswordFormState(newError = PASSWORD_TOO_SHORT), passwordForm)

        model.general.fillPassword(ADMIN_PASSWORD, "1234567", "12345678")
        model.general.changePassword()
        assertEquals(PasswordFormState(newError = PASSWORD_TOO_SHORT, repeatError = PASSWORDS_DIFFER), passwordForm)

        assertTrue(deps.adminPassword.verify(ADMIN_PASSWORD.toCharArray()))
        assertEquals("Не короче 8 символов", PASSWORD_TOO_SHORT)
        // Exactly eight characters are enough.
        model.general.fillPassword(ADMIN_PASSWORD, "12345678", "12345678")
        model.general.changePassword()
        assertEquals(PASSWORD_CHANGED, passwordForm.success)
    }

    @Test
    fun `the password is not part of the settings that are exported`() {
        model.general.fillPassword(ADMIN_PASSWORD, "brand-new-pass", "brand-new-pass")
        model.general.changePassword()
        val file = temp.root.toPath().resolve("export.json")

        deps.settingsTransfer.export(file)

        val text = file.toFile().readText()
        assertTrue("brand-new-pass" !in text && ADMIN_PASSWORD !in text)
        assertTrue("password" !in text.lowercase() && "пароль" !in text.lowercase())
    }

    /** Settings exported by another installation: laser 2 mm costs 99 and there is a copper group. */
    private fun exportedSettings(): Path {
        val file = temp.root.toPath().resolve("exported.json")
        Deps(AppPaths(temp.newFolder("other").toPath()), appVersion = "test").use { other ->
            val book = other.settings.load()
            other.settings.save(
                book.copy(
                    groups = book.groups + MaterialGroup(COPPER),
                    laserSheetCutPerCm = book.laserSheetCutPerCm.map { if (it.thicknessMm.compareTo(dec("2")) == 0) GroupPrice(STEEL, it.thicknessMm, dec("99")) else it },
                ),
            )
            other.settingsTransfer.export(file)
        }
        return file
    }

    private companion object {
        const val STEEL = "Сталь"
        const val ST3 = "Ст3"
        const val COPPER = "Медь"

        fun dec(value: String) = BigDecimal(value)

        /** Equal as numbers: 2.5 and 2.50 are the same price. */
        fun assertSame(expected: BigDecimal, actual: BigDecimal?) {
            assertNotNull(actual)
            assertEquals(0, expected.compareTo(actual), "expected $expected, was $actual")
        }

        fun PricesState.cellAt(cell: PriceCell): CellState =
            (listOf(material) + groupTables + bending + welding).flatMap { grid -> grid.rows.flatMap { it.cells } }.single { it.cell == cell }
    }
}
