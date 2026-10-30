package io.github.laalka.slav.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.editor.ShapeKind
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The window the program opens with (Main.kt): the dp of the tests are pixels, so the editor lays out as on a real screen.
private const val DEFAULT_WIDTH = 1280
private const val DEFAULT_HEIGHT = 800

/** The «Форма» row of a sheet: with five entries the longest label, «Прямоугольник / полоса», must still be whole. */
@OptIn(ExperimentalTestApi::class)
class ShapeRowUiTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `no label of the shape row is cut off at the default window size, whichever shape is chosen`() =
        runDesktopComposeUiTest(width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT) {
            Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
                setContent { SlavTheme(darkTheme = false) { App(deps, NoFilePicker) } }
                onNodeWithTag(CalculationTags.NEW).performClick()
                onNodeWithTag(CalculationTags.newOf(CalculationKind.FULL)).performClick()
                waitForIdle()
                onNodeWithTag(EditorTags.shape(PART, ShapeKind.RECTANGLE)).performScrollTo()
                waitForIdle()

                // The picture of the editor with the row as the user first sees it (the rectangle chosen).
                saveEditorPicture(onRoot().captureToImage().toAwtImage())

                for (chosen in ShapeKind.entries) {
                    onNodeWithTag(EditorTags.shape(PART, chosen)).performScrollTo().performClick()
                    waitForIdle()
                    for (kind in ShapeKind.entries) {
                        val layout = onNode(
                            hasText(kind.title) and hasAnyAncestor(hasTestTag(EditorTags.shape(PART, kind))),
                            useUnmergedTree = true,
                        ).textLayout()
                        // The width the text wants on one line must fit the width its segment gives it. TextLayoutResult.hasVisualOverflow
                        // cannot tell: for text that does not wrap it is true whenever the text is narrower than its slot.
                        val wanted = layout.multiParagraph.maxIntrinsicWidth
                        val granted = layout.layoutInput.constraints.maxWidth
                        assertTrue(wanted <= granted, "«${kind.title}» is cut off while «${chosen.title}» is chosen: it wants $wanted px, the segment gives $granted px")
                        assertEquals(1, layout.lineCount, "«${kind.title}» is on one line")
                    }
                    // The row does not run out of the window either.
                    val last = onNodeWithTag(EditorTags.shape(PART, ShapeKind.entries.last())).fetchSemanticsNode().boundsInRoot
                    assertTrue(last.right <= DEFAULT_WIDTH, "the shape row ends at ${last.right} px, the window is $DEFAULT_WIDTH px wide")
                }
            }
        }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = assertNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action, "the node is not a text")
        action(results)
        return results.single()
    }

    /** Saved under the build directory of the module, which is not committed. */
    private fun saveEditorPicture(image: java.awt.image.BufferedImage) {
        val file = File(System.getProperty("user.dir"), "build/shape-row.png")
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", file), "no PNG writer")
        assertEquals(DEFAULT_WIDTH to DEFAULT_HEIGHT, image.width to image.height)
        println("Shape row picture: ${file.absolutePath}")
    }

    private companion object {
        const val PART = "part/0"
    }
}
