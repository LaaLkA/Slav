package io.github.laalka.slav.app

import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.editor.fillGoldenCase
import io.github.laalka.slav.app.quick.QuickEditorModel
import io.github.laalka.slav.app.quick.fillQuickGoldenCase
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import io.github.laalka.slav.data.CalculationStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavigatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
    }

    @AfterTest
    fun close() = deps.close()

    private val Navigator.editor: EditorModel get() = assertIs<Page.Editor>(page.value).model

    private val Navigator.quickEditor: QuickEditorModel get() = assertIs<Page.QuickEditor>(page.value).model

    /** The golden case calculated through «Новый расчёт»; the navigator is left on its card. */
    private fun TestScope.calculated(): Pair<Navigator, UUID> {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null))
        navigator.editor.fillGoldenCase()
        navigator.editor.calculate()
        val card = assertIs<Page.Card>(navigator.page.value)
        return navigator to card.model.id
    }

    @Test
    fun `Изменить on a calculated card opens a revision draft in the editor and leaves the original alone`() = runTest {
        val (navigator, id) = calculated()
        val before = assertNotNull(deps.calculations.get(id))
        val card = assertIs<Page.Card>(navigator.page.value).model

        navigator.navigate(Screen.Editor(card.edit()))
        val editor = navigator.editor
        editor.onNameChange("Кронштейн с ребром")
        editor.calculate()

        // The new version is calculated on its own row and shown as its own card; the original is as it was.
        val revision = assertIs<Page.Card>(navigator.page.value).model.id
        assertNotEquals(id, revision)
        assertEquals(before, deps.calculations.get(id))
        val stored = assertNotNull(deps.calculations.get(revision))
        assertEquals("Кронштейн с ребром", stored.name)
        assertEquals(CalculationStatus.CALCULATED, stored.status)
        assertEquals(id, stored.sourceId)
        assertEquals(before.snapshot, stored.snapshot)
        assertEquals(before.snapshotAt, stored.snapshotAt, "the same prices, so the same date of the snapshot")
        assertEquals(2, deps.calculations.list().size)
    }

    @Test
    fun `leaving an untouched revision draft deletes it silently, a changed one stays a draft`() = runTest {
        val (navigator, id) = calculated()
        val card = assertIs<Page.Card>(navigator.page.value).model

        val untouched = card.edit()
        navigator.navigate(Screen.Editor(untouched))
        navigator.navigate(Screen.Calculations)

        assertEquals(Page.Calculations, navigator.page.value)
        assertNull(deps.calculations.get(untouched), "an automatic copy nobody changed is not kept")
        assertEquals(listOf(id), deps.calculations.list().map { it.id })

        val edited = card.edit()
        navigator.navigate(Screen.Editor(edited))
        navigator.editor.onQuantityChange("4")
        navigator.navigate(Screen.Calculations)

        val draft = assertNotNull(deps.calculations.get(edited))
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(4, draft.quantity)
        assertEquals(id, draft.sourceId)
        assertEquals(1, assertNotNull(deps.calculations.get(id)).quantity)
    }

    @Test
    fun `closing the window drops an untouched revision draft`() = runTest {
        val (navigator, id) = calculated()
        val card = assertIs<Page.Card>(navigator.page.value).model
        val untouched = card.edit()
        navigator.navigate(Screen.Editor(untouched))
        var exited = false

        navigator.requestExit { exited = true }

        assertEquals(true, exited)
        assertNull(deps.calculations.get(untouched))
        assertNotNull(deps.calculations.get(id))
    }

    @Test
    fun `a new version from the card opens as its own card, and a deleted one returns to the list`() = runTest {
        val (navigator, id) = calculated()
        val card = assertIs<Page.Card>(navigator.page.value).model
        card.requestQuantityChange()
        card.onQuantityChange("10")
        card.confirmQuantityChange()

        val revisionCard = assertIs<Page.Card>(navigator.page.value).model
        assertNotEquals(id, revisionCard.id)
        assertEquals("113 400,00 ₽", revisionCard.state.value.total)
        assertEquals(id, revisionCard.state.value.source?.id)

        navigator.open(assertNotNull(revisionCard.state.value.source))
        assertEquals(id, assertIs<Page.Card>(navigator.page.value).model.id)
        assertEquals(listOf(revisionCard.id), assertIs<Page.Card>(navigator.page.value).model.state.value.revisions.map { it.id })

        val original = assertIs<Page.Card>(navigator.page.value).model
        original.requestDelete()
        original.onDeletePasswordChange(ADMIN_PASSWORD)
        original.confirmDelete()
        assertEquals(Page.Calculations, navigator.page.value)
        assertNull(deps.calculations.get(id))
    }

    @Test
    fun `Детали saves the open editor and keeps it, and Расчёты returns to the same editor`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null))
        val editor = navigator.editor
        editor.onNameChange("Рама")

        navigator.navigate(Screen.Library)

        assertEquals(Page.Library, navigator.page.value)
        assertEquals("Рама", deps.calculations.list().single().name, "the draft is saved on the way to the library")
        navigator.selectCalculations()
        assertSame(editor, navigator.editor)
        assertEquals("Рама", editor.state.value.name.text)
    }

    @Test
    fun `from Детали to the settings and back the rail's Расчёты still returns to the calculation that was open`() = runTest {
        val (navigator, id) = calculated()
        val card = assertIs<Page.Card>(navigator.page.value).model

        navigator.navigate(Screen.Library)
        navigator.navigate(Screen.Settings)
        assertEquals(Page.Settings, navigator.page.value)
        navigator.navigate(Screen.Library)
        navigator.selectCalculations()

        assertSame(card, assertIs<Page.Card>(navigator.page.value).model)
        assertEquals(id, card.id)
    }

    @Test
    fun `Детали reads the library again each time it is shown`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Library)
        assertEquals(true, navigator.library.state.value.isEmpty)

        navigator.navigate(Screen.Calculations)
        navigator.navigate(Screen.Editor(null))
        navigator.editor.fillGoldenCase()
        navigator.editor.library.requestSave(navigator.editor.state.value.parts.single().id)
        navigator.editor.library.onSaveNameChange("Кронштейн")
        navigator.editor.library.confirmSave()
        navigator.navigate(Screen.Library)

        assertEquals(listOf("Кронштейн"), navigator.library.state.value.rows.map { it.name })
    }

    @Test
    fun `closing the window from Детали saves the open editor`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null))
        navigator.editor.onNameChange("Рама")
        navigator.navigate(Screen.Library)
        var exited = false

        navigator.requestExit { exited = true }

        assertEquals(true, exited)
        assertEquals("Рама", deps.calculations.list().single().name)
    }

    @Test
    fun `the open editor survives a visit to the settings and sees prices changed there`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null))
        val editor = navigator.editor
        editor.onNameChange("Рама")

        navigator.navigate(Screen.Settings)
        assertEquals("Рама", deps.calculations.list().single().name, "the draft is saved on the way to the settings")
        deps.settings.save(deps.settings.load().let { it.copy(general = it.general.copy(currencySymbol = "руб.")) })
        navigator.selectCalculations()

        assertSame(editor, navigator.editor)
        assertEquals("Рама", editor.state.value.name.text)
        assertEquals("руб.", editor.state.value.currency)
    }

    @Test
    fun `Новый расчёт of each kind opens the editor of that kind`() = runTest {
        val navigator = Navigator(deps, backgroundScope)

        navigator.navigate(Screen.Editor(null))
        assertIs<Page.Editor>(navigator.page.value)
        navigator.navigate(Screen.Editor(null, CalculationKind.QUICK))
        assertIs<Page.QuickEditor>(navigator.page.value)
        navigator.navigate(Screen.Editor(null, CalculationKind.FULL))
        assertIs<Page.Editor>(navigator.page.value)
    }

    @Test
    fun `a simplified calculation goes from its editor to its card, and Изменить opens its revision draft in the quick editor`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null, CalculationKind.QUICK))
        navigator.quickEditor.fillQuickGoldenCase()
        navigator.quickEditor.calculate()
        val card = assertIs<Page.Card>(navigator.page.value).model
        assertEquals("54 000,00 ₽", card.state.value.total)
        val before = assertNotNull(deps.calculations.get(card.id))

        // The kind of the saved calculation, not the one the screen names, picks the editor.
        navigator.navigate(Screen.Editor(card.edit(), CalculationKind.FULL))
        navigator.quickEditor.onNameChange("Лист с припуском")
        navigator.quickEditor.calculate()

        val revision = assertIs<Page.Card>(navigator.page.value).model.id
        assertNotEquals(card.id, revision)
        assertEquals(before, deps.calculations.get(card.id))
        assertEquals("Лист с припуском", assertNotNull(deps.calculations.get(revision)).name)
        assertEquals(card.id, assertNotNull(deps.calculations.get(revision)).sourceId)
        assertEquals(2, deps.calculations.list().size)
    }

    @Test
    fun `a quick draft opens in the quick editor from the list and from a copy, and leaving the editor saves it`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null, CalculationKind.QUICK))
        navigator.quickEditor.onNameChange("Лист")
        navigator.quickEditor.onSheetAreaChange("2")

        navigator.navigate(Screen.Calculations)

        val row = navigator.calculationList.state.value.rows.single()
        assertEquals(CalculationKind.QUICK, row.kind)
        assertEquals("Лист", row.name)
        navigator.open(row)
        assertEquals("2", navigator.quickEditor.state.value.sheetArea.text)

        navigator.navigate(Screen.Calculations)
        navigator.copy(row.id)
        assertEquals("Лист", navigator.quickEditor.state.value.name.text)
        assertEquals(2, deps.calculations.list().size)
        assertEquals(setOf(CalculationKind.QUICK), deps.calculations.list().map { it.kind }.toSet())
    }

    @Test
    fun `Детали and the settings save the open quick editor, and the rail returns to it`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null, CalculationKind.QUICK))
        val editor = navigator.quickEditor
        editor.onNameChange("Лист")

        navigator.navigate(Screen.Settings)
        assertEquals("Лист", deps.calculations.list().single().name, "the draft is saved on the way to the settings")
        deps.settings.save(deps.settings.load().let { it.copy(general = it.general.copy(currencySymbol = "руб.")) })
        navigator.selectCalculations()

        assertSame(editor, navigator.quickEditor)
        assertEquals("руб.", editor.state.value.currency)
        var exited = false
        navigator.requestExit { exited = true }
        assertEquals(true, exited)
    }
}
