package io.github.laalka.slav.app.settings

import io.github.laalka.slav.app.ADMIN_PASSWORD
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.ThemeMode
import io.github.laalka.slav.app.calculations.WRONG_PASSWORD
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.emptyPriceBook
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.core.Grade
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.AppPaths
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeveloperTablesModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private lateinit var store: SettingsStore
    private lateinit var general: GeneralModel
    private lateinit var materials: MaterialsModel
    private lateinit var sortament: SortamentModel
    private lateinit var prices: PricesModel

    /** Every notification of the store: true when the settings were replaced, false when they were changed. */
    private val notifications = mutableListOf<Boolean>()

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
        deps.preferences.setDeveloperMode(true)
        start(seed = 1)
    }

    @AfterTest
    fun close() = deps.close()

    /** The models over the settings stored now, with a fill drawn from [seed]. */
    private fun start(seed: Int) {
        store = SettingsStore(deps.settings)
        general = GeneralModel(store, deps.preferences, deps.adminPassword, Random(seed))
        materials = MaterialsModel(store)
        sortament = SortamentModel(store)
        prices = PricesModel(store)
        notifications.clear()
        store.addListener { notifications += it }
    }

    private fun startOn(book: PriceBook, seed: Int = 1) {
        deps.settings.save(book)
        start(seed)
    }

    private val stored: PriceBook get() = deps.settings.load()

    private fun dec(text: String) = BigDecimal(text)

    private fun noticeText() = general.state.value.developerNotice

    // ---- the fill ----

    @Test
    fun `one press on empty settings fills the four tables in one change that is saved once`() {
        startOn(emptyPriceBook())

        general.fillTablesRandomly()

        val book = store.book.value
        assertEquals(listOf(false), notifications, "one update, one save, no replacement")
        assertEquals(book, stored, "what the store holds is what was saved")
        assertEquals(listOf("Сталь чёрная", "Нержавейка", "Алюминий"), book.groups.map { it.name })
        assertEquals(4, book.grades.size)
        assertTrue(book.sortament.sheetThicknessesMm.isNotEmpty() && book.sortament.channels.isNotEmpty())
        assertTrue(book.priceCells().all { book.priceAt(it) != null }, "an empty price cell is left")
        // Every tab sees it at once.
        assertEquals(3, materials.state.value.groups.size)
        assertEquals(book.sortament.sheetThicknessesMm.size, sortament.state.value.sections.first().rows.size)
        assertTrue(prices.state.value.material.rows.all { row -> row.cells.all { it.text.isNotEmpty() } })
    }

    @Test
    fun `the confirmation counts what was added`() {
        startOn(emptyPriceBook())

        general.fillTablesRandomly()

        val book = store.book.value
        val sizes = book.sortament.let { it.sheetThicknessesMm.size + it.roundTubes.size + it.profileTubes.size + it.angles.size + it.channels.size + it.iBeams.size }
        val prices = book.priceCells().size
        assertEquals(85, sizes, "the catalog of the sortament")
        assertEquals(4 * book.sortament.allThicknesses().size, book.priceCells().count { it is PriceCell.Material })
        assertTrue(prices > 500, "$prices prices")
        // «цен» ends the text for the counts that do not end in 1–4, which this one does not.
        assertEquals("Добавлено: 4 марки, 85 размеров, $prices цен", noticeText())
    }

    @Test
    fun `a second press adds a grade and the prices of that grade, and says so`() {
        startOn(emptyPriceBook())
        general.fillTablesRandomly()
        val first = store.book.value

        general.fillTablesRandomly()

        val second = store.book.value
        assertEquals(first.grades + second.grades.last(), second.grades)
        assertEquals(first.sortament, second.sortament)
        val thicknesses = second.sortament.allThicknesses().size
        assertTrue(noticeText()!!.startsWith("Добавлено: 1 марка, 0 размеров, $thicknesses "), noticeText())
        assertEquals(listOf(false, false), notifications)
    }

    @Test
    fun `typed prices and names survive a press, whatever they are`() {
        val typed = PriceBook.default()
            .withPrice(PriceCell.Material("Ст3", dec("2")), dec("77.5"))
            .withPrice(PriceCell.Group(GroupTable.LASER_SHEET_CUT, "Сталь", dec("3")), BigDecimal.ZERO)
            .withPrice(PriceCell.Painting, dec("1234.56"))
            .withPrice(PriceCell.LaserCutPerMinute, null)
        startOn(typed)

        general.fillTablesRandomly()

        val book = store.book.value
        assertEquals(dec("77.5"), book.priceAt(PriceCell.Material("Ст3", dec("2"))))
        assertEquals(BigDecimal.ZERO, book.priceAt(PriceCell.Group(GroupTable.LASER_SHEET_CUT, "Сталь", dec("3"))))
        assertEquals(dec("1234.56"), book.priceAt(PriceCell.Painting))
        assertNotNull(book.priceAt(PriceCell.LaserCutPerMinute), "the empty laser rate is filled")
        assertEquals("Сталь", book.groups.first().name)
        assertEquals(Grade("Ст3", "Сталь", dec("8000")), book.grades.first())
        assertEquals(book, stored)
    }

    @Test
    fun `the same seed fills the same way and another seed another way`() {
        startOn(emptyPriceBook(), seed = 7)
        general.fillTablesRandomly()
        val first = store.book.value
        val firstNotice = noticeText()

        startOn(emptyPriceBook(), seed = 7)
        general.fillTablesRandomly()
        assertEquals(first, store.book.value)
        assertEquals(firstNotice, noticeText())

        startOn(emptyPriceBook(), seed = 8)
        general.fillTablesRandomly()
        assertNotEquals(first, store.book.value)
    }

    @Test
    fun `turning developer mode off takes the confirmation away`() {
        general.fillTablesRandomly()
        assertNotNull(noticeText())

        general.onDeveloperModeChange(false)

        assertNull(noticeText())
    }

    // ---- the reset ----

    private fun password(text: String) {
        general.onResetPasswordChange(text)
        general.confirmResetTables()
    }

    @Test
    fun `price cells are exactly the cells the price tabs draw`() {
        startOn(emptyPriceBook())
        general.fillTablesRandomly()
        val shown = prices.state.value.let { state ->
            (listOf(state.material) + state.groupTables + state.bending + state.welding)
                .flatMap { grid -> grid.rows.flatMap { row -> row.cells.map { it.cell } } } + state.painting.cell + state.laserCutPerMinute.cell
        }

        assertEquals(shown, store.book.value.priceCells(), "the same cells in the same order")
        assertEquals(shown.size, shown.toSet().size)
    }

    @Test
    fun `the reset asks the password first and changes nothing until it is right`() {
        general.fillTablesRandomly()
        val filled = store.book.value
        notifications.clear()

        general.requestResetTables()

        assertEquals(ResetQuestion(error = null), general.state.value.resetQuestion)
        assertEquals(filled, store.book.value)
        assertEquals(filled, stored)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `a wrong password leaves the question open with its error and the tables as they were, and so does an empty one`() {
        general.fillTablesRandomly()
        val filled = store.book.value
        notifications.clear()
        general.requestResetTables()

        password("not-the-password")
        assertEquals(ResetQuestion(error = WRONG_PASSWORD), general.state.value.resetQuestion)
        general.onResetPasswordChange("")
        general.confirmResetTables()
        assertEquals(ResetQuestion(error = WRONG_PASSWORD), general.state.value.resetQuestion)

        assertEquals(filled, store.book.value)
        assertEquals(filled, stored)
        assertTrue(notifications.isEmpty())
        assertNotEquals(PriceBook.default(), stored)
    }

    @Test
    fun `cancelling the question changes nothing and the next one starts clean`() {
        general.fillTablesRandomly()
        val filled = store.book.value
        general.requestResetTables()
        general.onResetPasswordChange("wrong")
        general.confirmResetTables()

        general.cancelResetTables()

        assertNull(general.state.value.resetQuestion)
        assertEquals(filled, store.book.value)
        general.requestResetTables()
        assertEquals(ResetQuestion(error = null), general.state.value.resetQuestion, "the old error is not shown again")
        // The typed password was forgotten with the first question.
        general.confirmResetTables()
        assertEquals(ResetQuestion(error = WRONG_PASSWORD), general.state.value.resetQuestion)
        assertEquals(filled, store.book.value)
    }

    @Test
    fun `the right password returns the settings to the first-run book, stored and shown, and says so`() {
        general.fillTablesRandomly()
        general.requestResetTables()

        password(ADMIN_PASSWORD)

        assertEquals(PriceBook.default(), store.book.value)
        assertEquals(PriceBook.default(), stored)
        assertEquals(PriceBook.default(), SettingsStore(deps.settings).book.value, "a restart reads the same")
        assertNull(general.state.value.resetQuestion)
        assertEquals("Таблицы приведены в исходное состояние", noticeText())
        assertEquals(listOf("Сталь"), materials.state.value.groups.map { it.name })
        assertEquals(listOf("Ст3"), materials.state.value.grades.map { it.name })
        assertEquals("50", prices.state.value.laserCutPerMinute.text)
    }

    @Test
    fun `the first-run book is the one an empty database stores, not a copy of it`() {
        // A fresh database: the repository stores PriceBook.default() on the first load, the reset replaces the settings with the same.
        general.fillTablesRandomly()
        general.requestResetTables()
        password(ADMIN_PASSWORD)

        val fresh = Deps(AppPaths(temp.newFolder("fresh").toPath()), appVersion = "test", adminSeed = testAdminSeed())
        fresh.use { assertEquals(it.settings.load(), stored) }
    }

    @Test
    fun `the reset is a replacement, so every tab drops what was typed and not saved`() {
        general.fillTablesRandomly()
        val cell = PriceCell.Material("Ст3", dec("2"))
        fun typedCell() = prices.state.value.material.rows.first { it.label == "Ст3" }.cells.first { it.cell == cell }
        prices.onCellChange(cell, "abc")
        general.onCurrencyChange("")
        materials.startRename(RenameTarget.GROUP, "Сталь")
        assertEquals(CellState(cell, "abc", NOT_A_NUMBER), typedCell())
        assertEquals("", general.state.value.currency.text)
        val epochs = listOf(prices.state.value.epoch, sortament.state.value.epoch, materials.state.value.epoch, general.state.value.epoch)
        notifications.clear()

        general.requestResetTables()
        password(ADMIN_PASSWORD)

        assertEquals(listOf(true), notifications, "told once, as a replacement")
        assertEquals(
            epochs.map { it + 1 },
            listOf(prices.state.value.epoch, sortament.state.value.epoch, materials.state.value.epoch, general.state.value.epoch),
            "every tab re-creates its fields",
        )
        assertEquals(CellState(cell, "200", null), typedCell(), "the default price of Ст3 at 2 mm, no typed text")
        assertEquals("₽", general.state.value.currency.text)
        assertNull(general.state.value.currency.error)
        assertNull(materials.state.value.rename)
    }

    @Test
    fun `the general settings that are part of the book go back with it, the password theme and developer mode stay`() {
        general.onCurrencyChange("руб.")
        general.onWeldTypeChange("плазма")
        general.addWeldType()
        general.setTheme(ThemeMode.DARK)
        assertNotEquals(PriceBook.default().general, store.book.value.general)

        general.requestResetTables()
        password(ADMIN_PASSWORD)

        assertEquals(PriceBook.default().general, store.book.value.general)
        assertEquals(ThemeMode.DARK, deps.preferences.themeMode.value)
        assertTrue(deps.preferences.developerMode.value)
        assertTrue(general.state.value.developerMode)
        assertTrue(deps.adminPassword.verify(ADMIN_PASSWORD.toCharArray()), "the administrator password is the same")
    }

    @Test
    fun `calculations and the library are not touched, a calculation keeps its own prices`() {
        general.fillTablesRandomly()
        val id = deps.saveCalculated(goldenInput("До сброса"))
        deps.partTemplates.save("Кронштейн", goldenInput().parts.single())
        val calculation = deps.calculations.get(id)
        val calculations = deps.calculations.list()
        val library = deps.partTemplates.list()
        val snapshot = assertNotNull(assertNotNull(calculation).snapshot)

        general.requestResetTables()
        password(ADMIN_PASSWORD)

        assertEquals(calculation, deps.calculations.get(id))
        assertEquals(calculations, deps.calculations.list())
        assertEquals(library, deps.partTemplates.list())
        assertEquals(1, deps.calculations.list().size)
        assertEquals(1, deps.partTemplates.list().size)
        assertNotEquals(PriceBook.default(), snapshot, "the calculation was priced on the filled tables, not the first-run ones")
    }

    @Test
    fun `a cancelled or wrong reset leaves the calculations and the library alone too`() {
        val id = deps.saveCalculated(goldenInput("Расчёт"))
        val calculation = deps.calculations.get(id)

        general.requestResetTables()
        password("nope")
        general.cancelResetTables()

        assertEquals(calculation, deps.calculations.get(id))
        assertEquals(1, deps.calculations.list().size)
    }

    @Test
    fun `the store replaces the settings and tells the tabs even when nothing differs`() {
        val book = store.book.value

        store.replace(book)

        assertEquals(listOf(true), notifications)
        assertEquals(book, stored)
    }
}
