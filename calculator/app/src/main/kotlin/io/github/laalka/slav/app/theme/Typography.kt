package io.github.laalka.slav.app.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * Inter (SIL Open Font License, `fonts/Inter-OFL.txt`) is the font of the whole interface: it has Cyrillic and
 * digits of equal width (`tnum`, see [tabular]). The static cuts of the four weights the scale below uses.
 */
val InterFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/Inter-Bold.ttf", weight = FontWeight.Bold),
)

private fun text(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
    TextStyle(fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

/**
 * The scale of the design, on Material's roles; the styles the screens already use keep their role:
 * - `headlineSmall` the title of a screen, 26 sp bold, tightened by 0.4 sp;
 * - `titleMedium` the title of a card, 15 sp semibold; `titleSmall` 14 sp semibold; `titleLarge` an empty state or an emphasised total;
 * - `bodyLarge` the text of a field, 15 sp; `bodyMedium` the body, 14 sp; `bodySmall` a description, 13 sp;
 * - `labelLarge` a button, 14 sp semibold; `labelMedium` the label of a field, 12 sp medium;
 * - `labelSmall` the header of a table, 12 sp semibold, opened by 0.6 sp: the caller writes the text in capitals.
 * The roles the screens do not use take Material's sizes in Inter, so no text falls back to the system font.
 */
internal val SlavTypography: Typography = Typography().let { base ->
    fun TextStyle.inter() = copy(fontFamily = InterFamily)
    base.copy(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = text(size = 26, line = 32, weight = FontWeight.Bold, spacing = -0.4),
        titleLarge = text(size = 20, line = 28, weight = FontWeight.SemiBold),
        titleMedium = text(size = 15, line = 22, weight = FontWeight.SemiBold),
        titleSmall = text(size = 14, line = 20, weight = FontWeight.SemiBold),
        bodyLarge = text(size = 15, line = 22, weight = FontWeight.Normal),
        bodyMedium = text(size = 14, line = 20, weight = FontWeight.Normal),
        bodySmall = text(size = 13, line = 18, weight = FontWeight.Normal),
        labelLarge = text(size = 14, line = 20, weight = FontWeight.SemiBold),
        labelMedium = text(size = 12, line = 16, weight = FontWeight.Medium),
        labelSmall = text(size = 12, line = 16, weight = FontWeight.SemiBold, spacing = 0.6),
    )
}
