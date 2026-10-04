package dev.mohak.scrinium.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mohak.scrinium.R

/**
 * Design tokens, ported from the web's src/lib/design/theme.css. Surfaces are
 * true black with a few lifted steps; the accent has two roles: [Sc.accent]
 * is ink for text, icons and thin lines, [Sc.fill] is the solid brand green
 * under white text. Never fill with [Sc.accent] under text.
 */
object Sc {
    val bg = Color(0xFF000000)
    val raise = Color(0xFF0D0D0D)
    val hover = Color(0xFF161616)
    val press = Color(0xFF1C1C1C)
    val line = Color(0xFF1A1A1A)
    val line2 = Color(0xFF262626)
    val line3 = Color(0xFF333333)

    val text = Color(0xFFE8E6E3)
    val text2 = Color(0xFFA8A5A0)
    val text3 = Color(0xFF6C6965)
    val text4 = Color(0xFF3D3B39)

    val accent = Color(0xFF5DB8AA)
    val fill = Color(0xFF175E54)
    val onFill = Color.White

    val red = Color(0xFFFF7A85)
    val redFill = Color(0xFF3A1418)
    val orange = Color(0xFFF0A35E)
    val yellow = Color(0xFFE8C872)
    val green = Color(0xFF9FD4A3)
    val blue = Color(0xFF8FB3FF)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int, style: FontStyle = FontStyle.Normal) = Font(
    res,
    FontWeight(weight),
    style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/** Space Grotesk: the UI face. */
val UiFont = FontFamily(
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 600)
)

/** Atkinson Hyperlegible Next: note text, in the editor and the preview. */
val ReadFont = FontFamily(
    variable(R.font.atkinson_next, 400),
    variable(R.font.atkinson_next, 600),
    variable(R.font.atkinson_next, 700),
    variable(R.font.atkinson_next_italic, 400, FontStyle.Italic),
    variable(R.font.atkinson_next_italic, 600, FontStyle.Italic)
)

/** JetBrains Mono: code, counts and timestamps. */
val MonoFont = FontFamily(
    variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 500)
)

/** Shared text styles beyond Material's slots. */
object Type {
    val body = TextStyle(fontFamily = UiFont, fontSize = 14.sp, lineHeight = 20.sp, color = Sc.text)
    val meta = TextStyle(fontFamily = UiFont, fontSize = 12.sp, lineHeight = 16.sp, color = Sc.text3)
    val mono = TextStyle(fontFamily = MonoFont, fontSize = 11.5.sp, lineHeight = 16.sp, color = Sc.text3)
    val title = TextStyle(fontFamily = UiFont, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = Sc.text)
    val group = TextStyle(fontFamily = UiFont, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Sc.text)
    val read = TextStyle(fontFamily = ReadFont, fontSize = 16.sp, lineHeight = 25.6.sp, color = Sc.text)
}

// Material components still read the scheme. primary is the fill (buttons,
// switches, pickers put white text on it); secondary is the accent ink.
private val ScriniumColors = darkColorScheme(
    primary = Sc.fill,
    onPrimary = Sc.onFill,
    primaryContainer = Sc.fill,
    onPrimaryContainer = Sc.onFill,
    secondary = Sc.accent,
    onSecondary = Sc.bg,
    secondaryContainer = Sc.press,
    onSecondaryContainer = Sc.text,
    tertiary = Sc.orange,
    onTertiary = Sc.bg,
    error = Sc.red,
    onError = Sc.bg,
    errorContainer = Sc.redFill,
    onErrorContainer = Sc.red,
    background = Sc.bg,
    onBackground = Sc.text,
    surface = Sc.bg,
    onSurface = Sc.text,
    surfaceVariant = Sc.press,
    onSurfaceVariant = Sc.text2,
    outline = Sc.text3,
    outlineVariant = Sc.line2,
    surfaceContainerLowest = Sc.bg,
    surfaceContainerLow = Sc.raise,
    surfaceContainer = Sc.raise,
    surfaceContainerHigh = Sc.hover,
    surfaceContainerHighest = Sc.press,
    surfaceTint = Color.Transparent,
    scrim = Color.Black
)

private val ScriniumType = Typography().let { t ->
    fun TextStyle.ui() = copy(fontFamily = UiFont)
    Typography(
        displayLarge = t.displayLarge.ui(),
        displayMedium = t.displayMedium.ui(),
        displaySmall = t.displaySmall.ui(),
        headlineLarge = t.headlineLarge.ui(),
        headlineMedium = t.headlineMedium.ui(),
        headlineSmall = t.headlineSmall.ui(),
        titleLarge = t.titleLarge.ui(),
        titleMedium = t.titleMedium.ui(),
        titleSmall = t.titleSmall.ui(),
        bodyLarge = t.bodyLarge.ui().copy(fontSize = 15.sp),
        bodyMedium = t.bodyMedium.ui(),
        bodySmall = t.bodySmall.ui(),
        labelLarge = t.labelLarge.ui(),
        labelMedium = t.labelMedium.ui(),
        labelSmall = t.labelSmall.ui()
    )
}

@Composable
fun ScriniumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScriniumColors, typography = ScriniumType, content = content)
}
