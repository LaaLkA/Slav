package io.github.laalka.slav.app

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.quick.QuickTags
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the redesigned screens do with the width they are given: two columns or one, the cost beside the form or under it. */
@OptIn(ExperimentalTestApi::class)
class ScreenLayoutsUiTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** The program in a window [width] dp wide and 800 high (the dp of a test are pixels), with [body] on the navigator. */
    private fun inWindow(width: Int, body: DesktopComposeUiTest.(Deps, Navigator) -> Unit) = runDesktopComposeUiTest(width = width, height = 800) {
        Deps(AppPaths(temp.newFolder().toPath()), appVersion = "test", adminSeed = testAdminSeed()).use { deps ->
            lateinit var navigator: Navigator
            setContent {
                SlavTheme(darkTheme = false) {
                    navigator = rememberNavigator(deps)
                    App(deps, NoFilePicker, navigator)
                }
            }
            waitForIdle()
            body(deps, navigator)
        }
    }

    private fun DesktopComposeUiTest.open(navigator: Navigator, screen: Screen) {
        runOnIdle { navigator.navigate(screen) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getBoundsInRoot()

    /** Within a dp: the cells of a grid are cut to whole pixels. */
    private fun assertWithin(expected: Dp, actual: Dp, what: String) =
        assertTrue(abs((expected - actual).value) <= 1f, "$what: $expected expected, $actual found")

    // ---- the editors ----

    @Test
    fun `from a window of 1100 dp the full editor has the cost panel to the right of the form, 340 dp wide`() {
        inWindow(1100) { _, navigator ->
            open(navigator, Screen.Editor(null, CalculationKind.FULL))
            val quantity = bounds(EditorTags.QUANTITY)
            val calculate = bounds(EditorTags.CALCULATE)

            assertTrue(calculate.left >= quantity.right, "«Рассчитать» is not right of the form: $calculate, $quantity")
            assertTrue(calculate.bottom < 500.dp, "the panel is at the top of the page, not a bar at the bottom: $calculate")
            // 340 dp less the 20 dp of the card on both sides.
            assertEquals(300.dp, calculate.width, "the button fills the panel")
            assertTrue(abs((calculate.height - 48.dp).value) < 1f, "the button is 48 dp high: ${calculate.height}")
        }
    }

    @Test
    fun `in a window of 1099 dp the editor keeps the bar of the price under the form`() {
        inWindow(1099) { _, navigator ->
            open(navigator, Screen.Editor(null, CalculationKind.FULL))
            val quantity = bounds(EditorTags.QUANTITY)
            val calculate = bounds(EditorTags.CALCULATE)

            assertTrue(calculate.top > quantity.bottom, "«Рассчитать» is not under the form: $calculate, $quantity")
            assertTrue(calculate.top > 600.dp, "it is in the bar at the bottom of the window: $calculate")
            assertTrue(calculate.right > 1000.dp, "at the right end of the bar: $calculate")
        }
    }

    @Test
    fun `the product card has the name, the counterparty, the run size and the discount in one row, the name and the counterparty twice as wide`() {
        inWindow(1280) { _, navigator ->
            open(navigator, Screen.Editor(null, CalculationKind.FULL))
            val name = bounds(EditorTags.NAME)
            val counterparty = bounds(EditorTags.COUNTERPARTY)
            val quantity = bounds(EditorTags.QUANTITY)
            val discount = bounds(EditorTags.DISCOUNT)

            assertTrue(counterparty.top == name.top && quantity.top == name.top && discount.top == name.top, "one row: $name, $counterparty, $quantity, $discount")
            assertTrue(name.right <= counterparty.left && counterparty.right <= quantity.left && quantity.right <= discount.left, "in this order")
            // A name takes two cells of the grid and a number one: 2 × cell + gap.
            val twoCells = quantity.width * 2f + 12.dp
            assertWithin(twoCells, name.width, "the name is two cells")
            assertWithin(counterparty.width, name.width, "the name and the counterparty are as wide as each other")
        }
    }

    @Test
    fun `the simplified editor has the same cost panel and no field for a run size`() {
        inWindow(1100) { _, navigator ->
            open(navigator, Screen.Editor(null, CalculationKind.QUICK))
            val name = bounds(QuickTags.NAME)
            val calculate = bounds(EditorTags.CALCULATE)

            assertTrue(calculate.left >= name.right, "«Рассчитать» is right of the form: $calculate, $name")
            onAllNodesWithTag(EditorTags.QUANTITY).assertCountEquals(0)
            assertEquals(name.top, bounds(QuickTags.DISCOUNT).top, "the name and the discount are one row")
        }
    }

    @Test
    fun `the cost panel stays where it is while the form scrolls, and it shows the lines and the price of what is entered`() {
        inWindow(1280) { _, navigator ->
            open(navigator, Screen.Editor(null, CalculationKind.FULL))
            onAllNodesWithTag(EditorTags.SUMMARY_UNIT_PRICE).assertCountEquals(0)
            onNodeWithTag(EditorTags.MISSING).assertExists()
            onNodeWithTag(EditorTags.CALCULATE).assertIsNotEnabled()

            runOnIdle { assertIs<Page.Editor>(navigator.page.value).model.fillRandomly(Random(2)) }
            waitForIdle()
            val summary = assertNotNull(assertIs<Page.Editor>(navigator.page.value).model.state.value.summary)
            onNodeWithTag(EditorTags.SUMMARY_UNIT_PRICE).assertTextEquals(summary.unitPrice)
            onNodeWithTag(EditorTags.SUMMARY_TOTAL).assertTextEquals(checkNotNull(summary.total))
            assertTrue(summary.lines.size >= 2, "a filled editor has lines: ${summary.lines}")
            summary.lines.forEachIndexed { index, line -> onNodeWithTag(EditorTags.summaryLine(index)).assertTextEquals(line.amount) }
            onAllNodesWithTag(EditorTags.MISSING).assertCountEquals(0)

            val before = bounds(EditorTags.CALCULATE)
            onNodeWithTag(EditorTags.ADD_PART).performScrollTo()
            waitForIdle()
            assertEquals(before, bounds(EditorTags.CALCULATE), "the panel did not move with the form")
        }
    }

    // ---- the settings ----

    @Test
    fun `the general settings are two columns of cards in a wide window and one column in a narrow one`() {
        inWindow(1280) { _, navigator ->
            open(navigator, Screen.Settings)
            onNodeWithTag(SettingsTab.GENERAL.testTag).performClick()
            waitForIdle()
            val production = onNodeWithText("Производство").getBoundsInRoot()
            val theme = onNodeWithText("Тема оформления").getBoundsInRoot()
            assertEquals(production.top, theme.top, "the first cards of the two columns start together")
            assertTrue(theme.left > production.left + 300.dp, "the second column is to the right: $production, $theme")
        }
        inWindow(800) { _, navigator ->
            open(navigator, Screen.Settings)
            onNodeWithTag(SettingsTab.GENERAL.testTag).performClick()
            waitForIdle()
            // The theme is under the fold of the 800 dp window: its place is read where it is, not where it is cut off.
            val production = onNodeWithText("Производство").getUnclippedBoundsInRoot()
            val theme = onNodeWithText("Тема оформления").getUnclippedBoundsInRoot()
            assertEquals(production.left, theme.left, "one column")
            assertTrue(theme.top > production.bottom, "the theme is under the production: $production, $theme")
        }
    }

    @Test
    fun `the tabs of the settings are one row of 44 dp, the chosen one is selected`() {
        inWindow(1280) { _, navigator ->
            open(navigator, Screen.Settings)
            onNodeWithTag(SettingsTab.GENERAL.testTag).performClick()
            waitForIdle()
            val tops = SettingsTab.entries.map { bounds(it.testTag).top }
            assertEquals(1, tops.distinct().size, "one row: $tops")
            assertEquals(44.dp, bounds(SettingsTab.GENERAL.testTag).height)
            onNodeWithTag(SettingsTab.GENERAL.testTag).assertIsSelected()
            SettingsTab.entries.filter { it != SettingsTab.GENERAL }.forEach { onNodeWithTag(it.testTag).assertIsNotSelected() }
        }
    }
}
