package com.newsradar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.newsradar.app.prefs.ColorScheme
import com.newsradar.app.prefs.ThemeMode

/** Rating colours are constant across all palettes so meaning never changes. */
val RatingGreen = Color(0xFF2E7D32)
val RatingAmber = Color(0xFFF9A825)
val RatingRed = Color(0xFFC62828)

private data class Palette(val primary: Color, val secondary: Color, val tertiary: Color)

private val palettes = mapOf(
    ColorScheme.BLUE to Palette(Color(0xFF1565C0), Color(0xFF42A5F5), Color(0xFF0D47A1)),
    ColorScheme.TEAL to Palette(Color(0xFF00796B), Color(0xFF26A69A), Color(0xFF004D40)),
    ColorScheme.PURPLE to Palette(Color(0xFF6A1B9A), Color(0xFFAB47BC), Color(0xFF4A148C)),
    ColorScheme.SUNSET to Palette(Color(0xFFE65100), Color(0xFFFF7043), Color(0xFFBF360C)),
    ColorScheme.FOREST to Palette(Color(0xFF2E7D32), Color(0xFF66BB6A), Color(0xFF1B5E20)),
    ColorScheme.MONO to Palette(Color(0xFF37474F), Color(0xFF78909C), Color(0xFF263238))
)

@Composable
fun NewsRadarTheme(
    themeMode: ThemeMode,
    colorScheme: ColorScheme,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = palettes[colorScheme] ?: palettes.getValue(ColorScheme.TEAL)

    // "No Noise" surfaces (brand brief): deep charcoal in dark, paper white in
    // light, with a subtle surface step for cards so we can drop borders/dividers
    // and lean entirely on whitespace + surface contrast.
    val colors = if (dark) {
        darkColorScheme(
            primary = p.secondary,
            secondary = p.primary,
            tertiary = p.tertiary,
            background = Color(0xFF0A0A0A),
            surface = Color(0xFF141414),
            surfaceVariant = Color(0xFF1E1E1E),
            onBackground = Color(0xFFEDEDED),
            onSurface = Color(0xFFEDEDED),
            onSurfaceVariant = Color(0xFFB0B0B0)
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFFAFAFA),
            surfaceVariant = Color(0xFFF0F0F0),
            onBackground = Color(0xFF111111),
            onSurface = Color(0xFF111111),
            onSurfaceVariant = Color(0xFF555555)
        )
    }

    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
