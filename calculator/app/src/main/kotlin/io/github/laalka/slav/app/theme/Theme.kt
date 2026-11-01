package io.github.laalka.slav.app.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

// The palette of the design: slate neutrals and one accent, orange. Every value of the schemes below is one of these.
private val Slate50 = Color(0xFFF8FAFC)
private val Slate100 = Color(0xFFF1F5F9)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate300 = Color(0xFFCBD5E1)
private val Slate400 = Color(0xFF94A3B8)
private val Slate600 = Color(0xFF475569)
private val Slate700 = Color(0xFF334155)
private val Slate800 = Color(0xFF1E293B)
private val Slate900 = Color(0xFF0F172A)
private val Slate950 = Color(0xFF020617)

private val Orange100 = Color(0xFFFFEDD5)
private val Orange300 = Color(0xFFFDBA74)
private val Orange600 = Color(0xFFEA580C)
private val Orange700 = Color(0xFFC2410C)
private val Orange800 = Color(0xFF9A3412)

private val Red300 = Color(0xFFFCA5A5)
private val Red400 = Color(0xFFF87171)
private val Red50 = Color(0xFFFEF2F2)
private val Red700 = Color(0xFFB91C1C)
private val Red900 = Color(0xFF7F1D1D)

// Warnings of the editor (the `tertiary` role) are amber, the one hue besides orange and red.
private val Amber100 = Color(0xFFFEF3C7)
private val Amber200 = Color(0xFFFDE68A)
private val Amber400 = Color(0xFFFBBF24)
private val Amber700 = Color(0xFFB45309)
private val Amber900 = Color(0xFF78350F)
private val Amber950 = Color(0xFF422006)

private val White = Color(0xFFFFFFFF)

/**
 * The light scheme. The page is `background`, a card `surface`, and a card holds a `surfaceVariant` track or
 * field; everything that floats (a menu, a dialog, the bar of the editor) is the white of a card, and only
 * `surfaceContainerHighest`, the fill of a badge, steps down to the track. Its text colours are checked against
 * the surfaces they sit on by `ThemeContrastTest`. `surfaceTint` is the surface itself: the tonal elevation
 * of Material then changes nothing, where the default would tint every raised surface orange.
 */
internal val LightColors = lightColorScheme(
    primary = Orange700,
    onPrimary = White,
    primaryContainer = Orange100,
    onPrimaryContainer = Orange800,
    secondary = Slate600,
    onSecondary = White,
    secondaryContainer = Slate200,
    onSecondaryContainer = Slate900,
    tertiary = Amber700,
    onTertiary = White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Amber900,
    error = Red700,
    onError = White,
    errorContainer = Red50,
    onErrorContainer = Red900,
    background = Slate50,
    onBackground = Slate900,
    surface = White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate300,
    outlineVariant = Slate200,
    scrim = Slate950,
    inverseSurface = Slate900,
    inverseOnSurface = Slate100,
    inversePrimary = Orange300,
    surfaceTint = White,
    surfaceDim = Slate200,
    surfaceBright = White,
    surfaceContainerLowest = White,
    surfaceContainerLow = White,
    surfaceContainer = White,
    surfaceContainerHigh = White,
    surfaceContainerHighest = Slate100,
)

/**
 * The dark scheme. The page is `background`, a card `surface`, and a card holds a `surfaceVariant` track or
 * field, which is the colour of the page. The containers step from the page (`Lowest`) through the card (`Low`)
 * up to `Highest`, the border colour: lighter is higher, as in every dark theme of Material. `primary` is the
 * fill of a button with dark text on it; orange as a text colour is [ExtendedColors.accentText], because the fill
 * is not bright enough to be read on a card.
 */
internal val DarkColors = darkColorScheme(
    primary = Orange600,
    onPrimary = Slate900,
    primaryContainer = Color(0x29FB923C),
    onPrimaryContainer = Orange300,
    secondary = Slate400,
    onSecondary = Slate900,
    secondaryContainer = Slate700,
    onSecondaryContainer = Slate100,
    tertiary = Amber400,
    onTertiary = Slate900,
    tertiaryContainer = Amber950,
    onTertiaryContainer = Amber200,
    error = Red400,
    onError = Slate900,
    errorContainer = Color(0x1FF87171),
    onErrorContainer = Red300,
    background = Slate900,
    onBackground = Slate100,
    surface = Slate800,
    onSurface = Slate100,
    surfaceVariant = Slate900,
    onSurfaceVariant = Slate400,
    outline = Slate600,
    outlineVariant = Slate700,
    scrim = Slate950,
    inverseSurface = Slate100,
    inverseOnSurface = Slate900,
    inversePrimary = Orange700,
    surfaceTint = Slate800,
    surfaceDim = Slate950,
    surfaceBright = Slate700,
    surfaceContainerLowest = Slate900,
    surfaceContainerLow = Slate800,
    surfaceContainer = Color(0xFF253144),
    surfaceContainerHigh = Color(0xFF2C394C),
    surfaceContainerHighest = Slate700,
)

/** A pair of colours for something with text on a fill: a chip, a banner. A translucent [container] is read over the surface it lies on. */
@Immutable
class FilledColors(val container: Color, val content: Color)

/**
 * What Material3 has no role for. [success] is the fill of a success chip or note and [onSuccess] its text, the
 * way `error` and `onError` are a pair; the status chips of the list and of the card are the same kind of pair
 * ([success] is the one of «Рассчитан»). The rail is the dark strip of navigation: it stays dark in the light
 * theme, and the selected item is tinted with the accent. [accentText] is the colour of links and of accented
 * text, which [ColorScheme.primary] is not in the dark theme; [errorText] is the colour of an error message,
 * lighter than [ColorScheme.error] in the dark theme, where `error` is the outline of a field.
 */
@Immutable
class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val statusDraft: FilledColors,
    val statusSent: FilledColors,
    val statusInWork: FilledColors,
    val statusFinished: FilledColors,
    val rail: Color,
    val railBorder: Color,
    val onRail: Color,
    val railSelected: Color,
    val onRailSelected: Color,
    val accentText: Color,
    val errorText: Color,
)

internal val LightExtendedColors = ExtendedColors(
    success = Color(0xFFDCFCE7),
    onSuccess = Color(0xFF166534),
    statusDraft = FilledColors(Slate100, Slate600),
    statusSent = FilledColors(Color(0xFFDBEAFE), Color(0xFF1E40AF)),
    statusInWork = FilledColors(Orange100, Orange800),
    statusFinished = FilledColors(Slate200, Slate700),
    rail = Slate900,
    railBorder = Color.Transparent,
    onRail = Slate400,
    railSelected = Color(0x29EA580C),
    onRailSelected = Orange300,
    accentText = Orange700,
    errorText = Red700,
)

internal val DarkExtendedColors = ExtendedColors(
    success = Color(0x2922C55E),
    onSuccess = Color(0xFF86EFAC),
    statusDraft = FilledColors(Color(0x2994A3B8), Slate300),
    statusSent = FilledColors(Color(0x2960A5FA), Color(0xFF93C5FD)),
    statusInWork = FilledColors(Color(0x29FB923C), Orange300),
    statusFinished = FilledColors(Color(0x3D94A3B8), Slate200),
    rail = Slate950,
    railBorder = Slate800,
    onRail = Slate400,
    railSelected = Color(0x29FB923C),
    onRailSelected = Orange300,
    accentText = Orange300,
    errorText = Red300,
)

private val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> { error("SlavTheme is not installed") }

/** The colours of the theme that Material has no role for. */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

// Material's slots, named for what they carry: a badge 6 dp, a segment of a segmented control 8, a field, a button
// and a message 10, a card 14, a dialog 16.
internal val SlavShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Every gap and padding is a multiple of 8 dp. */
object Spacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}

/** Digits of equal width, so the numbers of a column line up. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

// The idle thumb of a scrollbar is the text colour at this opacity, the thumb under the pointer at the second one.
private const val SCROLLBAR_IDLE_ALPHA = 0.4f
private const val SCROLLBAR_HOVER_ALPHA = 0.7f

/**
 * The scrollbars of both themes. The library's own are black at 12 % and 50 %, which is a thumb on the light
 * surface and nothing at all on the dark one; these follow the text colour of the theme.
 */
internal fun scrollbarStyle(colors: ColorScheme): ScrollbarStyle = defaultScrollbarStyle().copy(
    unhoverColor = colors.onSurface.copy(alpha = SCROLLBAR_IDLE_ALPHA),
    hoverColor = colors.onSurface.copy(alpha = SCROLLBAR_HOVER_ALPHA),
)

/** Application theme; [darkTheme] follows the operating system unless the user chose a theme in the settings. */
@Composable
fun SlavTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = SlavTypography, shapes = SlavShapes) {
        CompositionLocalProvider(
            LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
            LocalScrollbarStyle provides scrollbarStyle(colors),
        ) {
            // Text with no style of its own is the body: 14 sp, not Material's 16.
            ProvideTextStyle(SlavTypography.bodyMedium, content)
        }
    }
}
