package io.github.laalka.slav.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Font as SkiaFont

// The logo is drawn on a square of 96 units, as the mockup draws it (variant 10, «Оранжевая плашка»): an orange
// rounded square and the letter S in Unbounded ExtraBold, 70 units tall in the body, centred by its advance, on the
// baseline at 72.
private const val UNITS = 96f
private const val CORNER_RADIUS = 22f
private const val LETTER_SIZE = 70f
private const val LETTER_CENTRE = 48f
private const val LETTER_BASELINE = 72f

private val LogoFill = Color(0xFFEA580C)
private val LogoInk = Color(0xFF0F172A)

// Unbounded ExtraBold (SIL Open Font License, fonts/Unbounded-OFL.txt): the letter of the logo and nothing else.
private const val LETTER_FONT = "/fonts/Unbounded-ExtraBold.ttf"

// The outline of the glyph, not a text: the logo is then the same shape at every size and on every machine, and needs
// no text layout, so it can be drawn where there is none (the icon of the window, the files of the installers).
private val letterS: Path by lazy {
    val bytes = checkNotNull(SlavLogoPainter::class.java.getResourceAsStream(LETTER_FONT)) { "Font $LETTER_FONT is missing from the program" }
        .use { it.readBytes() }
    val typeface = checkNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "Font $LETTER_FONT cannot be read" }
    val font = SkiaFont(typeface, LETTER_SIZE)
    val glyph = font.getStringGlyphs("S").single()
    val advance = font.getWidths(shortArrayOf(glyph)).single()
    val outline = checkNotNull(font.getPath(glyph)) { "Unbounded ExtraBold has no outline for the letter S" }
    outline.asComposePath().apply { translate(Offset(LETTER_CENTRE - advance / 2, LETTER_BASELINE)) }
}

/** Draws the logo over the smaller side of the scope's square, from its top left corner. */
fun DrawScope.drawSlavLogo() {
    scale(size.minDimension / UNITS, pivot = Offset.Zero) {
        drawRoundRect(LogoFill, size = Size(UNITS, UNITS), cornerRadius = CornerRadius(CORNER_RADIUS))
        drawPath(letterS, LogoInk)
    }
}

/** The logo as a painter: the icon of the window, and what [SlavLogo] shows. */
object SlavLogoPainter : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() = drawSlavLogo()
}

/** The logo at [size]: the top of the navigation rail. */
@Composable
fun SlavLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(painter = SlavLogoPainter, contentDescription = "Slav", modifier = modifier.size(size))
}
