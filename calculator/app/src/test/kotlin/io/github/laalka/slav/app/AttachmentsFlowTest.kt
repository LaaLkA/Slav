package io.github.laalka.slav.app

import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drawings along the ways a calculation is opened, copied, revised and deleted from the screens. */
class AttachmentsFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: AppPaths
    private lateinit var deps: Deps
    private lateinit var incoming: Path

    @BeforeTest
    fun open() {
        paths = AppPaths(temp.newFolder("data").toPath())
        deps = Deps(paths, appVersion = "test", adminSeed = testAdminSeed(), fileOpener = RecordingOpener())
        incoming = temp.newFolder("incoming").toPath()
    }

    @AfterTest
    fun close() = deps.close()

    private val Navigator.editor: EditorModel get() = assertIs<Page.Editor>(page.value).model

    private fun drawing(name: String, size: Int = 64): Path = incoming.resolve(name).also { it.writeBytes(ByteArray(size) { i -> (i + name.length).toByte() }) }

    private fun folder(id: UUID): Path = paths.attachmentsDir.resolve(id.toString())

    private fun names(id: UUID): List<String> = deps.attachments.list(id).map { it.fileName }

    /** A calculated record with one drawing, and a navigator on the list. */
    private fun TestScope.calculatedWithDrawing(): Pair<Navigator, UUID> {
        val id = deps.saveCalculated(goldenInput())
        deps.attachments.add(id, drawing("plan.pdf"))
        return Navigator(deps, backgroundScope) to id
    }

    @Test
    fun `attaching in a new editor saves what is typed as a draft first, and the editor goes on with that draft`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null))
        val editor = navigator.editor
        editor.onNameChange("Кронштейн")
        assertEquals(emptyList(), deps.calculations.list())

        editor.attachments.attach(drawing("план.png"))

        val draft = deps.calculations.list().single()
        assertEquals(CalculationStatus.DRAFT, draft.status)
        assertEquals("Кронштейн", draft.name)
        assertEquals(listOf("план.png"), names(draft.id))
        assertEquals(listOf("план.png"), editor.attachments.state.value.rows.map { it.fileName })

        editor.onNameChange("Кронштейн с ребром")
        editor.flush()
        assertEquals(draft.id, deps.calculations.list().single().id, "the same draft, not a second one")
        assertEquals("Кронштейн с ребром", assertNotNull(deps.calculations.get(draft.id)).name)
        assertEquals(listOf("план.png"), names(draft.id))
    }

    @Test
    fun `a file that is refused in a new editor does not save an empty draft`() = runTest {
        val navigator = Navigator(deps, backgroundScope)
        navigator.navigate(Screen.Editor(null))

        navigator.editor.attachments.attach(drawing("scheme.dwg"))

        assertEquals(emptyList(), deps.calculations.list())
        assertTrue(assertNotNull(navigator.editor.attachments.state.value.message).isError)
    }

    @Test
    fun `the copy from the list opens with its own copies of the drawings`() = runTest {
        val (navigator, id) = calculatedWithDrawing()

        navigator.copy(id)

        val copy = deps.calculations.list().single { it.status == CalculationStatus.DRAFT }.id
        assertEquals(listOf("plan.pdf"), navigator.editor.attachments.state.value.rows.map { it.fileName })
        val original = deps.attachments.list(id).single()
        val copied = deps.attachments.list(copy).single()
        assertNotEquals(deps.attachments.pathOf(original), deps.attachments.pathOf(copied))
        assertTrue(deps.attachments.pathOf(copied).readBytes().contentEquals(deps.attachments.pathOf(original).readBytes()))
    }

    @Test
    fun `a new version made from the card takes the drawings along`() = runTest {
        val (navigator, id) = calculatedWithDrawing()
        navigator.navigate(Screen.Card(id))
        val card = assertIs<Page.Card>(navigator.page.value).model

        card.requestQuantityChange()
        card.onQuantityChange("5")
        card.confirmQuantityChange()

        val version = assertIs<Page.Card>(navigator.page.value).model
        assertNotEquals(id, version.id)
        assertEquals(listOf("plan.pdf"), version.attachments.state.value.rows.map { it.fileName })
        assertEquals(listOf("plan.pdf"), names(id), "the source keeps its own")
    }

    @Test
    fun `an untouched revision draft goes with its drawings, one with a drawing added is kept`() = runTest {
        val (navigator, id) = calculatedWithDrawing()
        navigator.navigate(Screen.Card(id))
        val card = assertIs<Page.Card>(navigator.page.value).model

        val untouched = card.edit()
        navigator.navigate(Screen.Editor(untouched))
        assertEquals(listOf("plan.pdf"), navigator.editor.attachments.state.value.rows.map { it.fileName })
        navigator.navigate(Screen.Calculations)

        assertNull(deps.calculations.get(untouched))
        assertFalse(folder(untouched).exists(), "the folder of the deleted draft is gone too")

        val changed = card.edit()
        navigator.navigate(Screen.Editor(changed))
        navigator.editor.attachments.attach(drawing("detail.jpg"))
        navigator.navigate(Screen.Calculations)

        assertEquals(CalculationStatus.DRAFT, assertNotNull(deps.calculations.get(changed)).status)
        assertEquals(listOf("plan.pdf", "detail.jpg"), names(changed))
        assertEquals(listOf("plan.pdf"), names(id))
    }

    @Test
    fun `deleting a calculation from the list removes its drawings only after the right password`() = runTest {
        val (navigator, id) = calculatedWithDrawing()
        val list = navigator.calculationList

        list.requestDelete(id)
        list.onDeletePasswordChange("not-the-password")
        list.confirmDelete()

        assertNotNull(deps.calculations.get(id))
        assertTrue(folder(id).exists())
        assertEquals(listOf("plan.pdf"), names(id))

        list.onDeletePasswordChange(ADMIN_PASSWORD)
        list.confirmDelete()

        assertNull(deps.calculations.get(id))
        assertFalse(folder(id).exists())
        assertEquals(emptyList(), names(id))
    }

    @Test
    fun `deleting a calculation from its card removes its drawings`() = runTest {
        val (navigator, id) = calculatedWithDrawing()
        navigator.navigate(Screen.Card(id))
        val card = assertIs<Page.Card>(navigator.page.value).model

        card.requestDelete()
        card.onDeletePasswordChange(ADMIN_PASSWORD)
        card.confirmDelete()

        assertNull(deps.calculations.get(id))
        assertFalse(folder(id).exists())
        assertEquals(emptyList(), names(id))
    }
}
