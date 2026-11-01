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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.laalka.slav.app.theme.DarkColors
import io.github.laalka.slav.app.theme.DarkExtendedColors
import io.github.laalka.slav.app.theme.ExtendedColors
import io.github.laalka.slav.app.theme.LightColors
import io.github.laalka.slav.app.theme.LightExtendedColors
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.CalculationStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared components of the design as pixels, in both themes: the dp of a test are pixels, so a thickness, a
 * height or an offset is counted on the picture of the component and compared with the number of the design.
 */
@OptIn(ExperimentalTestApi::class)
class ComponentsUiTest {
    private class Palette(val dark: Boolean) {
        val scheme = if (dark) DarkColors else LightColors
        val extended: ExtendedColors = if (dark) DarkExtendedColors else LightExtendedColors
        val page: Color get() = scheme.surface
    }

    private val palettes = listOf(Palette(dark = false), Palette(dark = true))

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

    private fun SemanticsNodeInteraction.image(): ImageBitmap = captureToImage()

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private fun DesktopComposeUiTest.labelOf(text: String) = onNode(hasText(text), useUnmergedTree = true)

    // ---- fields ----

    /** The rows of [image] at column [x] whose pixel has the colour [color]: where the lines of a box cross the column. */
    private fun ImageBitmap.rowsOfColor(x: Int, color: Color): List<Int> = (0 until height).filter { near(pixel(x, it), color) }

    private fun fieldContent(value: String = "", label: String? = "Название", error: String? = null, numeric: Boolean = false): @Composable () -> Unit = {
        InputField(value, {}, Modifier.width(300.dp).testTag("field"), label = label, error = error, numeric = numeric)
    }

    @Test
    fun `a field has its label above a box 44 dp high with a 1 dp outline and the fill of a field`() {
        for (palette in palettes) {
            inTheme(palette, fieldContent()) {
                val node = onNodeWithTag("field")
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertEquals(22f + 44f, bounds.height, "the label (16 dp) and its gap (6 dp) over the box (44 dp), dark ${palette.dark}")
                val image = node.image()
                // The top and the bottom lines of the box cross a column that is clear of the label.
                val lines = image.rowsOfColor(x = 150, palette.scheme.outline)
                assertEquals(listOf(22, 65), lines, "the box starts under the label and is 44 dp high, dark ${palette.dark}")
                image.assertPixel(0, 44, palette.scheme.outline, "the left line is the outline of the theme")
                image.assertPixel(1, 44, palette.scheme.surfaceContainerLowest, "and it is 1 dp thick, dark ${palette.dark}")
                image.assertPixel(150, 44, palette.scheme.surfaceContainerLowest, "the box is filled with the fill of a field")
                val label = labelOf("Название")
                assertTrue(label.fetchSemanticsNode().boundsInRoot.bottom <= bounds.top + 22f + 0.5f, "the label is above the box")
                assertEquals(palette.scheme.onSurfaceVariant, label.textLayout().layoutInput.style.color, "the label is in the secondary colour")
                assertEquals(12.sp, label.textLayout().layoutInput.style.fontSize, "the label is 12 sp")
            }
        }
    }

    @Test
    fun `a focused field has a 2 dp outline in the accent`() {
        for (palette in palettes) {
            inTheme(palette, fieldContent()) {
                onNodeWithTag("field").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                val image = onNodeWithTag("field").image()
                image.assertPixel(0, 44, palette.scheme.primary, "the outline of a focused field, dark ${palette.dark}")
                image.assertPixel(1, 44, palette.scheme.primary, "is 2 dp thick, dark ${palette.dark}")
                image.assertPixel(3, 44, palette.scheme.surfaceContainerLowest, "and no more, dark ${palette.dark}")
            }
        }
    }

    @Test
    fun `a field in error has a 2 dp error outline focused or not, a tinted label and its message under the box`() {
        for (palette in palettes) {
            inTheme(palette, fieldContent(error = "Обязательное поле")) {
                val node = onNodeWithTag("field")
                assertEquals(22f + 44f + 6f + 16f, node.fetchSemanticsNode().boundsInRoot.height, "the message stands 6 dp under the box")
                for (focused in listOf(false, true)) {
                    if (focused) {
                        node.performSemanticsAction(SemanticsActions.RequestFocus)
                        waitForIdle()
                    }
                    val image = node.image()
                    image.assertPixel(0, 44, palette.scheme.error, "the outline in error, focused $focused, dark ${palette.dark}")
                    image.assertPixel(1, 44, palette.scheme.error, "is 2 dp thick, focused $focused, dark ${palette.dark}")
                    image.assertPixel(3, 44, palette.scheme.surfaceContainerLowest, "and no more, focused $focused, dark ${palette.dark}")
                }
                val label = labelOf("Название").textLayout().layoutInput.style
                assertEquals(palette.extended.errorText, label.color, "the label is tinted with the error text colour")
                assertEquals(FontWeight.SemiBold, label.fontWeight, "and heavier")
                val message = labelOf("Обязательное поле")
                assertEquals(palette.extended.errorText, message.textLayout().layoutInput.style.color, "the message is in the error text colour")
                assertEquals(WarningWeight, message.textLayout().layoutInput.style.fontWeight)
                val box = node.fetchSemanticsNode().boundsInRoot
                assertTrue(message.fetchSemanticsNode().boundsInRoot.top >= box.top + 22f + 44f, "the message is under the box")
            }
        }
    }

    @Test
    fun `a field without a label is only its box, and a number is right-aligned in it`() {
        inTheme(palettes.first(), {
            Column {
                InputField("5", {}, Modifier.width(300.dp).testTag("number"), numeric = true)
                InputField("5", {}, Modifier.width(300.dp).testTag("text"))
            }
        }) {
            assertEquals(44f, onNodeWithTag("number").fetchSemanticsNode().boundsInRoot.height, "no label, no room for one")
            val fill = LightColors.surfaceContainerLowest
            fun inkRange(tag: String): IntRange {
                val image = onNodeWithTag(tag).image()
                val columns = (4 until image.width - 4).filter { x -> (8 until image.height - 8).any { y -> !near(image.pixel(x, y), fill, 0.2f) } }
                return columns.first()..columns.last()
            }
            val number = inkRange("number")
            val text = inkRange("text")
            assertTrue(number.first > 300 / 2, "the digit of a number stands in the right half, from ${number.first}")
            assertTrue(number.last in (300 - 16)..(300 - 8), "and ends 12 dp from the right edge, at ${number.last}")
            assertTrue(text.first in 8..16, "the same text in a field of text starts 12 dp from the left edge, at ${text.first}")
        }
    }

    // ---- segmented control ----

    @Test
    fun `the chosen segment is raised off the track, the others are the track, and every segment is 36 dp high`() {
        for (palette in palettes) {
            inTheme(palette, { SegmentedChoice(listOf("Один", "Два", "Три"), selectedIndex = 1, onSelect = {}, testTagOf = { "segment$it" }) }) {
                val chosen = onNodeWithTag("segment1")
                val idle = onNodeWithTag("segment0")
                assertEquals(36f, chosen.fetchSemanticsNode().boundsInRoot.height, "a segment is 36 dp high")
                assertEquals(36f, idle.fetchSemanticsNode().boundsInRoot.height)
                chosen.image().assertPixel(6, 18, palette.scheme.surfaceBright, "the chosen segment, dark ${palette.dark}")
                idle.image().assertPixel(6, 18, palette.scheme.surfaceVariant, "a segment not chosen shows the track, dark ${palette.dark}")
                assertEquals(palette.scheme.onSurface, onNodeWithText("Два", useUnmergedTree = true).textLayout().layoutInput.style.color)
                assertEquals(palette.scheme.onSurfaceVariant, onNodeWithText("Один", useUnmergedTree = true).textLayout().layoutInput.style.color)
                assertEquals(FontWeight.SemiBold, onNodeWithText("Два", useUnmergedTree = true).textLayout().layoutInput.style.fontWeight)
            }
        }
    }

    // ---- buttons ----

    @Composable
    private fun Buttons() {
        Column {
            PrimaryButton({}, Modifier.testTag("primary")) { Text("Основная") }
            PrimaryButton({}, Modifier.testTag("disabled"), enabled = false) { Text("Недоступная") }
            SecondaryButton({}, Modifier.testTag("secondary")) { Text("Вторая") }
            DestructiveButton({}, Modifier.testTag("destructive")) { Text("Удалить") }
            DashedButton({}, Modifier.width(240.dp).testTag("dashed")) { Text("Случайные данные") }
            AccentTextButton({}, Modifier.testTag("text")) { Text("Текстовая") }
        }
    }

    @Test
    fun `every button is 44 dp high with a 10 dp radius and 14 sp semibold text`() {
        for (palette in palettes) {
            inTheme(palette, { Buttons() }) {
                listOf("primary", "disabled", "secondary", "destructive", "dashed", "text").forEach { tag ->
                    assertEquals(44f, onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height, "the height of the «$tag» button")
                }
                val style = onNodeWithText("Основная", useUnmergedTree = true).textLayout().layoutInput.style
                assertEquals(14.sp, style.fontSize)
                assertEquals(FontWeight.SemiBold, style.fontWeight)
                // The corner of a 10 dp radius leaves the pixel at the very corner outside the button, the page shows there.
                onNodeWithTag("primary").image().assertPixel(0, 0, palette.page, "the corner of the primary button is rounded, dark ${palette.dark}")
                onNodeWithTag("primary").image().assertPixel(10, 0, palette.scheme.primary, "and the edge past the radius is the fill")
            }
        }
    }

    @Test
    fun `the primary button is the accent fill, the secondary one is outlined and the destructive one is tinted`() {
        for (palette in palettes) {
            inTheme(palette, { Buttons() }) {
                val page = palette.page
                onNodeWithTag("primary").image().assertPixel(6, 22, palette.scheme.primary, "primary, dark ${palette.dark}")
                val disabled = palette.scheme.primary.copy(alpha = 0.5f).compositeOver(page)
                onNodeWithTag("disabled").image().assertPixel(6, 22, disabled, "a disabled primary is half of it, dark ${palette.dark}")
                val secondary = onNodeWithTag("secondary").image()
                secondary.assertPixel(0, 22, palette.scheme.outline.compositeOver(page), "the line of the secondary button is the outline colour, dark ${palette.dark}")
                secondary.assertPixel(6, 22, palette.extended.buttonFill.compositeOver(page), "inside it is the fill of a button")
                val container = palette.scheme.errorContainer.compositeOver(page)
                val destructive = onNodeWithTag("destructive").image()
                destructive.assertPixel(6, 22, container, "the destructive button is the error container, dark ${palette.dark}")
                destructive.assertPixel(0, 22, palette.extended.errorBorder.compositeOver(container), "with the softer error line round it")
                assertEquals(palette.extended.errorText, onNodeWithText("Удалить", useUnmergedTree = true).textLayout().layoutInput.style.color)
                assertEquals(palette.extended.accentText, onNodeWithText("Текстовая", useUnmergedTree = true).textLayout().layoutInput.style.color)
            }
        }
    }

    @Test
    fun `the dashed button has dashes on its top edge where the secondary one has a solid line`() {
        for (palette in palettes) {
            inTheme(palette, { Buttons() }) {
                val outline = palette.scheme.outline.compositeOver(palette.page)
                fun runsOfOutline(tag: String): Int {
                    val image = onNodeWithTag(tag).image()
                    val onLine = (14 until image.width - 14).map { near(image.pixel(it, 0), outline) }
                    return onLine.zipWithNext().count { (a, b) -> !a && b } + if (onLine.first()) 1 else 0
                }
                assertTrue(runsOfOutline("dashed") >= 5, "the top edge of the dashed button is cut into dashes, dark ${palette.dark}")
                assertEquals(1, runsOfOutline("secondary"), "the top edge of the secondary button is one line, dark ${palette.dark}")
            }
        }
    }

    // ---- cards, chips, alerts ----

    @Test
    fun `a card is the surface with a 1 dp line, a 14 dp radius, 20 dp of padding, a 15 sp title and a 13 sp subtitle`() {
        for (palette in palettes) {
            inTheme(palette, { SectionCard("Заголовок", Modifier.testTag("card"), subtitle = "Пояснение") { Text("Содержимое") } }) {
                val card = onNodeWithTag("card")
                val bounds = card.fetchSemanticsNode().boundsInRoot
                val image = card.image()
                image.assertPixel(0, image.height / 2, palette.scheme.outlineVariant, "the line of the card, dark ${palette.dark}")
                image.assertPixel(2, image.height / 2, palette.scheme.surface, "is 1 dp thick, dark ${palette.dark}")
                image.assertPixel(0, 0, palette.page, "the corner of a card is rounded, dark ${palette.dark}")
                val title = onNodeWithText("Заголовок", useUnmergedTree = true)
                assertEquals(bounds.left + 20f, title.fetchSemanticsNode().boundsInRoot.left, "20 dp of room before the content")
                assertEquals(bounds.top + 20f, title.fetchSemanticsNode().boundsInRoot.top, "and over it")
                assertEquals(15.sp, title.textLayout().layoutInput.style.fontSize)
                assertEquals(FontWeight.SemiBold, title.textLayout().layoutInput.style.fontWeight)
                val subtitle = onNodeWithText("Пояснение", useUnmergedTree = true)
                assertEquals(13.sp, subtitle.textLayout().layoutInput.style.fontSize)
                assertEquals(palette.scheme.onSurfaceVariant, subtitle.textLayout().layoutInput.style.color)
                assertEquals(bounds.bottom - 20f, onNodeWithText("Содержимое", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom, 2f, "20 dp of room under it")
            }
        }
    }

    @Test
    fun `a status chip is 26 dp high, fully rounded, in the colours of its status`() {
        for (palette in palettes) {
            val colours = mapOf(
                CalculationStatus.DRAFT to palette.extended.statusDraft.container,
                CalculationStatus.CALCULATED to palette.extended.success,
                CalculationStatus.SENT to palette.extended.statusSent.container,
                CalculationStatus.IN_WORK to palette.extended.statusInWork.container,
                CalculationStatus.ARCHIVED to palette.extended.statusFinished.container,
            )
            inTheme(palette, { Column { CalculationStatus.entries.forEach { StatusChip(it, Modifier.testTag("chip/${it.name}")) } } }) {
                CalculationStatus.entries.forEach { status ->
                    val chip = onNodeWithTag("chip/${status.name}")
                    assertEquals(26f, chip.fetchSemanticsNode().boundsInRoot.height, "the height of the chip of $status")
                    val image = chip.image()
                    // Inside the round end, clear of the text: the container over the card.
                    image.assertPixel(5, 13, colours.getValue(status).compositeOver(palette.page), "the chip of $status, dark ${palette.dark}")
                    image.assertPixel(0, 0, palette.page, "the end of the chip of $status is round, dark ${palette.dark}")
                    val style = onNodeWithText(status.title, useUnmergedTree = true).textLayout().layoutInput.style
                    assertEquals(12.sp, style.fontSize)
                    assertEquals(FontWeight.SemiBold, style.fontWeight)
                }
            }
        }
    }

    @Test
    fun `an alert is the error container with a line, an icon and 13 sp text`() {
        for (palette in palettes) {
            inTheme(palette, { WarningPanel("Заполните поля", Modifier.width(300.dp).testTag("alert")) }) {
                val alert = onNodeWithTag("alert")
                val image = alert.image()
                val container = palette.scheme.errorContainer.compositeOver(palette.page)
                image.assertPixel(0, image.height / 2, palette.extended.errorBorder.compositeOver(container), "the line round the alert, dark ${palette.dark}")
                image.assertPixel(5, image.height / 2, container, "the fill of the alert")
                val text = onNodeWithText("Заполните поля", useUnmergedTree = true).textLayout().layoutInput.style
                assertEquals(13.sp, text.fontSize)
                assertEquals(palette.scheme.onErrorContainer, text.color)
                // The icon stands before the text, tinted with the error colour: some of its pixels are that colour.
                val iconPixels = (8 until 36).sumOf { x -> (0 until image.height).count { y -> near(image.pixel(x, y), palette.scheme.error, 0.08f) } }
                assertTrue(iconPixels > 20, "the icon of the alert is drawn in the error colour, dark ${palette.dark}")
            }
        }
    }

    // ---- tables ----

    @Test
    fun `a table header is 12 sp semibold capitals that a test still finds as they were written`() {
        for (palette in palettes) {
            inTheme(palette, { HeaderText("Плотность, кг/м³", Modifier.testTag("header")) }) {
                val layout = onNodeWithTag("header").textLayout()
                assertEquals("ПЛОТНОСТЬ, КГ/М³", layout.layoutInput.text.text)
                assertEquals(12.sp, layout.layoutInput.style.fontSize)
                assertEquals(FontWeight.SemiBold, layout.layoutInput.style.fontWeight)
                assertEquals(0.6.sp, layout.layoutInput.style.letterSpacing)
                assertEquals(palette.scheme.onSurfaceVariant, layout.layoutInput.style.color)
                onNodeWithText("Плотность, кг/м³").assertExists()
            }
        }
    }

    @Test
    fun `a table row is lit while the pointer is over it`() {
        for (palette in palettes) {
            inTheme(palette, { Box(Modifier.width(300.dp).height(40.dp).tableRow().testTag("row")) { Text("Строка") } }) {
                onNodeWithTag("row").image().assertPixel(150, 30, palette.page, "a row at rest, dark ${palette.dark}")
                onNodeWithTag("row").performMouseInput { moveTo(center) }
                waitForIdle()
                onNodeWithTag("row").image().assertPixel(150, 30, palette.scheme.surfaceVariant, "a row under the pointer, dark ${palette.dark}")
                onNodeWithTag("row").performMouseInput { moveTo(Offset(-50f, -50f)) }
                waitForIdle()
                onNodeWithTag("row").image().assertPixel(150, 30, palette.page, "the pointer left, dark ${palette.dark}")
            }
        }
    }

    @Test
    fun `the divider of a table is 1 dp of the colour of the borders of a card`() {
        for (palette in palettes) {
            inTheme(palette, { Column(Modifier.width(300.dp).testTag("table")) { Box(Modifier.height(10.dp)); TableDivider(); Box(Modifier.height(10.dp)) } }) {
                val image = onNodeWithTag("table").image()
                assertEquals(listOf(10), image.rowsOfColor(x = 150, palette.scheme.outlineVariant), "one line of one pixel, dark ${palette.dark}")
            }
        }
    }

    @Test
    fun `a control standing beside a labelled field is level with its box`() {
        inTheme(palettes.first(), {
            Row {
                InputField("", {}, Modifier.width(200.dp).testTag("field"), label = "Название")
                SecondaryButton({}, Modifier.besideField().testTag("button")) { Text("Добавить") }
            }
        }) {
            val box = onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
            val button = onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
            assertEquals(box.top + 22f, button.top, "the button starts where the box does")
            assertEquals(box.bottom, button.bottom, "and ends with it")
        }
    }
}
