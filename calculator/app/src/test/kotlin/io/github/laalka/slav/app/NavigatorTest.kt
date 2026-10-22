package io.github.laalka.slav.app

import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.editor.fillGoldenCase
import io.github.laalka.slav.data.AppPaths
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavigatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() = deps.close()

    private val Navigator.editor: EditorModel get() = assertIs<Page.Editor>(page.value).model

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
    fun `leaving an edited calculated calculation asks first, and discarding restores it`() = runTest {
        val (navigator, id) = calculated()
        val before = assertNotNull(deps.calculations.get(id))
        navigator.navigate(Screen.Editor(id))
        val editor = navigator.editor
        editor.onNameChange("Кронштейн с ребром")

        navigator.navigate(Screen.Calculations)

        assertEquals("Кронштейн с ребром", navigator.leaveQuestion.value)
        assertSame(editor, navigator.editor, "the editor stays open while the question is asked")
        navigator.answerLeave(LeaveAnswer.DISCARD)
        assertEquals(Page.Calculations, navigator.page.value)
        assertNull(navigator.leaveQuestion.value)
        assertEquals(before, deps.calculations.get(id))
    }

    @Test
    fun `closing the window waits for the answer and keeps the edit as a draft`() = runTest {
        val (navigator, id) = calculated()
        navigator.navigate(Screen.Editor(id))
        navigator.editor.onQuantityChange("4")
        var exited = false

        navigator.requestExit { exited = true }
        assertEquals(false, exited)
        navigator.answerLeave(LeaveAnswer.KEEP_DRAFT)

        assertEquals(true, exited)
        val draft = assertNotNull(deps.calculations.get(id))
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals(4, draft.quantity)
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
}
