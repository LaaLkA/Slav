package io.github.laalka.slav.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.library.LibraryTags
import io.github.laalka.slav.app.library.nameTakenMessage
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LibraryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun type(tag: String, text: String) {
        node(tag).performScrollTo().performTextReplacement(text)
    }

    /** A field of a dialog: it is not in the scrolling body of the editor, so there is nothing to scroll. */
    private fun typeInDialog(tag: String, text: String) {
        node(tag).performTextReplacement(text)
    }

    private fun click(tag: String) {
        node(tag).performScrollTo().performClick()
    }

    private fun choose(tag: String, option: String) {
        click(tag)
        node("$tag/$option").performClick()
    }

    private fun deps() = Deps(AppPaths(temp.root.toPath()), appVersion = "test")

    /** The part of the golden case: sheet Ст3 2 mm 1000 × 1000, laser contour 4000 mm with 3 pierces, 4 bends of 80 mm. */
    private fun fillGoldenPart() {
        choose("part/0/grade", "Ст3")
        choose("part/0/size", "2")
        type("part/0/length", "1000")
        type("part/0/width", "1000")
        click("part/0/op/LASER_SHEET/check")
        click("part/0/op/LASER_SHEET/mode/CONTOUR")
        type("part/0/op/LASER_SHEET/contour-length", "4000")
        type("part/0/op/LASER_SHEET/pierces", "3")
        click("part/0/op/BENDING/check")
        type("part/0/op/BENDING/rows/0/length", "80")
        type("part/0/op/BENDING/rows/0/count", "4")
    }

    private fun saveToLibrary(name: String) {
        click(LibraryTags.toLibrary("part/0"))
        typeInDialog(LibraryTags.SAVE_NAME, name)
        node(LibraryTags.SAVE_CONFIRM).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a part saved to the library is added to a new calculation and the golden case with the welding is 11 340,00`() {
        deps().use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            // A part filled in once, put into the library from its card; the dialog starts with the name of the part.
            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            fillGoldenPart()
            click(LibraryTags.toLibrary("part/0"))
            node(LibraryTags.SAVE_NAME).assertTextContains("Деталь 1", substring = true)
            node(LibraryTags.SAVE_NAME).performTextReplacement("Кронштейн")
            node(LibraryTags.SAVE_CONFIRM).performClick()
            compose.waitForIdle()
            node(LibraryTags.NOTICE).assertExists()
            val template = deps.partTemplates.list().single()
            assertEquals("Кронштейн", template.name)
            assertEquals(BigDecimal("2"), assertNotNull(template.part.material as? Material.Sheet).thicknessMm)

            // Another calculation: the blank first part goes, the library's part comes in, the welding of the product is added by hand.
            node(NAV_CALCULATIONS_TAG).performClick()
            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            type(EditorTags.NAME, "Кронштейн с приваркой")
            click("part/0/delete")
            click(LibraryTags.ADD_FROM_LIBRARY)
            typeInDialog(LibraryTags.PICK_SEARCH, "КРОН")
            compose.waitForIdle()
            // The whole row is one button, so its texts are looked up in the tree before merging.
            compose.onNodeWithTag("${LibraryTags.pickRow(0)}/name", useUnmergedTree = true).assertTextEquals("Кронштейн")
            compose.onNodeWithTag("${LibraryTags.pickRow(0)}/summary", useUnmergedTree = true).assertTextEquals("Лист Ст3 2 мм, 1000×1000")
            node(LibraryTags.pickRow(0)).performClick()
            compose.waitForIdle()
            node("part/0/mass").assertTextEquals("16 кг")
            click("product/op/WELDING/check")
            choose("product/op/WELDING/group", "Сталь")
            choose("product/op/WELDING/thickness", "2")
            type("product/op/WELDING/seam-length", "40")
            compose.waitForIdle()

            node(EditorTags.CALCULATE).performClick()
            compose.waitForIdle()

            node(CalculationTags.CARD_NAME).assertTextEquals("Кронштейн с приваркой")
            node(CalculationTags.CARD_UNIT_PRICE).assertTextContains("11 340,00", substring = true)
            val saved = deps.calculations.list().first { it.name == "Кронштейн с приваркой" }
            assertEquals(0, BigDecimal("11340.00").compareTo(assertNotNull(saved.unitPrice).amount))
            assertEquals(goldenInput().parts.single().copy(name = "Деталь 1"), assertNotNull(deps.calculations.get(saved.id)).input.parts.single())
        }
    }

    @Test
    fun `a name the library already has asks Заменить, Назад returns to the name, and Заменить replaces the part`() {
        deps().use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            fillGoldenPart()
            saveToLibrary("Кронштейн")
            type("part/0/length", "1200")

            click(LibraryTags.toLibrary("part/0"))
            typeInDialog(LibraryTags.SAVE_NAME, "кронштейн")
            node(LibraryTags.SAVE_CONFIRM).performClick()
            compose.waitForIdle()

            node(LibraryTags.REPLACE_QUESTION).assertTextContains("«Кронштейн» уже есть в библиотеке", substring = true)
            assertEquals(BigDecimal("1000"), lengthOf(deps.partTemplates.list().single().part), "nothing is replaced before the answer")

            node(LibraryTags.REPLACE_BACK).performClick()
            compose.waitForIdle()
            node(LibraryTags.SAVE_NAME).assertTextContains("кронштейн", substring = true)
            node(LibraryTags.SAVE_CONFIRM).performClick()
            compose.waitForIdle()
            node(LibraryTags.REPLACE_CONFIRM).performClick()
            compose.waitForIdle()

            val template = deps.partTemplates.list().single()
            assertEquals("Кронштейн", template.name)
            assertEquals(BigDecimal("1200"), lengthOf(template.part))
            node(LibraryTags.NOTICE).assertExists()
            compose.onNodeWithText("Деталь «Кронштейн» в библиотеке заменена").assertExists()
            compose.onAllNodesWithTag(LibraryTags.REPLACE_QUESTION).assertCountEquals(0)
        }
    }

    @Test
    fun `an empty library says so in the picker and on its screen`() {
        deps().use { deps ->
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(CalculationTags.NEW).performClick()
            node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
            click(LibraryTags.ADD_FROM_LIBRARY)
            node(LibraryTags.PICK_EMPTY).assertExists()
            node(LibraryTags.PICK_CLOSE).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag(LibraryTags.PICK_EMPTY).assertCountEquals(0)

            node(NAV_LIBRARY_TAG).performClick()
            compose.waitForIdle()
            node(LibraryTags.EMPTY).assertExists()
        }
    }

    @Test
    fun `Детали lists the parts, searches, renames, and deletes after a confirmation without a password`() {
        deps().use { deps ->
            val bracket = goldenInput().parts.single()
            deps.partTemplates.save("Кронштейн", bracket)
            deps.partTemplates.save("Стойка", Part("Стойка", Material.RoundTube("Ст3", BigDecimal("57"), BigDecimal("3"), BigDecimal("1000")), operations = emptyList()))
            compose.setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }

            node(NAV_LIBRARY_TAG).performClick()
            compose.waitForIdle()
            node("${LibraryTags.row(0)}/name").assertTextEquals("Кронштейн")
            node("${LibraryTags.row(0)}/summary").assertTextEquals("Лист Ст3 2 мм, 1000×1000")
            node("${LibraryTags.row(1)}/name").assertTextEquals("Стойка")
            node("${LibraryTags.row(1)}/summary").assertTextEquals("Труба круглая Ст3 57×3 мм, длина 1000 мм")

            node(LibraryTags.SEARCH).performTextReplacement("СТОЙ")
            compose.waitForIdle()
            node("${LibraryTags.row(0)}/name").assertTextEquals("Стойка")
            compose.onAllNodesWithTag(LibraryTags.row(1)).assertCountEquals(0)
            node(LibraryTags.SEARCH).performTextReplacement("болт")
            compose.waitForIdle()
            node(LibraryTags.NO_MATCHES).assertExists()
            node(LibraryTags.SEARCH).performTextReplacement("")
            compose.waitForIdle()

            // A name another part has is refused under the field; a free one renames.
            node("${LibraryTags.row(0)}/rename").performClick()
            node(LibraryTags.RENAME_FIELD).performTextReplacement("стойка")
            node(LibraryTags.RENAME_CONFIRM).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(nameTakenMessage("стойка")).assertExists()
            node(LibraryTags.RENAME_FIELD).performTextReplacement("Опора")
            node(LibraryTags.RENAME_CONFIRM).performClick()
            compose.waitForIdle()
            node("${LibraryTags.row(0)}/name").assertTextEquals("Опора")
            node("${LibraryTags.row(1)}/name").assertTextEquals("Стойка")
            assertEquals(listOf("Опора", "Стойка"), deps.partTemplates.list().map { it.name })

            node("${LibraryTags.row(0)}/delete").performClick()
            node(LibraryTags.DELETE_CANCEL).performClick()
            compose.waitForIdle()
            assertEquals(2, deps.partTemplates.list().size)
            node("${LibraryTags.row(0)}/delete").performClick()
            node(LibraryTags.DELETE_CONFIRM).performClick()
            compose.waitForIdle()
            node("${LibraryTags.row(0)}/name").assertTextEquals("Стойка")
            assertEquals(listOf("Стойка"), deps.partTemplates.list().map { it.name })

            node("${LibraryTags.row(0)}/delete").performClick()
            node(LibraryTags.DELETE_CONFIRM).performClick()
            compose.waitForIdle()
            node(LibraryTags.EMPTY).assertExists()
        }
    }

    private fun lengthOf(part: Part): BigDecimal =
        ((part.material as Material.Sheet).shape as SheetShape.Rectangle).lengthMm
}
