package com.donfluids.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFF2C14E)
val Teal = Color(0xFF3EC7B0)
val Coral = Color(0xFFF07167)

private val DarkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF2B1F00),
    secondary = Teal,
    onSecondary = Color(0xFF00201A),
    tertiary = Coral,
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF1A2027),
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF242C35),
    onSurfaceVariant = Color(0xFFB4BCC6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF7A5A00),
    onPrimary = Color.White,
    secondary = Color(0xFF00695C),
    onSecondary = Color.White,
    tertiary = Color(0xFFB3261E),
    background = Color(0xFFF7F6F2),
    onBackground = Color(0xFF1B1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFEDEBE4),
    onSurfaceVariant = Color(0xFF4A4D52),
)

@Composable
fun LiveKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
