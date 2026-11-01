package io.github.laalka.slav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import io.github.laalka.slav.app.editor.CalcErrors
import io.github.laalka.slav.app.editor.MissingCell
import io.github.laalka.slav.app.editor.ReadableError
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.theme.DarkColors
import io.github.laalka.slav.app.theme.DarkExtendedColors
import io.github.laalka.slav.app.theme.ExtendedColors
import io.github.laalka.slav.app.theme.LightColors
import io.github.laalka.slav.app.theme.LightExtendedColors
import io.github.laalka.slav.app.theme.SlavTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The components the screens of the redesign are made of, as pixels and as layout, in both themes: the grid of
 * fields, the filter pill, the success strip, the card with a dashed line, the title bar, the dialog and the small
 * buttons. The dp of a test are pixels, so a height or a thickness is counted on the picture and compared with the number
 * of the design.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenComponentsUiTest {
    private class Palette(val dark: Boolean) {
        val scheme = if (dark) DarkColors else LightColors
        val extended: ExtendedColors = if (dark) DarkExtendedColors else LightExtendedColors
    }

    private val palettes = listOf(Palette(dark = false), Palette(dark = true))

    /** [content] on the surface of the theme with 16 dp round it, in a window of 800 × 600. */
    private fun inTheme(palette: Palette, content: @Composable () -> Unit, body: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 800, height = 600) {
            setContent { SlavTheme(darkTheme = palette.dark) { Box(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)) { content() } } }
            waitForIdle()
            body()
        }

    private fun near(a: Color, b: Color, tolerance: Float = 0.03f): Boolean =
        abs(a.red - b.red) <= tolerance && abs(a.green - b.green) <= tolerance && abs(a.blue - b.blue) <= tolerance

    private fun ImageBitmap.pixel(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun ImageBitmap.assertPixel(x: Int, y: Int, expected: Color, what: String) {
        val actual = pixel(x, y)
        assertTrue(near(actual, expected), "$what: the pixel ($x, $y) is $actual, expected $expected")
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    // ---- the grid of fields ----

    /** A grid in 768 dp (the window less the margin) whose cells are 40 dp high boxes tagged "cell<index>", the one at [index] spanning `spans[index]`. */
    private fun grid(vararg spans: Int, minCell: Int = 160): @Composable () -> Unit = {
        Box(Modifier.width(768.dp)) {
            FieldGrid(minCell = minCell.dp) {
                spans.forEachIndexed { index, span -> Box(Modifier.gridSpan(span).height(40.dp).testTag("cell$index")) }
            }
        }
    }

    private fun DesktopComposeUiTest.cell(index: Int) = onNodeWithTag("cell$index").getBoundsInRoot()

    @Test
    fun `a grid puts as many equal cells in a row as fit, 4 of at least 160 dp in 768 dp, and the fifth starts a new row as wide as the others`() {
        for (palette in palettes) {
            inTheme(palette, grid(1, 1, 1, 1, 1)) {
                // (768 − 3 × 12) / 4 = 183.
                for (index in 0..4) assertEquals(183.dp, cell(index).width, "cell $index, dark ${palette.dark}")
                assertEquals(listOf(0.dp, 195.dp, 390.dp, 585.dp), (0..3).map { cell(it).left - cell(0).left })
                assertEquals(cell(0).top, cell(3).top, "the first four are one row")
                assertEquals(cell(0).top + 52.dp, cell(4).top, "the fifth is a row of 40 dp and a gap of 12 under")
                assertEquals(cell(0).left, cell(4).left)
            }
        }
    }

    @Test
    fun `a grid with fewer cells than columns gives them all the room, as auto-fit does`() {
        inTheme(palettes.first(), grid(1, 1)) {
            assertEquals(378.dp, cell(0).width, "(768 − 12) / 2")
            assertEquals(378.dp, cell(1).width)
            assertEquals(cell(0).top, cell(1).top)
        }
    }

    @Test
    fun `a cell with a span takes that many cells, and one that does not fit in the rest of the row starts the next`() {
        // Cells of 100 dp or more: 6 columns of (768 − 5 × 12) / 6 = 118; a name takes two of them (2 × 118 + 12 = 248).
        inTheme(palettes.first(), grid(2, 2, 1, 1, minCell = 100)) {
            assertEquals(248.dp, cell(0).width)
            assertEquals(248.dp, cell(1).width)
            assertEquals(118.dp, cell(2).width)
            assertEquals(118.dp, cell(3).width)
            assertTrue((1..3).all { cell(it).top == cell(0).top }, "four fields in one row")
        }
        // Cells of 160 dp or more: 4 columns, so 2 + 2 fill the first row and the two numbers are the second.
        inTheme(palettes.first(), grid(2, 2, 1, 1)) {
            assertEquals(378.dp, cell(0).width, "2 × 183 + 12")
            assertEquals(cell(0).top, cell(1).top)
            assertTrue(cell(2).top > cell(0).top, "the numbers wrapped")
            assertEquals(cell(2).top, cell(3).top)
        }
    }

    // ---- the filter pill ----

    @Test
    fun `a filter pill is 36 dp high and fully rounded, the chosen one on the inverse surface and the others outlined, and it says which it is`() {
        for (palette in palettes) {
            val content: @Composable () -> Unit = {
                Row {
                    FilterPill("Все", selected = true, onClick = {}, modifier = Modifier.testTag("on"))
                    FilterPill("Черновик", selected = false, onClick = {}, modifier = Modifier.testTag("off"))
                }
            }
            inTheme(palette, content) {
                assertEquals(36.dp, onNodeWithTag("on").getBoundsInRoot().height, "dark ${palette.dark}")
                assertEquals(36.dp, onNodeWithTag("off").getBoundsInRoot().height)
                onNodeWithTag("on").assertIsSelected()
                onNodeWithTag("off").assertIsNotSelected()
                val on = onNodeWithTag("on").captureToImage()
                on.assertPixel(10, 18, palette.scheme.inverseSurface, "the chosen pill is filled with the inverse surface, dark ${palette.dark}")
                val off = onNodeWithTag("off").captureToImage()
                off.assertPixel(10, 18, palette.extended.buttonFill.compositeOver(palette.scheme.surface), "the other pill has the fill of a button")
                assertTrue(near(off.pixel(0, 18), palette.scheme.outline, 0.08f), "and an outline: ${off.pixel(0, 18)}")
                // The corners are cut off: a pill is a stadium, so the corner pixel is the page.
                off.assertPixel(0, 0, palette.scheme.surface, "the corner of a fully rounded pill is not the pill")
            }
        }
    }

    // ---- the success strip ----

    @Test
    fun `the success strip is one node with the text in the success colours on a strip of the success fill`() {
        for (palette in palettes) {
            inTheme(palette, { SuccessStrip("Добавлено: 4 марки", Modifier.width(400.dp).testTag("strip")) }) {
                onNodeWithTag("strip").assertTextEquals("Добавлено: 4 марки")
                val image = onNodeWithTag("strip").captureToImage()
                image.assertPixel(200, 2, palette.extended.success.compositeOver(palette.scheme.surface), "the strip, dark ${palette.dark}")
                val layout = onNodeWithText("Добавлено: 4 марки", useUnmergedTree = true).textLayout()
                assertEquals(palette.extended.onSuccess, layout.layoutInput.style.color)
                assertEquals(13.sp, layout.layoutInput.style.fontSize)
            }
        }
    }

    // ---- the card ----

    /** The columns of the row [y] of [image] between [from] and [until] whose pixel is [color]. */
    private fun ImageBitmap.columnsOfColor(y: Int, from: Int, until: Int, color: Color): List<Int> = (from until until).filter { near(pixel(it, y), color) }

    @Test
    fun `a card has a 1 dp line of the theme round it, and a dashed card has dashes of its colour in place of it`() {
        for (palette in palettes) {
            val content: @Composable () -> Unit = {
                Column {
                    CardSurface(Modifier.width(300.dp).testTag("solid")) { Box(Modifier.height(40.dp)) }
                    CardSurface(Modifier.width(300.dp).padding(top = 16.dp).testTag("dashed"), dashed = Color(0xFFFF00FF)) { Box(Modifier.height(40.dp)) }
                }
            }
            inTheme(palette, content) {
                // The straight part of the top edge, clear of the 14 dp corners.
                val solid = onNodeWithTag("solid").captureToImage()
                assertEquals(260, solid.columnsOfColor(0, 20, 280, palette.scheme.outlineVariant).size, "a solid line, dark ${palette.dark}")

                val dashed = onNodeWithTag("dashed").captureToImage()
                val drawn = dashed.columnsOfColor(0, 20, 280, Color(0xFFFF00FF))
                assertTrue(drawn.size in 100..200, "the dashes of the line are over part of the edge: ${drawn.size} of 260 columns")
                assertTrue(drawn.zipWithNext().any { (a, b) -> b - a > 1 }, "and there are gaps between them")
                assertEquals(0, dashed.columnsOfColor(0, 20, 280, palette.scheme.outlineVariant).size, "no solid line of the theme under them")
            }
        }
    }

    // ---- the title bar ----

    @Test
    fun `the title bar is 81 dp high with a 44 dp way back and a line of the header colour under it`() {
        for (palette in palettes) {
            val content: @Composable () -> Unit = {
                TitleBar(onBack = {}, backTag = "back", modifier = Modifier.width(700.dp).testTag("bar")) { Text("Новый расчёт") }
            }
            inTheme(palette, content) {
                assertEquals(81.dp, onNodeWithTag("bar").getBoundsInRoot().height, "18 + 44 + 18 and the line, dark ${palette.dark}")
                val back = onNodeWithTag("back").getBoundsInRoot()
                assertEquals(44.dp, back.width)
                assertEquals(44.dp, back.height)
                val image = onNodeWithTag("bar").captureToImage()
                image.assertPixel(350, 80, palette.extended.headerBorder, "the line under the bar")
                image.assertPixel(350, 79, palette.scheme.surface, "which is 1 dp thick")
            }
        }
    }

    // ---- the dialog ----

    @Test
    fun `a dialog has its title in 18 sp semibold, its text in 14 sp and the buttons of the screens in its foot`() {
        for (palette in palettes) {
            val content: @Composable () -> Unit = {
                SlavDialog(
                    title = "Удалить расчёт?",
                    onDismissRequest = {},
                    confirmButton = { DestructiveButton({}, Modifier.testTag("confirm")) { Text("Удалить") } },
                    dismissButton = { SecondaryButton({}, Modifier.testTag("cancel")) { Text("Отмена") } },
                ) { Text("Расчёт будет удалён.") }
            }
            inTheme(palette, content) {
                val title = onNodeWithText("Удалить расчёт?").textLayout().layoutInput.style
                assertEquals(18.sp, title.fontSize, "the title, dark ${palette.dark}")
                assertEquals(FontWeight.SemiBold, title.fontWeight)
                assertEquals(14.sp, onNodeWithText("Расчёт будет удалён.").textLayout().layoutInput.style.fontSize, "the text")
                assertEquals(44.dp, onNodeWithTag("confirm").getBoundsInRoot().height)
                assertEquals(44.dp, onNodeWithTag("cancel").getBoundsInRoot().height)
                assertTrue(onNodeWithTag("cancel").getBoundsInRoot().right <= onNodeWithTag("confirm").getBoundsInRoot().left, "the question is left of the answer")
            }
        }
    }

    // ---- small buttons ----

    @Test
    fun `an icon action is 36 dp square and a compact text button 36 dp high with 13 sp text`() {
        for (palette in palettes) {
            val content: @Composable () -> Unit = {
                Row {
                    IconAction(LineIcons.Trash, "Удалить деталь", {}, Modifier.testTag("icon"))
                    AccentTextButton({}, Modifier.testTag("compact"), compact = true) { Text("Копировать") }
                    AccentTextButton({}, Modifier.testTag("regular")) { Text("Отмена") }
                }
            }
            inTheme(palette, content) {
                val icon = onNodeWithTag("icon").getBoundsInRoot()
                assertEquals(36.dp, icon.width, "dark ${palette.dark}")
                assertEquals(36.dp, icon.height)
                assertEquals(36.dp, onNodeWithTag("compact").getBoundsInRoot().height)
                assertEquals(44.dp, onNodeWithTag("regular").getBoundsInRoot().height)
                assertEquals(13.sp, onNodeWithText("Копировать", useUnmergedTree = true).textLayout().layoutInput.style.fontSize)
                assertEquals(14.sp, onNodeWithText("Отмена", useUnmergedTree = true).textLayout().layoutInput.style.fontSize)
            }
        }
    }

    // ---- the missing prices ----

    @Test
    fun `the missing prices are a table of four columns where there is room, and two lines for each price in a narrow place`() {
        val errors = listOf(ReadableError("Не задана цена", MissingCell("Цены материала", "Ст3", "8", SettingsTab.MATERIAL_PRICES)))
        inTheme(palettes.first(), { Box(Modifier.width(300.dp)) { CalcErrors(errors, hint = null) } }) {
            onAllNodesWithText("Таблица").assertCountEquals(0)
            onNodeWithText("Цены материала: Ст3, 8").assertExists()
            onNodeWithText("Настройки → Цены материала").assertExists()
        }
        inTheme(palettes.first(), { Box(Modifier.width(700.dp)) { CalcErrors(errors, hint = null) } }) {
            onNodeWithText("Таблица").assertExists()
            onNodeWithText("Где заполнить").assertExists()
            onAllNodesWithText("Цены материала: Ст3, 8").assertCountEquals(0)
            onNodeWithText("Ст3").assertExists()
        }
    }
}
