package io.github.laalka.slav.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

/** The colour of a token written as the design writes it, `#RRGGBB`; [alpha] is the opacity of a translucent one. */
private fun token(hex: String, alpha: Double = 1.0): Color {
    val rgb = hex.removePrefix("#").toLong(16)
    return Color(
        red = ((rgb shr 16) and 0xFF) / 255f,
        green = ((rgb shr 8) and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
        alpha = alpha.toFloat(),
    )
}

private fun assertToken(expected: Color, actual: Color, what: String) {
    assertEquals(expected.red, actual.red, 1e-3f, "$what: red of $actual")
    assertEquals(expected.green, actual.green, 1e-3f, "$what: green of $actual")
    assertEquals(expected.blue, actual.blue, 1e-3f, "$what: blue of $actual")
    assertEquals(expected.alpha, actual.alpha, 0.01f, "$what: opacity of $actual")
}

/** The tokens of the design, exactly as the mockups and the brief give them: a colour moved by a digit passes the contrast test and fails here. */
class ThemeTokensTest {
    @Test
    fun `the light scheme has the tokens of the design`() {
        val scheme = LightColors
        mapOf(
            "background" to (scheme.background to "#F8FAFC"),
            "surface (cards)" to (scheme.surface to "#FFFFFF"),
            "surfaceVariant (track)" to (scheme.surfaceVariant to "#F1F5F9"),
            "outlineVariant (card borders, dividers)" to (scheme.outlineVariant to "#E2E8F0"),
            "outline (field borders)" to (scheme.outline to "#CBD5E1"),
            "onSurface" to (scheme.onSurface to "#0F172A"),
            "onSurfaceVariant (secondary text)" to (scheme.onSurfaceVariant to "#475569"),
            "primary" to (scheme.primary to "#C2410C"),
            "onPrimary" to (scheme.onPrimary to "#FFFFFF"),
            "primaryContainer" to (scheme.primaryContainer to "#FFEDD5"),
            "onPrimaryContainer" to (scheme.onPrimaryContainer to "#9A3412"),
            "error" to (scheme.error to "#B91C1C"),
            "errorContainer" to (scheme.errorContainer to "#FEF2F2"),
            "onErrorContainer" to (scheme.onErrorContainer to "#7F1D1D"),
        ).forEach { (what, pair) -> assertToken(token(pair.second), pair.first, "light $what") }
    }

    @Test
    fun `the dark scheme has the tokens of the design`() {
        val scheme = DarkColors
        mapOf(
            "background" to (scheme.background to token("#0F172A")),
            "surface (cards)" to (scheme.surface to token("#1E293B")),
            "surfaceVariant (track inside cards)" to (scheme.surfaceVariant to token("#0F172A")),
            "outlineVariant" to (scheme.outlineVariant to token("#334155")),
            "outline" to (scheme.outline to token("#475569")),
            "onSurface" to (scheme.onSurface to token("#F1F5F9")),
            "onSurfaceVariant" to (scheme.onSurfaceVariant to token("#94A3B8")),
            "primary" to (scheme.primary to token("#EA580C")),
            "onPrimary" to (scheme.onPrimary to token("#0F172A")),
            "primaryContainer" to (scheme.primaryContainer to token("#FB923C", alpha = 0.16)),
            "onPrimaryContainer" to (scheme.onPrimaryContainer to token("#FDBA74")),
            "error (the outline)" to (scheme.error to token("#F87171")),
            "errorContainer" to (scheme.errorContainer to token("#F87171", alpha = 0.12)),
        ).forEach { (what, pair) -> assertToken(pair.second, pair.first, "dark $what") }
    }

    @Test
    fun `the colours Material has no role for are the ones of the design, light and dark`() {
        val light = LightExtendedColors
        assertToken(token("#DCFCE7"), light.success, "light success")
        assertToken(token("#166534"), light.onSuccess, "light onSuccess")
        assertToken(token("#F1F5F9"), light.statusDraft.container, "light draft")
        assertToken(token("#475569"), light.statusDraft.content, "light draft text")
        assertToken(token("#DBEAFE"), light.statusSent.container, "light sent")
        assertToken(token("#1E40AF"), light.statusSent.content, "light sent text")
        assertToken(token("#FFEDD5"), light.statusInWork.container, "light in work")
        assertToken(token("#9A3412"), light.statusInWork.content, "light in work text")
        assertToken(token("#E2E8F0"), light.statusFinished.container, "light finished")
        assertToken(token("#334155"), light.statusFinished.content, "light finished text")
        assertToken(token("#0F172A"), light.rail, "light rail: dark in the light theme too")
        assertToken(token("#FDBA74"), light.onRailSelected, "light selected item")
        assertToken(token("#94A3B8"), light.onRail, "light rail items")
        assertToken(token("#C2410C"), light.accentText, "light accent text")
        assertToken(token("#B91C1C"), light.errorText, "light error text")
        assertToken(token("#FFFFFF"), light.buttonFill, "light secondary button: white")
        assertToken(token("#FECACA"), light.errorBorder, "light line of a destructive button and an alert")
        assertToken(token("#0F172A", alpha = 0.12), light.raisedShadow, "light shadow under the chosen segment")
        assertToken(token("#E2E8F0"), light.headerBorder, "light line under the title bar of an editor and of a card")

        val dark = DarkExtendedColors
        assertToken(token("#86EFAC"), dark.onSuccess, "dark success text")
        assertToken(token("#22C55E", alpha = 0.16), dark.success, "dark success")
        assertToken(token("#020617"), dark.rail, "dark rail")
        assertToken(token("#1E293B"), dark.railBorder, "dark rail border")
        assertToken(token("#FB923C", alpha = 0.16), dark.railSelected, "dark selected item")
        assertToken(token("#FDBA74"), dark.onRailSelected, "dark selected item text")
        assertToken(token("#FDBA74"), dark.accentText, "dark accent text")
        assertToken(token("#FCA5A5"), dark.errorText, "dark error text")
        assertEquals(0f, dark.buttonFill.alpha, "dark secondary button: nothing, the card or the page shows through")
        assertToken(token("#F87171", alpha = 0.5), dark.errorBorder, "dark line of a destructive button and an alert")
        assertEquals(0f, dark.raisedShadow.alpha, "dark: the chosen segment is lifted by its colour, not by a shadow")
        assertToken(token("#1E293B"), dark.headerBorder, "dark line under the title bar: the colour of the card, as the mockups draw it")
        // The dark chips: the hue of the light one at 16 % on the surface, with its light tint as text.
        listOf(dark.statusDraft, dark.statusSent, dark.statusInWork).forEach { chip ->
            assertEquals(0.16f, chip.container.alpha, 0.01f, "a dark chip is the hue at 16 %")
        }
    }

    @Test
    fun `a field and the chosen segment stand on the roles the design gives them in both themes`() {
        // The box of a field: white on the light card, the colour of the page inside the dark one.
        assertToken(token("#FFFFFF"), LightColors.surfaceContainerLowest, "light field")
        assertToken(token("#0F172A"), DarkColors.surfaceContainerLowest, "dark field")
        // The chosen segment: raised, white in the light theme and #334155 in the dark one.
        assertToken(token("#FFFFFF"), LightColors.surfaceBright, "light chosen segment")
        assertToken(token("#334155"), DarkColors.surfaceBright, "dark chosen segment")
    }

    @Test
    fun `the cards are drawn with 20 dp of padding and 12 dp between their blocks, and a page has 28 to 32 dp at its sides`() {
        assertEquals(20f, Spacing.card.value)
        assertEquals(12f, Spacing.gap.value)
        assertEquals(32f, Spacing.page.value, "the list, the library and the settings")
        assertEquals(28f, Spacing.pageTight.value, "an editor and a card, which have a title bar")
    }

    @Test
    fun `the shapes are those of the design`() {
        val density = Density(1f)
        val size = Size(400f, 400f)
        fun radius(shape: Shape) = (shape as RoundedCornerShape).topStart.toPx(size, density)
        assertEquals(14f, radius(SlavShapes.large), "cards")
        assertEquals(10f, radius(SlavShapes.medium), "fields and buttons")
        assertEquals(8f, radius(SlavShapes.small), "the segments")
        assertEquals(6f, radius(SlavShapes.extraSmall), "badges")
        assertEquals(16f, radius(SlavShapes.extraLarge), "dialogs")
    }
}
