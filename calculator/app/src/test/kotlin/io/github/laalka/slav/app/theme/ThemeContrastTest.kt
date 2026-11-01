package io.github.laalka.slav.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2.x contrast of text on a background; 4.5 is the AA level for body text. */
private const val AA = 4.5

/** The level for large text (18 sp bold and over) and for the parts of an interface that are not text, a button's fill against the card it lies on. */
private const val LARGE = 3.0

private fun channel(value: Float): Double {
    val v = value.toDouble()
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun luminance(color: Color): Double = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

private fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** [color] with its opacity laid over [background]: what the eye sees of a translucent thing on a surface. */
private fun over(color: Color, background: Color): Color {
    val alpha = color.alpha
    return Color(
        red = color.red * alpha + background.red * (1 - alpha),
        green = color.green * alpha + background.green * (1 - alpha),
        blue = color.blue * alpha + background.blue * (1 - alpha),
    )
}

/** The WCAG level for the parts of an interface that are not text: a scrollbar thumb is one of them, though it only needs to be seen. */
private const val VISIBLE = 2.0

/** How far the colour is from grey, 0..1: a saturated red has a high one. */
private fun saturation(color: Color): Float {
    val top = maxOf(color.red, color.green, color.blue)
    val bottom = minOf(color.red, color.green, color.blue)
    return if (top == 0f) 0f else (top - bottom) / top
}

/** [text] drawn on [background], which has to reach [minimum]: the unit of the table of pairs below. */
private class Pairing(val name: String, val text: Color, val background: Color, val minimum: Double = AA) {
    val ratio: Double get() = contrast(text, background)
}

/**
 * Every text colour of the design with every fill it is drawn on. A translucent fill (the dark chips, the banner of an
 * error) is read over the card and over the page, the two surfaces it lies on, and the worse of the two counts.
 */
private fun pairings(scheme: ColorScheme, extended: ExtendedColors): List<Pairing> = buildList {
    val grounds = mapOf("surface" to scheme.surface, "background" to scheme.background)
    fun on(name: String, text: Color, vararg backgrounds: Pair<String, Color>) =
        backgrounds.forEach { (ground, fill) -> add(Pairing("$name on $ground", text, fill)) }

    fun onFill(name: String, text: Color, fill: Color) =
        grounds.forEach { (ground, base) -> add(Pairing("$name on a fill over $ground", text, over(fill, base))) }

    on("onBackground", scheme.onBackground, "background" to scheme.background)
    on("onSurface", scheme.onSurface, *grounds.toList().toTypedArray(), "surfaceVariant" to scheme.surfaceVariant)
    on(
        "onSurface",
        scheme.onSurface,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh (a dialog)" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )
    on("onSurfaceVariant", scheme.onSurfaceVariant, *grounds.toList().toTypedArray(), "surfaceVariant" to scheme.surfaceVariant)
    add(Pairing("onPrimary on primary", scheme.onPrimary, scheme.primary))
    onFill("onPrimaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
    on(
        "accentText",
        extended.accentText,
        *grounds.toList().toTypedArray(),
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainerHigh (a dialog)" to scheme.surfaceContainerHigh,
    )
    // `error` is the colour of text as well as of the outline of a field: the messages under the fields are drawn with it.
    on("error", scheme.error, *grounds.toList().toTypedArray())
    on("errorText", extended.errorText, *grounds.toList().toTypedArray())
    add(Pairing("onError on error", scheme.onError, scheme.error))
    onFill("onErrorContainer", scheme.onErrorContainer, scheme.errorContainer)
    onFill("errorText", extended.errorText, scheme.errorContainer)
    on("tertiary (a warning)", scheme.tertiary, *grounds.toList().toTypedArray(), "tertiaryContainer" to scheme.tertiaryContainer)
    add(Pairing("onTertiary on tertiary", scheme.onTertiary, scheme.tertiary))
    add(Pairing("onTertiaryContainer on tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer))
    add(Pairing("onSecondary on secondary", scheme.onSecondary, scheme.secondary))
    add(Pairing("onSecondaryContainer on secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer))
    add(Pairing("inverseOnSurface on inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface))
    add(Pairing("inversePrimary on inverseSurface", scheme.inversePrimary, scheme.inverseSurface))
    onFill("onSuccess (the chip «Рассчитан»)", extended.onSuccess, extended.success)
    mapOf(
        "Черновик" to extended.statusDraft,
        "Отправлен" to extended.statusSent,
        "В работе" to extended.statusInWork,
        "Завершён" to extended.statusFinished,
    ).forEach { (status, chip) -> onFill("the chip «$status»", chip.content, chip.container) }
    add(Pairing("onRail on rail", extended.onRail, extended.rail))
    add(Pairing("onRailSelected on the selected item", extended.onRailSelected, over(extended.railSelected, extended.rail)))
    // Not text: the fill of a button against the card and the page it lies on.
    grounds.forEach { (ground, base) -> add(Pairing("primary against $ground", scheme.primary, base, LARGE)) }
}

class ThemeContrastTest {
    private val themes: Map<String, ColorScheme> = mapOf("light" to LightColors, "dark" to DarkColors)
    private val extendedThemes: Map<String, ExtendedColors> = mapOf("light" to LightExtendedColors, "dark" to DarkExtendedColors)

    @Test
    fun `every text colour of the design is at least 4,5 to 1 on every fill it is drawn on, in both themes`() {
        val pairings = themes.flatMap { (name, scheme) -> pairings(scheme, extendedThemes.getValue(name)).map { name to it } }
        // The table is long on purpose: one that shrank to a handful would pass just as well.
        assertTrue(pairings.size >= 100, "only ${pairings.size} pairs are checked")
        val failing = pairings.filter { (_, pairing) -> pairing.ratio < pairing.minimum }
            .map { (theme, pairing) -> "$theme: ${pairing.name} is ${"%.2f".format(pairing.ratio)}:1, needs ${pairing.minimum}" }
        assertTrue(failing.isEmpty(), failing.joinToString("\n"))
    }

    @Test
    fun `the ink of the logo is at least 3 to 1 on its orange`() {
        assertTrue(contrast(Color(0xFF0F172A), Color(0xFFEA580C)) >= LARGE, "the S is lost on the plate")
    }

    @Test
    fun `orange as the fill of a button is not text on the dark card, which is why accent text is a colour of its own`() {
        // The reason the design has [ExtendedColors.accentText]: `primary` is below AA as text on the card, in the dark theme.
        assertTrue(contrast(DarkColors.primary, DarkColors.surface) < AA, "primary is readable as text on the dark card: accentText is not needed")
        assertTrue(contrast(DarkExtendedColors.accentText, DarkColors.surface) >= AA)
    }

    @Test
    fun `the contrast function gives the WCAG values for black on white and for a mid grey`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 1e-9)
        assertEquals(1.0, contrast(Color.White, Color.White), 1e-9)
        // #767676 is the lightest grey that passes AA on white: 4.54:1.
        assertEquals(4.54, contrast(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun `error text is at least 4,5 to 1 on the surfaces of fields and messages in both themes`() {
        themes.forEach { (name, scheme) ->
            mapOf(
                "surface" to scheme.surface,
                "surfaceContainerLow (the cards)" to scheme.surfaceContainerLow,
                "surfaceContainer (the bottom bar)" to scheme.surfaceContainer,
            ).forEach { (surface, color) ->
                val ratio = contrast(scheme.error, color)
                assertTrue(ratio >= AA, "$name error on $surface is $ratio:1")
            }
        }
    }

    @Test
    fun `the text on the error colours is at least 4,5 to 1 in both themes`() {
        themes.forEach { (name, scheme) ->
            assertTrue(contrast(scheme.onError, scheme.error) >= AA, "$name onError on error is ${contrast(scheme.onError, scheme.error)}:1")
            // The container of the dark theme is translucent: the text is read on it as it lies on a card.
            val onContainer = contrast(scheme.onErrorContainer, over(scheme.errorContainer, scheme.surface))
            assertTrue(onContainer >= AA, "$name onErrorContainer on errorContainer is $onContainer:1")
        }
    }

    @Test
    fun `the error colour is a saturated red in both themes, not a pale pink`() {
        themes.forEach { (name, scheme) ->
            val error = scheme.error
            assertTrue(error.red > error.green && error.red > error.blue, "$name error is not red: $error")
            // The red of the dark theme, #F87171, is the lightest red the design uses: 0.54; a pink such as #FCA5A5 is 0.35.
            assertTrue(saturation(error) >= 0.5f, "$name error is washed out: saturation ${saturation(error)}")
        }
    }

    @Test
    fun `the scrollbar thumb is seen on the surface and on the cards in both themes, idle and under the pointer`() {
        themes.forEach { (name, scheme) ->
            val style = scrollbarStyle(scheme)
            mapOf("surface" to scheme.surface, "surfaceContainerLow (the cards)" to scheme.surfaceContainerLow).forEach { (surface, background) ->
                val idle = contrast(over(style.unhoverColor, background), background)
                val hover = contrast(over(style.hoverColor, background), background)
                assertTrue(idle >= VISIBLE, "$name idle scrollbar thumb on $surface is $idle:1")
                assertTrue(hover > idle, "$name scrollbar thumb under the pointer is $hover:1, the idle one $idle:1")
            }
        }
    }

    @Test
    fun `the library scrollbar, black at 12 percent, would be lost on the dark surface`() {
        // The reason the program has its own: this is what the default gives on the dark theme.
        val default = over(androidx.compose.foundation.defaultScrollbarStyle().unhoverColor, DarkColors.surface)
        assertTrue(contrast(default, DarkColors.surface) < VISIBLE, "the default thumb is ${contrast(default, DarkColors.surface)}:1 on the dark surface")
    }
}
