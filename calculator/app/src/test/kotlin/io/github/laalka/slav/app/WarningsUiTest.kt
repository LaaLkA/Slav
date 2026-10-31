package io.github.laalka.slav.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.settings.REQUIRED
import io.github.laalka.slav.app.theme.DarkColors
import io.github.laalka.slav.app.theme.LightColors
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The warnings of an editor, looked at as pixels: the bottom message is a panel on the error container, the
 * outline of a field in error is 2 dp, the error text under it is heavier than the regular one. The editor
 * of a new calculation shows all of them at once, since nothing is filled in. The screenshots are kept in
 * the module's build directory: `build/warnings-light.png` and `build/warnings-dark.png`.
 */
class WarningsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun openNewEditor(deps: Deps, dark: Boolean) {
        compose.setContent { SlavTheme(darkTheme = dark) { App(deps, NoFilePicker) } }
        node(CalculationTags.NEW).performClick()
        node(CalculationTags.newOf(CalculationKind.FULL)).performClick()
        compose.waitForIdle()
    }

    private fun screenshot(name: String): File {
        val file = File("build/$name.png")
        file.parentFile.mkdirs()
        check(ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", file)) { "No PNG writer for $file" }
        return file
    }

    private fun near(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < 0.06f && abs(a.green - b.green) < 0.06f && abs(a.blue - b.blue) < 0.06f

    /** The pixels of the row at mid-height of [node], from its left edge, that have the colour of [color]; that is the outline's thickness. */
    private fun outlinePixels(node: SemanticsNodeInteraction, color: Color): Int {
        val image: ImageBitmap = node.captureToImage()
        val pixels = image.toPixelMap()
        val row = image.height / 2
        return (0 until image.width).takeWhile { near(pixels[it, row], color) }.size
    }

    private fun verifyWarnings(dark: Boolean, scheme: ColorScheme, screenshotName: String) {
        Deps(AppPaths(temp.root.toPath()), appVersion = "test").use { deps ->
            openNewEditor(deps, dark)

            // The bottom message is a panel: its container is the error container, edge to edge of the text.
            val message = node(EditorTags.MISSING).captureToImage()
            assertEquals(
                scheme.errorContainer.toArgb(),
                message.toPixelMap()[4, message.height / 2].toArgb(),
                "the panel of «Заполните выделенные поля» is not on the error container",
            )

            // The outline of a field in error is 2 dp, unfocused as well.
            val dp = compose.density.density
            val thickness = outlinePixels(node(EditorTags.NAME), scheme.error) / dp
            assertTrue(thickness >= 2f, "the outline of the name field in error is $thickness dp, not 2")

            // The text under it is heavier than the regular one.
            val texts = mutableListOf<TextLayoutResult>()
            compose.onAllNodesWithText(REQUIRED, useUnmergedTree = true)[0].fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                .action?.invoke(texts)
            assertEquals(FontWeight.SemiBold, texts.single().layoutInput.style.fontWeight, "weight of the error text")

            val file = screenshot(screenshotName)
            assertTrue(file.length() > 0, "$file is empty")
            println("screenshot: ${file.absolutePath}")
        }
    }

    @Test
    fun `the warnings of the editor in the light theme`() = verifyWarnings(dark = false, LightColors, "warnings-light")

    @Test
    fun `the warnings of the editor in the dark theme`() = verifyWarnings(dark = true, DarkColors, "warnings-dark")
}
