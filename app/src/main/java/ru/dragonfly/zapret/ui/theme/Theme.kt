package ru.dragonfly.zapret.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4DD0A7)
private val AccentDark = Color(0xFF12856A)
private val Danger = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201A),
    primaryContainer = AccentDark,
    onPrimaryContainer = Color(0xFFCDFFEE),
    secondary = Color(0xFF7FC7FF),
    background = Color(0xFF101315),
    onBackground = Color(0xFFE6EAEC),
    surface = Color(0xFF161A1D),
    onSurface = Color(0xFFE6EAEC),
    surfaceVariant = Color(0xFF1F2529),
    onSurfaceVariant = Color(0xFFBFC8CC),
    error = Danger,
    outline = Color(0xFF3A4348)
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF00658F),
    error = Color(0xFFBA1A1A)
)

@Composable
fun ZapretTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
