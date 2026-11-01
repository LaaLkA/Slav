package io.github.laalka.slav.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.editor.CutMode
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.editor.ShapeKind
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.app.ui.SegmentedChoice
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The window the program opens with (Main.kt): the dp of the tests are pixels, so the screens lay out as on a real one.
private const val WINDOW_WIDTH = 1280
private const val WINDOW_HEIGHT = 800

/**
 * The four segmented rows of the program — the theme, the kind of material, the shape of the sheet and the cut
 * mode of the sheet laser — are all [io.github.laalka.slav.app.ui.SegmentedChoice]. For every row, in both themes,
 * whichever segment is chosen: every label is whole on one line and every segment of the row has the same height
 * as before the choice.
 */
@OptIn(ExperimentalTestApi::class)
class SegmentedRowsUiTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A row of segments: [titles] are its labels, [segment] finds the node of one segment and [label] the text inside it. */
    private class SegmentedRow(
        val name: String,
        val titles: List<String>,
        val segment: DesktopComposeUiTest.(Int) -> SemanticsNodeInteraction,
        val label: DesktopComposeUiTest.(Int) -> SemanticsNodeInteraction,
    )

    private fun taggedRow(name: String, titles: List<String>, tagOf: (Int) -> String) = SegmentedRow(
        name,
        titles,
        segment = { onNodeWithTag(tagOf(it)) },
        label = { onNode(hasText(titles[it]) and hasAnyAncestor(hasTestTag(tagOf(it))), useUnmergedTree = true) },
    )

    private val themeRow = SegmentedRow(
        "theme",
        ThemeMode.entries.map { it.title },
        segment = { onNode(hasText(ThemeMode.entries[it].title) and isSelectable()) },
        label = { onNode(hasText(ThemeMode.entries[it].title), useUnmergedTree = true) },
    )

    private val kindRow = taggedRow("material kind", SortamentKind.entries.map { it.title }) {
        "${PART}/kind/${SortamentKind.entries[it].name}"
    }

    private val shapeRow = taggedRow("sheet shape", ShapeKind.entries.map { it.title }) { EditorTags.shape(PART, ShapeKind.entries[it]) }

    private val laserRow = taggedRow("laser cut mode", CutMode.entries.map { it.title }) { "$LASER/mode/${CutMode.entries[it].name}" }

    @Test
    fun `the theme row keeps every label whole whichever theme is chosen`() = inBothThemes {
        onNodeWithTag(NAV_SETTINGS_TAG).performClick()
        onNodeWithTag(SettingsTab.GENERAL.testTag).performClick()
        waitForIdle()
        check(themeRow)
    }

    @Test
    fun `the material kind row keeps every label whole whichever kind is chosen`() = inBothThemes {
        openNewFullEditor()
        check(kindRow)
    }

    @Test
    fun `the sheet shape row keeps every label whole whichever shape is chosen`() = inBothThemes {
        openNewFullEditor()
        check(shapeRow)
    }

    @Test
    fun `the laser cut mode row keeps every label whole whichever mode is chosen`() = inBothThemes {
        openNewFullEditor()
        // The laser is offered once the sheet has a grade and a thickness.
        choose("$PART/grade", "Ст3")
        choose("$PART/size", "2")
        onNodeWithTag("$LASER/check").performScrollTo().performClick()
        waitForIdle()
        check(laserRow)
    }

    @Test
    fun `a label stays on one line however narrow the row is squeezed`() {
        val titles = ShapeKind.entries.map { it.title }
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
                setContent {
                    SlavTheme(darkTheme = dark) {
                        Box(Modifier.width(SQUEEZED_ROW_WIDTH)) { SegmentedChoice(titles, selectedIndex = 0, onSelect = {}) }
                    }
                }
                waitForIdle()
                for (title in titles) {
                    val layout = onNode(hasText(title), useUnmergedTree = true).textLayout()
                    assertEquals(1, layout.lineCount, "«$title» wraps when the row is $SQUEEZED_ROW_WIDTH wide (dark theme: $dark)")
                }
            }
        }
    }

    private fun inBothThemes(body: DesktopComposeUiTest.() -> Unit) {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
                Deps(AppPaths(temp.newFolder().toPath()), appVersion = "test").use { deps ->
                    setContent { SlavTheme(darkTheme = dark) { App(deps, NoFilePicker) } }
                    body()
                }
            }
        }
    }

    private fun DesktopComposeUiTest.openNewFullEditor() {
        onNodeWithTag(CalculationTags.NEW).performClick()
        onNodeWithTag(CalculationTags.newOf(CalculationKind.FULL)).performClick()
        waitForIdle()
    }

    private fun DesktopComposeUiTest.choose(tag: String, option: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        onNodeWithTag("$tag/$option").performClick()
        waitForIdle()
    }

    /** Chooses every segment of [row] in turn and looks at the whole row each time. */
    private fun DesktopComposeUiTest.check(row: SegmentedRow) {
        var heights: List<Float>? = null
        var widths: List<Float>? = null
        for (chosen in row.titles.indices) {
            row.segment(this, chosen).performScrollTo().performClick()
            waitForIdle()
            val where = "the ${row.name} row with «${row.titles[chosen]}» chosen"
            val segments = row.titles.indices.map { row.segment(this, it).fetchSemanticsNode().boundsInRoot }
            for (index in row.titles.indices) {
                val title = row.titles[index]
                val label = row.label(this, index)
                val layout = label.textLayout()
                assertFalse(layout.hasVisualOverflow, "«$title» overflows its segment in $where")
                // The width the text wants on one line must fit the width its segment gives it. hasVisualOverflow alone
                // cannot tell a text that is cut from one that is not: for a text that does not wrap it is true whenever
                // the slot is wider than the text, so it holds here only because each segment gives its label exactly its width.
                val wanted = layout.multiParagraph.maxIntrinsicWidth
                val granted = layout.layoutInput.constraints.maxWidth
                assertTrue(wanted <= granted, "«$title» is cut off in $where: it wants $wanted px, the segment gives $granted px")
                assertEquals(1, layout.lineCount, "«$title» is on one line in $where")
                // The room on both sides of the label is the same, chosen or not: Material's check mark takes it from one side only.
                val text = label.fetchSemanticsNode().boundsInRoot
                val left = text.left - segments[index].left
                val right = segments[index].right - text.right
                assertTrue(left >= MIN_PADDING && abs(left - right) <= 1f, "«$title» has $left px before it and $right px after it in $where")
            }
            assertEquals(1, segments.map { it.height }.distinct().size, "the segments of $where are of different heights: ${segments.map { it.height }}")
            // The choice changes the colour of a segment, not its size.
            if (heights == null) heights = segments.map { it.height }
            if (widths == null) widths = segments.map { it.width }
            assertEquals(heights, segments.map { it.height }, "the heights changed after the choice of «${row.titles[chosen]}»")
            assertEquals(widths, segments.map { it.width }, "the widths changed after the choice of «${row.titles[chosen]}»")
            assertRowFitsWindow(segments, where)
        }
    }

    private fun assertRowFitsWindow(bounds: List<Rect>, where: String) {
        assertTrue(bounds.last().right <= WINDOW_WIDTH, "the segments of $where end at ${bounds.last().right} px, the window is $WINDOW_WIDTH px wide")
        assertTrue(bounds.zipWithNext().all { (left, right) -> left.right <= right.left + 2f }, "the segments of $where overlap: $bounds")
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = assertNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action, "the node is not a text")
        action(results)
        assertFalse(results.isEmpty(), "the text has no layout")
        return results.single()
    }

    private companion object {
        // Far less than the labels of the shape row need together, so they have to stay on their lines on their own.
        val SQUEEZED_ROW_WIDTH = 120.dp

        // Well under the 16 dp a segment leaves on each side of its label, yet more than the nothing the check mark leaves on one.
        const val MIN_PADDING = 8f
        const val PART = "part/0"
        const val LASER = "part/0/op/LASER_SHEET"
    }
}
