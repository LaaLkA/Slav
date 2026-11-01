package io.github.laalka.slav.app.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// The file of each font and the family its name must start with.
private val FONT_FILES = mapOf(
    "fonts/Inter-Regular.ttf" to "Inter",
    "fonts/Inter-Medium.ttf" to "Inter",
    "fonts/Inter-SemiBold.ttf" to "Inter",
    "fonts/Inter-Bold.ttf" to "Inter",
    "fonts/Unbounded-ExtraBold.ttf" to "Unbounded",
)

private fun typeface(resource: String): Typeface {
    val bytes = checkNotNull(TypographyTest::class.java.classLoader.getResourceAsStream(resource)) { "$resource is not in the program" }.use { it.readBytes() }
    return checkNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "$resource is not a font" }
}

@OptIn(ExperimentalTestApi::class)
class TypographyTest {
    @Test
    fun `the fonts are bundled and every glyph of the interface is in them`() {
        // Cyrillic capitals and small letters, ё, the ruble, the signs the amounts and the titles use.
        val text = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя₽«»−—·↓0123456789"
        FONT_FILES.forEach { (resource, family) ->
            val face = typeface(resource)
            assertTrue(face.familyName.startsWith(family), "$resource is the family ${face.familyName}, not $family")
            val missing = text.filterNot { char -> face.getStringGlyphs(char.toString()).single().toInt() != 0 }
            assertEquals("", missing, "$resource has no glyph for: $missing")
        }
    }

    @Test
    fun `the licences of the fonts are bundled with them`() {
        listOf("fonts/Inter-OFL.txt", "fonts/Unbounded-OFL.txt", "fonts/OFL.txt").forEach { licence ->
            val text = checkNotNull(TypographyTest::class.java.classLoader.getResourceAsStream(licence)) { "$licence is missing" }.use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue("SIL OPEN FONT LICENSE" in text, "$licence is not the Open Font License")
        }
    }

    @Test
    fun `the scale is the one of the design`() {
        fun TextStyle.check(size: Int, weight: FontWeight, spacing: Double = 0.0) {
            assertEquals(size.sp, fontSize, "size")
            assertEquals(weight, fontWeight, "weight")
            assertEquals(spacing.sp, letterSpacing, "letter spacing")
            assertEquals(InterFamily, fontFamily, "font family")
        }
        with(SlavTypography) {
            headlineSmall.check(26, FontWeight.Bold, spacing = -0.4)
            titleMedium.check(15, FontWeight.SemiBold)
            bodyMedium.check(14, FontWeight.Normal)
            bodyLarge.check(15, FontWeight.Normal)
            labelMedium.check(12, FontWeight.Medium)
            labelSmall.check(12, FontWeight.SemiBold, spacing = 0.6)
            labelLarge.check(14, FontWeight.SemiBold)
        }
        // No role is left to the system font: every style of Material is set in Inter.
        with(SlavTypography) {
            listOf(
                displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall, titleLarge, titleMedium, titleSmall,
                bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall,
            ).forEach { assertEquals(InterFamily, it.fontFamily) }
        }
    }

    @Test
    fun `text with no style of its own is the body, 14 sp`() = runDesktopComposeUiTest(width = 200, height = 100) {
        var ambient: TextStyle? = null
        setContent { SlavTheme(darkTheme = false) { ambient = LocalTextStyle.current } }
        waitForIdle()
        assertEquals(14.sp, ambient?.fontSize)
    }

    @Test
    fun `digits are of equal width with tabular and the bundled Inter has proportional ones without`() = runDesktopComposeUiTest(width = 200, height = 100) {
        val widths = mutableMapOf<String, Int>()
        setContent {
            SlavTheme(darkTheme = false) {
                val measurer = rememberTextMeasurer()
                val body = MaterialTheme.typography.bodyMedium
                fun width(text: String, style: TextStyle) = measurer.measure(text, style).size.width
                SideEffect {
                    widths["narrow"] = width("1 111,11", body)
                    widths["wide"] = width("8 888,88", body)
                    widths["narrow tabular"] = width("1 111,11", body.tabular())
                    widths["wide tabular"] = width("8 888,88", body.tabular())
                }
            }
        }
        waitForIdle()
        assertEquals(widths.getValue("narrow tabular"), widths.getValue("wide tabular"), "the digits of a column do not line up with tnum")
        // A font that has no proportional digits (the system fallback) would fail here: Inter is really the font of the text.
        assertNotEquals(widths.getValue("narrow"), widths.getValue("wide"), "Inter is not the font of the text: its digits are not proportional")
    }
}
