package io.github.laalka.slav.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.laalka.slav.app.theme.DarkColors
import io.github.laalka.slav.app.theme.DarkExtendedColors
import io.github.laalka.slav.app.theme.ExtendedColors
import io.github.laalka.slav.app.theme.LightColors
import io.github.laalka.slav.app.theme.LightExtendedColors
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.app.ui.RailIcons
import io.github.laalka.slav.app.ui.RailItem
import io.github.laalka.slav.app.ui.SlavNavigationRail
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RAIL = "rail"
private val ITEM_TAGS = listOf("item/calculations", "item/library", "item/settings")

/** A dp of a test is a pixel. */
private val Dp.px: Int get() = value.toInt()

private fun over(color: Color, background: Color) = Color(
    red = color.red * color.alpha + background.red * (1 - color.alpha),
    green = color.green * color.alpha + background.green * (1 - color.alpha),
    blue = color.blue * color.alpha + background.blue * (1 - color.alpha),
)

/** The pixel at [x], [y] must be [expected] to within the rounding of 8-bit channels. */
private fun BufferedImage.assertPixel(x: Int, y: Int, expected: Color, what: String) {
    val rgb = getRGB(x, y)
    fun channel(shift: Int) = ((rgb ushr shift) and 0xFF) / 255f
    assertEquals(expected.red, channel(16), 0.012f, "$what: red at $x,$y is ${"%08X".format(rgb)}")
    assertEquals(expected.green, channel(8), 0.012f, "$what: green at $x,$y is ${"%08X".format(rgb)}")
    assertEquals(expected.blue, channel(0), 0.012f, "$what: blue at $x,$y is ${"%08X".format(rgb)}")
}

@OptIn(ExperimentalTestApi::class)
class NavigationRailUiTest {
    /** The rail as the program has it, with [selected] as the item that is lit; the dp of a test are pixels. */
    private fun DesktopComposeUiTest.rail(dark: Boolean, selected: Int = 0) {
        setContent {
            SlavTheme(darkTheme = dark) {
                var current by remember { mutableIntStateOf(selected) }
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SlavNavigationRail(Modifier.testTag(RAIL)) {
                        RailItem(current == 0, { current = 0 }, RailIcons.Calculations, "Расчёты", Modifier.testTag(ITEM_TAGS[0]))
                        RailItem(current == 1, { current = 1 }, RailIcons.Library, "Детали", Modifier.testTag(ITEM_TAGS[1]))
                        RailItem(current == 2, { current = 2 }, RailIcons.Settings, "Настройки", Modifier.testTag(ITEM_TAGS[2]))
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.picture(): BufferedImage = onRoot().captureToImage().toAwtImage()

    @Test
    fun `the rail is 88 dp wide, its items 72 dp, and the logo of 40 dp is on top of them`() = runDesktopComposeUiTest(width = 400, height = 600) {
        rail(dark = false)
        val rail = onNodeWithTag(RAIL).getBoundsInRoot()
        assertEquals(88.dp, rail.width)
        val logo = onNodeWithContentDescription("Slav").getBoundsInRoot()
        assertEquals(40.dp, logo.width)
        assertEquals(40.dp, logo.height)
        assertEquals(20.dp, logo.top, "the rail's padding above the logo")
        assertEquals(24.dp, logo.left, "centred in the rail")
        val items = ITEM_TAGS.map { onNodeWithTag(it).getBoundsInRoot() }
        items.forEach { assertEquals(72.dp, it.width) }
        // 8 dp between the items, 16 dp more (and the 8 dp gaps beside it) between the logo and the first.
        assertEquals(logo.bottom + 32.dp, items[0].top)
        assertEquals(items[0].bottom + 8.dp, items[1].top)
        assertEquals(items[1].bottom + 8.dp, items[2].top)
    }

    @Test
    fun `the labels are on one line inside their item`() = runDesktopComposeUiTest(width = 400, height = 600) {
        rail(dark = false)
        listOf("Расчёты", "Детали", "Настройки").forEachIndexed { index, label ->
            // The item merges its label into itself; the label's own node is in the unmerged tree.
            val text = onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
            val item = onNodeWithTag(ITEM_TAGS[index]).getBoundsInRoot()
            assertTrue(text.width <= item.width, "«$label» is ${text.width} wide in an item of ${item.width}")
            // A line of 12 sp is 16 dp or a little less; a second line would double it.
            assertTrue(text.height < 20.dp, "«$label» is ${text.height} tall: it wrapped")
        }
    }

    @Test
    fun `a click selects the item and unselects the others`() = runDesktopComposeUiTest(width = 400, height = 600) {
        rail(dark = false)
        onNodeWithTag(ITEM_TAGS[0]).assertIsSelected()
        onNodeWithTag(ITEM_TAGS[2]).assertIsNotSelected()
        onNodeWithTag(ITEM_TAGS[2]).performClick()
        waitForIdle()
        onNodeWithTag(ITEM_TAGS[2]).assertIsSelected()
        onNodeWithTag(ITEM_TAGS[0]).assertIsNotSelected()
        onNodeWithTag(ITEM_TAGS[1]).assertIsNotSelected()
    }

    private fun DesktopComposeUiTest.checkColours(extended: ExtendedColors, page: Color) {
        val image = picture()
        val items = ITEM_TAGS.map { onNodeWithTag(it).getBoundsInRoot() }
        // The rail's own colour, in the padding at its left edge and right under the last item.
        image.assertPixel(2, 300, extended.rail, "rail")
        image.assertPixel(items[2].left.px + 2, items[2].bottom.px + 40, extended.rail, "rail below the items")
        // The page beside it.
        image.assertPixel(250, 300, page, "page")
        // The border on the right edge: only the dark rail has one.
        image.assertPixel(87, 300, if (extended.railBorder.alpha > 0f) extended.railBorder else extended.rail, "right edge of the rail")
        // The selected item is the accent at 16 % over the rail, the others are the rail itself.
        val tinted = over(extended.railSelected, extended.rail)
        items.forEachIndexed { index, bounds ->
            image.assertPixel(bounds.left.px + 4, bounds.top.px + 30, if (index == 0) tinted else extended.rail, "item $index")
        }
        // The logo is the orange plate.
        val logo = onNodeWithContentDescription("Slav").getBoundsInRoot()
        image.assertPixel(logo.left.px + 2, logo.top.px + 20, Color(0xFFEA580C), "logo plate")
    }

    @Test
    fun `the light theme has a dark rail with the first item tinted and no border`() = runDesktopComposeUiTest(width = 400, height = 600) {
        rail(dark = false)
        checkColours(LightExtendedColors, LightColors.background)
    }

    @Test
    fun `the dark theme has a darker rail with a border on its right`() = runDesktopComposeUiTest(width = 400, height = 600) {
        rail(dark = true)
        checkColours(DarkExtendedColors, DarkColors.background)
    }
}
