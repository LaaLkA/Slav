package io.github.laalka.slav.app.library

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.PartTemplate
import io.github.laalka.slav.data.TemplateWriteResult
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private lateinit var model: LibraryModel

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
        model = LibraryModel(deps.partTemplates)
    }

    @AfterTest
    fun close() = deps.close()

    private val bracket: Part = goldenInput().parts.single()

    private val post = Part("Стойка", Material.RoundTube("Ст3", BigDecimal("57"), BigDecimal("3"), BigDecimal("1000")), operations = emptyList())

    private fun save(name: String, part: Part = bracket): PartTemplate =
        assertIs<TemplateWriteResult.Written>(deps.partTemplates.save(name, part)).template

    private val names get() = model.state.value.rows.map { it.name }

    @Test
    fun `an empty library says it is empty, and the first saved part ends that after a reload`() {
        assertTrue(model.state.value.isEmpty)
        assertEquals(emptyList(), model.state.value.rows)

        save("Кронштейн")
        model.reload()

        assertEquals(false, model.state.value.isEmpty)
        assertEquals(listOf("Кронштейн"), names)
    }

    @Test
    fun `the rows have the name and the material, and the search finds a part of a name in any letter case`() {
        save("Стойка", post)
        save("Кронштейн")
        model.reload()

        assertEquals(
            listOf("Кронштейн" to "Лист Ст3 2 мм, 1000×1000", "Стойка" to "Труба круглая Ст3 57×3 мм, длина 1000 мм"),
            model.state.value.rows.map { it.name to it.summary },
        )

        model.onQueryChange("СТОЙ")
        assertEquals(listOf("Стойка"), names)
        assertEquals(false, model.state.value.isEmpty)

        model.onQueryChange("болт")
        assertEquals(emptyList(), names)
        assertEquals(false, model.state.value.isEmpty, "something is stored, just nothing matches: not the empty state")
        assertEquals("болт", model.state.value.query)
    }

    @Test
    fun `rename starts from the current name and gives the template the new one, trimmed`() {
        val template = save("Кронштейн")

        model.requestRename(template.id)
        assertEquals(RenameQuestion(name = "Кронштейн", error = null), model.state.value.renameQuestion)
        model.onRenameChange("  Кронштейн КР-12 ")
        model.confirmRename()

        assertNull(model.state.value.renameQuestion)
        assertEquals(listOf("Кронштейн КР-12"), names)
        assertEquals(bracket, assertNotNull(deps.partTemplates.get(template.id)).part)
    }

    @Test
    fun `rename refuses a blank name and a name another template has, with the reason under the field`() {
        val first = save("Кронштейн")
        save("Стойка", post)

        model.requestRename(first.id)
        model.onRenameChange("   ")
        model.confirmRename()
        assertEquals(NAME_REQUIRED, model.state.value.renameQuestion?.error)

        model.onRenameChange(" стойка ")
        assertNull(model.state.value.renameQuestion?.error, "typing clears the error")
        model.confirmRename()
        assertEquals(nameTakenMessage("стойка"), model.state.value.renameQuestion?.error)
        assertEquals("Деталь «стойка» уже есть в библиотеке", model.state.value.renameQuestion?.error)
        assertEquals(listOf("Кронштейн", "Стойка"), names, "nothing was renamed")

        model.cancelRename()
        assertNull(model.state.value.renameQuestion)
        assertEquals("Кронштейн", assertNotNull(deps.partTemplates.get(first.id)).name)
    }

    @Test
    fun `rename to the same name or to its own name in another letter case is accepted`() {
        val template = save("Кронштейн")

        model.requestRename(template.id)
        model.confirmRename()
        assertNull(model.state.value.renameQuestion)
        assertEquals(template, deps.partTemplates.get(template.id), "an unchanged name writes nothing")

        model.requestRename(template.id)
        model.onRenameChange("КРОНШТЕЙН")
        model.confirmRename()
        assertNull(model.state.value.renameQuestion)
        assertEquals(listOf("КРОНШТЕЙН"), names)
    }

    @Test
    fun `delete asks first, deletes nothing until confirmed and needs no password`() {
        val first = save("Кронштейн")
        save("Стойка", post)

        model.requestDelete(first.id)
        assertEquals("Кронштейн", model.state.value.deleteQuestion)
        assertEquals(2, deps.partTemplates.list().size)

        model.cancelDelete()
        assertNull(model.state.value.deleteQuestion)
        assertEquals(2, deps.partTemplates.list().size)

        model.requestDelete(first.id)
        model.confirmDelete()
        assertNull(model.state.value.deleteQuestion)
        assertEquals(listOf("Стойка"), names)
        assertNull(deps.partTemplates.get(first.id))
    }

    @Test
    fun `deleting the last template brings the empty state back`() {
        val only = save("Кронштейн")

        model.requestDelete(only.id)
        model.confirmDelete()

        assertTrue(model.state.value.isEmpty)
    }
}
