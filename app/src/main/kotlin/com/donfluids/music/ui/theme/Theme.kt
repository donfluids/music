package com.donfluids.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.donfluids.music.R

// One accent (teal) used sparingly, one signal colour (coral) for the downbeat and recording.
// Light values were checked against WCAG: coral on the background 5.0:1, outline 3.4:1.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0EC),
    onPrimaryContainer = Color(0xFF0B3F3B),
    secondary = Color(0xFF0F766E),
    // Warm neutrals for tonal buttons, the tap box, dialogs and menus. Left unset, Material's
    // default lavender shows through, which belongs to no part of this palette.
    secondaryContainer = Color(0xFFE6E2D9),
    onSecondaryContainer = Color(0xFF1C1B19),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4F0),
    surfaceContainer = Color(0xFFF2EFEA),
    surfaceContainerHigh = Color(0xFFEDEAE4),
    surfaceContainerHighest = Color(0xFFE7E4DD),
    tertiary = Color(0xFFB8492A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE3DC),
    onTertiaryContainer = Color(0xFF7A2B14),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFDCD8CF),
    onSurfaceVariant = Color(0xFF5E5953),
    outline = Color(0xFF8C877F),
    outlineVariant = Color(0xFFE8E5DE),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF5EEAD4),
    secondaryContainer = Color(0xFF2F3437),
    onSecondaryContainer = Color(0xFFECEAE4),
    surfaceContainerLowest = Color(0xFF0F1011),
    surfaceContainerLow = Color(0xFF1A1C1D),
    surfaceContainer = Color(0xFF1E2022),
    surfaceContainerHigh = Color(0xFF25282A),
    surfaceContainerHighest = Color(0xFF2C3033),
    tertiary = Color(0xFFF2997F),
    onTertiary = Color(0xFF3B1409),
    tertiaryContainer = Color(0xFF5C2A1A),
    onTertiaryContainer = Color(0xFFFFD9CE),
    background = Color(0xFF141516),
    onBackground = Color(0xFFECEAE4),
    surface = Color(0xFF1E2022),
    onSurface = Color(0xFFECEAE4),
    surfaceVariant = Color(0xFF2E3235),
    onSurfaceVariant = Color(0xFFA8A39B),
    outline = Color(0xFF6B7076),
    outlineVariant = Color(0xFF2E3134),
)

/** Tabular figures so a changing BPM does not shift in width. */
const val Tabular = "tnum"

private val LiveKeyTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * Manrope, bundled so the app looks the same on every phone. SIL Open Font License 1.1; the
 * licence ships in assets/licenses and is listed in THIRD_PARTY_NOTICES.md. Trimmed to the
 * characters the app uses (tools/fonts/README.md says how).
 */
private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
)

/** Every text style in Manrope. */
private val AppTypography: Typography = LiveKeyTypography.run {
    fun TextStyle.inManrope() = copy(fontFamily = Manrope)
    Typography(
        displayLarge = displayLarge.inManrope(), displayMedium = displayMedium.inManrope(), displaySmall = displaySmall.inManrope(),
        headlineLarge = headlineLarge.inManrope(), headlineMedium = headlineMedium.inManrope(), headlineSmall = headlineSmall.inManrope(),
        titleLarge = titleLarge.inManrope(), titleMedium = titleMedium.inManrope(), titleSmall = titleSmall.inManrope(),
        bodyLarge = bodyLarge.inManrope(), bodyMedium = bodyMedium.inManrope(), bodySmall = bodySmall.inManrope(),
        labelLarge = labelLarge.inManrope(), labelMedium = labelMedium.inManrope(), labelSmall = labelSmall.inManrope(),
    )
}

@Composable
fun LiveKeyTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
