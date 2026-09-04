package com.donfluids.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// One accent (teal) used sparingly, one signal colour (coral) for the downbeat and recording.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0EC),
    onPrimaryContainer = Color(0xFF0B3F3B),
    secondary = Color(0xFF0F766E),
    tertiary = Color(0xFFE07A5F),
    onTertiary = Color.White,
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFF0EEE9),
    onSurfaceVariant = Color(0xFF6B6660),
    outline = Color(0xFFD8D4CC),
    outlineVariant = Color(0xFFE8E5DE),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF5EEAD4),
    tertiary = Color(0xFFF2997F),
    onTertiary = Color(0xFF3B1409),
    background = Color(0xFF141516),
    onBackground = Color(0xFFECEAE4),
    surface = Color(0xFF1E2022),
    onSurface = Color(0xFFECEAE4),
    surfaceVariant = Color(0xFF2A2D30),
    onSurfaceVariant = Color(0xFFA19C94),
    outline = Color(0xFF3A3D41),
    outlineVariant = Color(0xFF2E3134),
)

/** Tabular figures so a changing BPM does not shift in width. */
const val Tabular = "tnum"

private val LiveKeyTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LiveKeyTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = LiveKeyTypography,
        content = content,
    )
}
