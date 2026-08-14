package dev.mohak.scrinium.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Background = Color(0xFF121318)
private val SurfaceLowest = Color(0xFF0D0E13)
private val SurfaceLow = Color(0xFF1A1B20)
private val Surface = Color(0xFF1E1F25)
private val SurfaceHigh = Color(0xFF292A2F)
private val SurfaceHighest = Color(0xFF34343A)
private val OnSurface = Color(0xFFE3E1E9)
private val OnSurfaceVariant = Color(0xFFC3C5D7)
private val Outline = Color(0xFF8D90A0)
private val OutlineVariant = Color(0xFF434654)
private val Primary = Color(0xFFB5C4FF)
private val OnPrimary = Color(0xFF00297B)
private val PrimaryContainer = Color(0xFF648AFF)
private val OnPrimaryContainer = Color(0xFF00236D)
private val Secondary = Color(0xFFC1C4E4)
private val OnSecondary = Color(0xFF2B2F47)
private val SecondaryContainer = Color(0xFF41455F)
private val OnSecondaryContainer = Color(0xFFB0B3D2)
private val Tertiary = Color(0xFFFFB786)
private val OnTertiary = Color(0xFF502400)
private val Error = Color(0xFFFFB4AB)
private val OnError = Color(0xFF690005)
private val ErrorContainer = Color(0xFF93000A)
private val OnErrorContainer = Color(0xFFFFDAD6)

private val ScriniumColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceHighest,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    surfaceContainerLowest = SurfaceLowest,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest
)

@Composable
fun ScriniumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScriniumColors, content = content)
}