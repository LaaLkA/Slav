package io.github.laalka.slav.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2.x contrast of text on a background; 4.5 is the AA level for body text. */
private const val AA = 4.5

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

/** How far the colour is from grey, 0..1: a saturated red has a high one. */
private fun saturation(color: Color): Float {
    val top = maxOf(color.red, color.green, color.blue)
    val bottom = minOf(color.red, color.green, color.blue)
    return if (top == 0f) 0f else (top - bottom) / top
}

class ThemeContrastTest {
    private val themes: Map<String, ColorScheme> = mapOf("light" to LightColors, "dark" to DarkColors)

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
            val onContainer = contrast(scheme.onErrorContainer, scheme.errorContainer)
            assertTrue(onContainer >= AA, "$name onErrorContainer on errorContainer is $onContainer:1")
        }
    }

    @Test
    fun `the error colour is a saturated red in both themes, not a pale pink`() {
        themes.forEach { (name, scheme) ->
            val error = scheme.error
            assertTrue(error.red > error.green && error.red > error.blue, "$name error is not red: $error")
            assertTrue(saturation(error) >= 0.6f, "$name error is washed out: saturation ${saturation(error)}")
        }
    }
}
