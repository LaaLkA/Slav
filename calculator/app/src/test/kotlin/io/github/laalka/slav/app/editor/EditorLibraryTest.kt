package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.library.LibraryModel
import io.github.laalka.slav.app.library.LibraryRow
import io.github.laalka.slav.app.library.NAME_REQUIRED
import io.github.laalka.slav.app.library.SavePartDialog
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.PartTemplate
import io.github.laalka.slav.data.StoredCalculation
import io.github.laalka.slav.data.TemplateWriteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «В библиотеку» and «Добавить из библиотеки» of the editor: what the dialogs ask and what the parts become. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private val calculated = mutableListOf<UUID>()

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test")
    }

    @AfterTest
    fun close() = deps.close()

    private fun TestScope.editor(id: UUID? = null) =
        EditorModel(deps.calculations, deps.settings, deps.attachments, deps.partTemplates, deps.fileOpener, backgroundScope, id, onCalculated = { calculated += it })

    /** The part of the golden case as the first part of a new editor holds it. */
    private val goldenPart: Part = goldenInput().parts.single().copy(name = "Деталь 1")

    private fun stored(id: UUID): StoredCalculation.Full = assertIs<StoredCalculation.Full>(deps.calculations.get(id))

    private fun written(result: TemplateWriteResult): PartTemplate = assertIs<TemplateWriteResult.Written>(result).template

    private fun EditorModel.fillGoldenProduct() {
        setChecked(LineRef.OfProduct(OperationKind.WELDING), true)
        onAssemblyGroupChange("Сталь")
        onAssemblyThicknessChange(dec("2"))
        onSeamLengthChange("40")
    }

    private fun Part.sheetLength(): BigDecimal = assertIs<SheetShape.Rectangle>(assertIs<Material.Sheet>(material).shape).lengthMm

    /** Puts the golden part into the library under [name] the way the user does: through the dialog of the part. */
    private fun TestScope.saveGoldenPart(name: String): PartTemplate {
        val editor = editor()
        editor.fillGoldenCase()
        editor.library.requestSave(editor.part().id)
        editor.library.onSaveNameChange(name)
        editor.library.confirmSave()
        return deps.partTemplates.list().first { it.name == name }
    }

    @Test
    fun `a part is saved to the library as the form reads it, named after the part until another name is typed`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()
        val part = editor.part().id

        editor.library.requestSave(part)

        assertEquals(SavePartDialog(name = "Деталь 1", error = null, replacing = null), editor.library.state.value.save)
        editor.library.confirmSave()

        val template = deps.partTemplates.list().single()
        assertEquals("Деталь 1", template.name)
        assertEquals(goldenPart, template.part)
        assertNull(editor.library.state.value.save)
        assertEquals("Деталь «Деталь 1» сохранена в библиотеку", editor.library.state.value.notice)

        editor.library.dismissNotice()
        assertNull(editor.library.state.value.notice)
    }

    @Test
    fun `the name typed in the dialog is the name of the template, trimmed`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()
        editor.library.requestSave(editor.part().id)

        editor.library.onSaveNameChange("  Кронштейн КР-12 ")
        editor.library.confirmSave()

        assertEquals(listOf("Кронштейн КР-12"), deps.partTemplates.list().map { it.name })
        // The part keeps the name it has in the calculation.
        assertEquals("Деталь 1", deps.partTemplates.list().single().part.name)
    }

    @Test
    fun `a blank name keeps the dialog open with an error, and typing clears it`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()
        editor.library.requestSave(editor.part().id)

        editor.library.onSaveNameChange("   ")
        editor.library.confirmSave()

        assertEquals(NAME_REQUIRED, editor.library.state.value.save?.error)
        assertEquals(emptyList(), deps.partTemplates.list())
        editor.library.onSaveNameChange("Кронштейн")
        assertNull(editor.library.state.value.save?.error)
        editor.library.confirmSave()
        assertEquals(1, deps.partTemplates.list().size)
    }

    @Test
    fun `cancel saves nothing, and a part that is gone opens no dialog`() = runTest {
        val editor = editor()
        editor.fillGoldenCase()
        val part = editor.part().id

        editor.library.requestSave(part)
        editor.library.cancelSave()
        editor.library.requestSave(part + 1_000)

        assertNull(editor.library.state.value.save)
        assertEquals(emptyList(), deps.partTemplates.list())
    }

    @Test
    fun `a name that is taken asks to replace, back returns to the name, and replace changes the part but not the name`() = runTest {
        val first = saveGoldenPart("Кронштейн")
        val editor = editor()
        editor.fillGoldenCase()
        val part = editor.part().id
        editor.onDimensionChange(part, DimensionField.LENGTH, "1200")
        editor.library.requestSave(part)

        editor.library.onSaveNameChange("  кронштейн ")
        editor.library.confirmSave()

        // Nothing is replaced until it is confirmed.
        assertEquals(SavePartDialog(name = "  кронштейн ", error = null, replacing = "Кронштейн"), editor.library.state.value.save)
        assertEquals(first, deps.partTemplates.list().single())

        editor.library.backToName()
        assertEquals(SavePartDialog(name = "  кронштейн ", error = null, replacing = null), editor.library.state.value.save)
        editor.library.confirmSave()
        editor.library.confirmReplace()

        val replaced = deps.partTemplates.list().single()
        assertEquals(first.id, replaced.id)
        assertEquals("Кронштейн", replaced.name)
        assertEquals(dec("1200"), replaced.part.sheetLength())
        assertNull(editor.library.state.value.save)
        assertEquals("Деталь «Кронштейн» в библиотеке заменена", editor.library.state.value.notice)
    }

    @Test
    fun `the picker lists the templates with their material and finds one by a part of its name`() = runTest {
        val library = deps.partTemplates
        val bracket = written(library.save("Кронштейн", goldenPart))
        val post = written(library.save("Стойка", Part("Стойка", Material.RoundTube("Ст3", dec("57"), dec("3"), dec("1000")), operations = emptyList())))
        val editor = editor()

        assertNull(editor.library.state.value.pick)
        editor.library.openPick()
        assertEquals(
            listOf(LibraryRow(bracket.id, "Кронштейн", "Лист Ст3 2 мм, 1000×1000"), LibraryRow(post.id, "Стойка", "Труба круглая Ст3 57×3 мм, длина 1000 мм")),
            editor.library.state.value.pick?.rows,
        )

        editor.library.onPickQueryChange("КРОН")
        assertEquals(listOf("Кронштейн"), editor.library.state.value.pick?.rows?.map { it.name })
        editor.library.onPickQueryChange("болт")
        val none = assertNotNull(editor.library.state.value.pick)
        assertEquals(emptyList(), none.rows)
        assertEquals(false, none.libraryIsEmpty, "something is stored, just not this")

        editor.library.closePick()
        assertNull(editor.library.state.value.pick)
    }

    @Test
    fun `the picker of an empty library says it is empty`() = runTest {
        val editor = editor()

        editor.library.openPick()

        val pick = assertNotNull(editor.library.state.value.pick)
        assertEquals(emptyList(), pick.rows)
        assertTrue(pick.libraryIsEmpty)
    }

    @Test
    fun `a part added from the library is a copy of the template at the end of the parts, equal to it`() = runTest {
        val template = written(deps.partTemplates.save("Кронштейн", goldenPart))
        val editor = editor()
        val blank = editor.part().id

        editor.library.openPick()
        editor.library.pick(template.id)

        assertNull(editor.library.state.value.pick)
        assertEquals("Деталь «Кронштейн» добавлена в конец списка деталей", editor.library.state.value.notice)
        assertEquals(2, editor.state.value.parts.size)
        assertEquals(blank, editor.state.value.parts[0].id, "the part that was there stays first")
        assertTrue(editor.state.value.parts.map { it.id }.distinct().size == 2, "the copy has ids of its own")
        val inserted = editor.state.value.parts[1]
        assertEquals("Деталь 1", inserted.name.text)
        assertEquals("Ст3", inserted.grade.selected)
        assertEquals("2", inserted.size.selected)
        assertEquals("1000", inserted.dimension(DimensionField.LENGTH).text)
        assertEquals("16 кг", inserted.mass)
        assertTrue(inserted.operations.first { (it.ref as LineRef.OfPart).kind == OperationKind.LASER_SHEET }.checked)
        assertTrue(inserted.operations.first { (it.ref as LineRef.OfPart).kind == OperationKind.BENDING }.checked)

        editor.flush()
        assertEquals(template.part, stored(deps.calculations.list().single().id).input.parts[1])
    }

    @Test
    fun `the same template added twice gives two parts with ids of their own that edit separately`() = runTest {
        val template = written(deps.partTemplates.save("Кронштейн", goldenPart))
        val editor = editor()
        editor.deletePart(editor.part().id)

        editor.library.pick(template.id)
        editor.library.pick(template.id)
        val (first, second) = editor.state.value.parts.map { it.id }
        editor.onDimensionChange(second, DimensionField.LENGTH, "700")

        assertEquals(2, editor.state.value.parts.size)
        assertEquals("1000", editor.state.value.parts[0].dimension(DimensionField.LENGTH).text)
        assertEquals("700", editor.state.value.parts[1].dimension(DimensionField.LENGTH).text)
        assertTrue(first != second)
        assertEquals(template.part, deps.partTemplates.get(template.id)?.part, "editing the copy leaves the template as it was")
    }

    @Test
    fun `a template that is gone adds nothing`() = runTest {
        val template = written(deps.partTemplates.save("Кронштейн", goldenPart))
        val editor = editor()
        editor.library.openPick()
        deps.partTemplates.delete(template.id)

        editor.library.pick(template.id)

        assertEquals(1, editor.state.value.parts.size)
        assertNull(editor.library.state.value.pick)
        assertNull(editor.library.state.value.notice)
    }

    @Test
    fun `a template whose grade or size the settings do not have is still added, and Рассчитать then says what is missing`() = runTest {
        val unknownGrade = Part(
            name = "Фланец",
            material = Material.Sheet("Х18Н10Т", dec("2"), SheetShape.Rectangle(dec("500"), dec("300"))),
            operations = listOf(PartOperation.LaserSheet(SheetCutMode.ByShape)),
        )
        val unknownSize = unknownGrade.copy(name = "Пластина", material = Material.Sheet("Ст3", dec("7"), SheetShape.Rectangle(dec("500"), dec("300"))))
        val first = written(deps.partTemplates.save("Фланец из нержавейки", unknownGrade))
        val second = written(deps.partTemplates.save("Пластина 7 мм", unknownSize))
        val editor = editor()
        editor.deletePart(editor.part().id)
        editor.onNameChange("Изделие")

        editor.library.pick(first.id)
        editor.library.pick(second.id)

        val (flange, plate) = editor.state.value.parts
        assertEquals("Х18Н10Т", flange.grade.selected, "shown as it was saved, though the settings do not list it")
        assertEquals("7", plate.size.selected, "likewise a thickness the sortament does not have")
        assertTrue(editor.state.value.canCalculate, "the form is complete: only the settings lack the grade and the size")
        editor.calculate()

        assertEquals(emptyList(), calculated)
        val messages = editor.state.value.calcErrors.map { it.message }
        assertTrue("Марки «Х18Н10Т» нет в настройках" in messages, messages.toString())
        assertTrue(messages.any { it.startsWith("Нет цены в таблице") && "7" in it }, "no price cell for 7 mm: $messages")
        editor.flush()
        assertEquals(listOf(unknownGrade, unknownSize), deps.calculations.list().single().let { stored(it.id) }.input.parts, "the parts are kept in the draft")
    }

    @Test
    fun `a part taken from the library does not follow later changes of the template`() = runTest {
        val template = written(deps.partTemplates.save("Кронштейн", goldenPart))
        val editor = editor()
        editor.deletePart(editor.part().id)
        editor.library.pick(template.id)
        editor.onNameChange("Изделие")
        editor.fillGoldenProduct()
        editor.flush()
        val draft = deps.calculations.list().single().id
        assertEquals(listOf(goldenPart), stored(draft).input.parts, "the part in the calculation equals the template")

        // Each way the program changes a template is followed by what the user does on coming back to the editor
        // from «Детали»: the part in the calculation is still the one that was added.
        fun assertUnchanged(step: String) {
            editor.resume()
            editor.flush()
            assertEquals(listOf(goldenPart), stored(draft).input.parts, "stored draft after $step")
            assertEquals("1000", editor.part().dimension(DimensionField.LENGTH).text, "editor after $step")
            assertEquals("1000", editor(draft).part().dimension(DimensionField.LENGTH).text, "calculation reopened after $step")
        }

        // 1. «В библиотеку» on another part under the same name, «Заменить».
        replaceTemplate("Кронштейн", length = "2000")
        assertEquals(dec("2000"), assertNotNull(deps.partTemplates.get(template.id)).part.sheetLength(), "the template did change")
        assertUnchanged("the template was replaced")

        // The control: a part added now is the new one, so the check above could have seen a difference.
        val other = editor()
        other.library.pick(template.id)
        assertEquals("2000", other.state.value.parts.last().dimension(DimensionField.LENGTH).text)

        // 2. Renamed in «Детали».
        val library = LibraryModel(deps.partTemplates)
        library.requestRename(template.id)
        library.onRenameChange("Кронштейн КР-12")
        library.confirmRename()
        assertEquals("Кронштейн КР-12", assertNotNull(deps.partTemplates.get(template.id)).name)
        assertUnchanged("the template was renamed")

        // 3. Deleted in «Детали».
        library.requestDelete(template.id)
        library.confirmDelete()
        assertNull(deps.partTemplates.get(template.id))
        assertUnchanged("the template was deleted")

        editor.calculate()
        val calculation = stored(calculated.single())
        assertEquals(listOf(goldenPart), calculation.input.parts)
        assertEquals(0, dec("11340.00").compareTo(assertNotNull(calculation.unitPrice).amount), "priced as the part was added, not as the template is now")
    }

    /** «В библиотеку» on a golden part of [length] under [name] when that name is taken: the replace question, confirmed. */
    private fun TestScope.replaceTemplate(name: String, length: String) {
        val editor = editor()
        editor.fillGoldenCase()
        editor.onDimensionChange(editor.part().id, DimensionField.LENGTH, length)
        editor.library.requestSave(editor.part().id)
        editor.library.onSaveNameChange(name)
        editor.library.confirmSave()
        assertEquals(name, editor.library.state.value.save?.replacing)
        editor.library.confirmReplace()
    }
}
