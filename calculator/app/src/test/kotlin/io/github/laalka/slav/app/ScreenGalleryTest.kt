package io.github.laalka.slav.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.laalka.slav.app.attachments.AttachmentTags
import io.github.laalka.slav.app.calculations.CalculationTags
import io.github.laalka.slav.app.calculations.goldenInput
import io.github.laalka.slav.app.calculations.quickGoldenInput
import io.github.laalka.slav.app.calculations.saveCalculated
import io.github.laalka.slav.app.calculations.saveQuickCalculated
import io.github.laalka.slav.app.calculations.updateSettings
import io.github.laalka.slav.app.developer.RandomCalculation
import io.github.laalka.slav.app.editor.EditorTags
import io.github.laalka.slav.app.editor.read
import io.github.laalka.slav.app.library.LibraryTags
import io.github.laalka.slav.app.quick.QuickTags
import io.github.laalka.slav.app.settings.GeneralTags
import io.github.laalka.slav.app.settings.SettingsTab
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.CalculationKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The window the program opens with (Main.kt): the dp of the tests are pixels, so every screen lays out as on a real one.
private const val GALLERY_WIDTH = 1280
private const val GALLERY_HEIGHT = 800

// The seeds of the random calculations: fixed, so a picture of the gallery shows the same calculation every run.
private const val FULL_SEED = 2
private const val QUICK_SEED = 5

// Every picture of a page of a scrolling screen overlaps the one before it by this many pixels.
private const val PAGE_OVERLAP = 96

/**
 * The whole program in pictures: the real [App] at the size of its window, in both themes, every screen and
 * every dialog, saved as `build/screens/<light|dark>/<NN-name>.png` of the module (the build directory, which
 * is not committed). The pictures are what the visual audit of the screens is made from; the test itself only
 * asserts that every one of them was written and is not empty.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenGalleryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `every screen of the program is saved as a picture in the light theme`() = gallery(dark = false)

    @Test
    fun `every screen of the program is saved as a picture in the dark theme`() = gallery(dark = true)

    private fun gallery(dark: Boolean) = runDesktopComposeUiTest(width = GALLERY_WIDTH, height = GALLERY_HEIGHT) {
        val folder = File(System.getProperty("user.dir"), "build/screens/${if (dark) "dark" else "light"}")
        folder.deleteRecursively()
        assertTrue(folder.mkdirs(), "cannot create $folder")
        val shots = Shots(this, folder)
        Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed()).use { deps ->
            lateinit var navigator: Navigator
            setContent {
                SlavTheme(darkTheme = dark) {
                    navigator = rememberNavigator(deps)
                    App(deps, NoFilePicker, navigator)
                }
            }
            waitForIdle()
            shots.take("list-empty")

            val data = seed(deps)
            listScreens(navigator, shots, data)
            editorScreens(deps, navigator, shots)
            quickScreens(navigator, shots)
            cardScreens(deps, navigator, shots, data)
            libraryScreens(deps, navigator, shots, data)
            settingsScreens(deps, navigator, shots)
            developerScreens(navigator, shots)
        }
        shots.assertAllWritten(REQUIRED)
    }

    // ---- data ----

    /** The calculations the screens are shown with; the ids are of the full and the quick one that carry a drawing. */
    private class Seeded(val full: UUID, val quick: UUID, val inputs: List<CalculationInput>)

    private fun seed(deps: Deps): Seeded {
        val book = deps.settings.load()
        var next = 1L
        fun random(seed: Int) = RandomCalculation(book, Random(seed)).calculationForm { next++ }.read().input
        val inputs = listOf(
            goldenInput("Кронштейн опорный", quantity = 20).copy(counterparty = "ООО «Ромашка»", discountPercent = BigDecimal("5")),
            random(3).copy(name = "Рама стойки", counterparty = "АО «Уралмаш»", quantity = 4),
            random(7).copy(
                name = "Короб защитный с очень длинным названием, чтобы проверить обрезку текста в таблице",
                counterparty = "ИП Иванов Сергей Петрович",
                quantity = 150,
                discountPercent = BigDecimal("12.5"),
            ),
        )
        val ids = inputs.map { deps.saveCalculated(it) }
        val quick = deps.saveQuickCalculated(
            quickGoldenInput("Лист на раскрой", quantity = 10).copy(counterparty = "ООО «ТехноПром»", discountPercent = BigDecimal("3")),
        )
        deps.saveQuickCalculated(quickGoldenInput("Заготовка фланца", quantity = 300))
        deps.calculations.saveDraft(goldenInput("Черновик крышки"))
        deps.attachments.add(ids.first(), pdfDrawing("plan.pdf"))
        deps.attachments.add(quick, pdfDrawing("раскрой.pdf"))
        return Seeded(ids.first(), quick, inputs)
    }

    /** A one-page A4 drawing with some lines on it, so its thumbnail is not a blank sheet. */
    private fun pdfDrawing(name: String): Path = temp.newFolder("drawing-${UUID.randomUUID()}").toPath().resolve(name).also { target ->
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.addRect(60f, 300f, 300f, 220f)
                stream.addRect(120f, 360f, 90f, 90f)
                stream.moveTo(60f, 300f)
                stream.lineTo(360f, 520f)
                stream.stroke()
            }
            document.save(target.toFile())
        }
    }

    // ---- screens ----

    private fun DesktopComposeUiTest.listScreens(navigator: Navigator, shots: Shots, data: Seeded) {
        runOnIdle { navigator.navigate(Screen.Calculations) }
        waitForIdle()
        shots.take("list")

        onNodeWithTag(CalculationTags.SEARCH).performTextInput("нет такого расчёта")
        waitForIdle()
        shots.take("list-no-matches")
        onNodeWithTag(CalculationTags.CLEAR_FILTERS).performClick()
        waitForIdle()

        onNodeWithTag(CalculationTags.SELECT).performClick()
        onNodeWithTag("${CalculationTags.row(0)}/check").performClick()
        onNodeWithTag("${CalculationTags.row(1)}/check").performClick()
        waitForIdle()
        shots.take("list-selection")

        val target = temp.newFolder("archive").toPath().resolve("slav-archive.zip")
        runOnIdle { navigator.calculationList.requestArchive(target) }
        waitForIdle()
        shots.take("list-archive-dialog")
        runOnIdle { navigator.calculationList.cancelArchive() }
        onNodeWithTag(CalculationTags.SELECT_CANCEL).performClick()
        waitForIdle()

        runOnIdle { navigator.calculationList.requestDelete(data.full) }
        waitForIdle()
        shots.take("list-delete-dialog")
        runOnIdle { navigator.calculationList.cancelDelete() }
        waitForIdle()

        onNodeWithTag(CalculationTags.NEW).performClick()
        waitForIdle()
        shots.take("list-new-menu")
    }

    private fun DesktopComposeUiTest.editorScreens(deps: Deps, navigator: Navigator, shots: Shots) {
        onNodeWithTag(CalculationTags.newOf(CalculationKind.FULL)).performClick()
        waitForIdle()
        shots.takeAt("editor-full-new", listOf(0, 100))

        // Two parts with nothing chosen: every field they need is marked, and «Рассчитать» stays shut.
        onNodeWithTag(EditorTags.ADD_PART).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag(EditorTags.CALCULATE).performClick()
        waitForIdle()
        shots.takeAt("editor-full-errors", listOf(50, 100))

        val editor = { assertIs<Page.Editor>(navigator.page.value).model }
        runOnIdle { editor().fillRandomly(Random(FULL_SEED)) }
        waitForIdle()
        shots.takeAt("editor-full-filled", listOf(0, 50, 100))

        // The prices of the first part's grade are gone: «Рассчитать» is open, but the calculation stops and says where.
        val book = deps.settings.load()
        val grade = runOnIdle { editor().state.value.parts.first().grade.selected }
        deps.updateSettings { it.copy(materialPricesPerKg = it.materialPricesPerKg.filterNot { price -> price.grade == grade }) }
        onNodeWithTag(EditorTags.CALCULATE).performClick()
        waitForIdle()
        shots.takeAt("editor-full-calc-errors", listOf(100))
        deps.settings.save(book)

        onNodeWithTag(LibraryTags.toLibrary("part/0")).performScrollTo().performClick()
        waitForIdle()
        shots.take("editor-library-save-dialog")
        onNodeWithTag(LibraryTags.SAVE_CANCEL).performClick()
        waitForIdle()

        runOnIdle { navigator.navigate(Screen.Calculations) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.quickScreens(navigator: Navigator, shots: Shots) {
        runOnIdle { navigator.navigate(Screen.Editor(null, CalculationKind.QUICK)) }
        waitForIdle()
        shots.takeAt("editor-quick-new", listOf(0, 100))
        runOnIdle { assertIs<Page.QuickEditor>(navigator.page.value).model.fillRandomly(Random(QUICK_SEED)) }
        waitForIdle()
        shots.takeAt("editor-quick-filled", listOf(0, 100))
        runOnIdle { navigator.navigate(Screen.Calculations) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.cardScreens(deps: Deps, navigator: Navigator, shots: Shots, data: Seeded) {
        runOnIdle { navigator.navigate(Screen.Card(data.full)) }
        awaitThumbnail()
        shots.takePaged("card-full", maxPages = 4)

        onNodeWithTag(CalculationTags.CARD_QUANTITY_CHANGE).performScrollTo().performClick()
        waitForIdle()
        shots.take("card-quantity-dialog")
        onNodeWithTag(CalculationTags.CARD_QUANTITY_CANCEL).performClick()
        waitForIdle()

        onNodeWithTag(CalculationTags.CARD_RECALC).performScrollTo().performClick()
        waitForIdle()
        shots.take("card-recalc-dialog")
        onNodeWithTag(CalculationTags.CARD_RECALC_CANCEL).performClick()
        waitForIdle()

        onNodeWithTag(CalculationTags.CARD_EDIT).performScrollTo().performClick()
        waitForIdle()
        shots.take("card-password-dialog")
        onNodeWithTag(CalculationTags.EDIT_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
        onNodeWithTag(CalculationTags.EDIT_CONFIRM).performClick()
        awaitThumbnail()
        // A change in an edit in place: leaving it asks whether to throw the change away.
        onNodeWithTag(EditorTags.QUANTITY).performScrollTo().performTextReplacement("3")
        waitForIdle()
        shots.takeAt("editor-in-place", listOf(0))
        onNodeWithTag(EditorTags.BACK).performClick()
        waitForIdle()
        shots.take("editor-discard-dialog")
        onNodeWithTag(EditorTags.LEAVE_CONFIRM).performClick()
        waitForIdle()

        runOnIdle { navigator.navigate(Screen.Card(data.quick)) }
        awaitThumbnail()
        shots.takePaged("card-quick", maxPages = 3)
        runOnIdle { navigator.navigate(Screen.Calculations) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.libraryScreens(deps: Deps, navigator: Navigator, shots: Shots, data: Seeded) {
        onNodeWithTag(NAV_LIBRARY_TAG).performClick()
        waitForIdle()
        shots.take("library-empty")
        data.inputs.forEachIndexed { index, input ->
            deps.partTemplates.save(listOf("Кронштейн 2 мм", "Стойка рамы", "Короб защитный")[index], input.parts.first())
        }
        runOnIdle { navigator.library.reload() }
        waitForIdle()
        shots.take("library")

        runOnIdle { navigator.navigate(Screen.Editor(null, CalculationKind.FULL)) }
        waitForIdle()
        onNodeWithTag(LibraryTags.ADD_FROM_LIBRARY).performScrollTo().performClick()
        waitForIdle()
        shots.take("editor-library-pick-dialog")
        onNodeWithTag(LibraryTags.PICK_CLOSE).performClick()
        waitForIdle()
        runOnIdle { navigator.navigate(Screen.Calculations) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.settingsScreens(deps: Deps, navigator: Navigator, shots: Shots) {
        onNodeWithTag(NAV_SETTINGS_TAG).performClick()
        waitForIdle()
        for (tab in SettingsTab.entries) {
            onNodeWithTag(tab.testTag).performClick()
            waitForIdle()
            if (tab == SettingsTab.GENERAL) {
                // Developer mode is off: the switch asks the administrator password.
                onNodeWithTag(GeneralTags.DEVELOPER_SWITCH).performScrollTo().performClick()
                waitForIdle()
                shots.take("settings-developer-password-dialog")
                onNodeWithTag(GeneralTags.DEVELOPER_CANCEL).performClick()
                waitForIdle()
                // And turned on the way the user does it: with the password.
                onNodeWithTag(GeneralTags.DEVELOPER_SWITCH).performClick()
                onNodeWithTag(GeneralTags.DEVELOPER_PASSWORD).performTextReplacement(ADMIN_PASSWORD)
                onNodeWithTag(GeneralTags.DEVELOPER_CONFIRM).performClick()
                waitForIdle()
                assertTrue(deps.preferences.developerMode.value, "developer mode did not turn on")
                // The reset of the tables asks the password too; the picture is taken and the question is cancelled.
                onNodeWithTag(GeneralTags.RESET_TABLES).performScrollTo().performClick()
                waitForIdle()
                shots.take("settings-developer-reset-dialog")
                onNodeWithTag(GeneralTags.RESET_CANCEL).performClick()
                waitForIdle()
            }
            shots.takePaged("settings-${tab.name.lowercase().replace('_', '-')}", maxPages = 4)
        }
        runOnIdle { navigator.settings.selectTab(SettingsTab.MATERIALS) }
    }

    /** With developer mode on both editors have the extra button in their bar. */
    private fun DesktopComposeUiTest.developerScreens(navigator: Navigator, shots: Shots) {
        runOnIdle { navigator.navigate(Screen.Editor(null, CalculationKind.FULL)) }
        waitForIdle()
        shots.takeAt("editor-full-developer", listOf(0))
        runOnIdle { navigator.navigate(Screen.Editor(null, CalculationKind.QUICK)) }
        waitForIdle()
        shots.takeAt("editor-quick-developer", listOf(0))
        onNodeWithTag(QuickTags.FILL_RANDOM).assertExists()
        runOnIdle { navigator.navigate(Screen.Calculations) }
        waitForIdle()
    }

    // ---- pictures ----

    /** The thumbnails of the drawings are made in the background: waits until the one of the first drawing is on the screen. */
    private fun DesktopComposeUiTest.awaitThumbnail() {
        waitForIdle()
        waitUntil(timeoutMillis = 15_000) {
            listOf("image", "icon").any { part ->
                onAllNodesWithTag("${AttachmentTags.row(0)}/thumbnail/$part", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        waitForIdle()
    }

    /** Numbers and saves the pictures of one gallery; [take] is one picture of what is on the screen now. */
    private class Shots(private val test: DesktopComposeUiTest, private val folder: File) {
        private var number = 0
        private val screens = mutableSetOf<String>()

        /** [name] is the file's name; [screen] is the screen it belongs to, when the screen has several pictures. */
        fun take(name: String, screen: String = name) {
            number++
            val file = File(folder, "%02d-%s.png".format(number, name))
            // A dialog or a menu is a root of its own on top of the window; the first root is the window, which the scene draws with them.
            val image = test.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
            assertTrue(ImageIO.write(image, "png", file), "no PNG writer")
            assertTrue(file.length() > 0, "$file is empty")
            assertEquals(GALLERY_WIDTH to GALLERY_HEIGHT, image.width to image.height, "the picture of $name is not the size of the window")
            screens += screen
        }

        /** The scrolling body of the screen at each of [positions] (percent of its way down), 0 the top and 100 the bottom. */
        fun takeAt(name: String, positions: List<Int>) {
            positions.forEach { position ->
                test.scrollBody(position / 100.0)
                take(if (positions.size == 1) name else "$name-at$position", screen = name)
            }
        }

        /** The screen from its top down in pages, [maxPages] at most. */
        fun takePaged(name: String, maxPages: Int) {
            test.scrollBody(0.0)
            take(name)
            var page = 1
            while (page < maxPages && test.scrollBodyOnePage()) {
                page++
                take("$name-page$page", screen = name)
            }
        }

        /** Every screen of [required] has a picture, and every picture taken is on the disk and not empty. */
        fun assertAllWritten(required: List<String>) {
            val missing = required.filterNot { it in screens }
            assertTrue(missing.isEmpty(), "pictures not taken: $missing")
            val files = folder.listFiles { file -> file.extension == "png" }.orEmpty()
            assertEquals(number, files.size, "the pictures on disk are not the pictures taken")
            assertTrue(files.all { it.length() > 0 }, "an empty picture in $folder")
        }
    }

    private companion object {
        /** Every screen and dialog the audit looks at; a gallery that took none of one of these has silently lost it. */
        val REQUIRED = listOf(
            "list-empty", "list", "list-no-matches", "list-selection", "list-archive-dialog", "list-delete-dialog", "list-new-menu",
            "editor-full-new", "editor-full-errors", "editor-full-filled", "editor-full-calc-errors", "editor-library-save-dialog",
            "editor-quick-new", "editor-quick-filled",
            "card-full", "card-quantity-dialog", "card-recalc-dialog", "card-password-dialog", "editor-in-place", "editor-discard-dialog", "card-quick",
            "library-empty", "library", "editor-library-pick-dialog",
            "settings-materials", "settings-sortament", "settings-material-prices", "settings-operation-prices", "settings-general",
            "settings-transfer", "settings-developer-password-dialog", "settings-developer-reset-dialog",
            "editor-full-developer", "editor-quick-developer",
        )
    }
}

/** The biggest scrolling area of the screen: its body. Null when nothing on the screen scrolls. */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.body(): SemanticsNode? =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
        .maxByOrNull { it.boundsInRoot.height }

/** Scrolls the body of the screen to [fraction] of its way down. */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.scrollBody(fraction: Double) {
    val node = body() ?: return
    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
    scrollNode(node, (range.maxValue() * fraction - range.value()).toFloat())
}

/** Scrolls the body of the screen one page down; false when it is at the bottom already. */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.scrollBodyOnePage(): Boolean {
    val node = body() ?: return false
    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
    val left = range.maxValue() - range.value()
    if (left < 1f) return false
    scrollNode(node, minOf(left, node.boundsInRoot.height - PAGE_OVERLAP))
    return true
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.scrollNode(node: SemanticsNode, delta: Float) {
    onNode(SemanticsMatcher("the scrolling body") { it.id == node.id })
        .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
    waitForIdle()
}
