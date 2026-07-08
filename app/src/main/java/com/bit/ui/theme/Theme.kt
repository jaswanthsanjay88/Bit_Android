package com.bit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Obsidian Kernel Color Scheme ───────────────────────────

private val ObsidianColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), // Pure White
    onPrimary = Color(0xFF000000), // Pure Black
    primaryContainer = Color(0xFF262626), // CarbonSurfaceHighest
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFE5E5E5), // Silver
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1E1E1E), // CarbonSurfaceHigh
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFFD4D4D4), // Neutral Gray
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF1E1E1E),
    onTertiaryContainer = Color(0xFFD4D4D4),

    background = Color(0xFF000000), // OLED Black
    onBackground = Color(0xFFFFFFFF),

    surface = Color(0xFF121212), // Dark Surface
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFD0D0D0),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFE5E5E5),
    inverseOnSurface = Color(0xFF121212),
    outline = Color(0xFF666666),
    outlineVariant = Color(0xFF3D3D3D),
    scrim = Color(0xFF000000),

    error = Color(0xFFFF6E6E),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF351717),
    onErrorContainer = Color(0xFFFFC8C8),

    inversePrimary = Color(0xFFE0E0E0)
)

// ── Typography ──
// Single instance with Manrope applied to all text styles.

private val ManropeTypography: Typography by lazy {
    val base = Typography()
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = ManropeFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = ManropeFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = ManropeFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = ManropeFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = ManropeFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = ManropeFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = ManropeFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = ManropeFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = ManropeFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = ManropeFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = ManropeFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = ManropeFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = ManropeFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = ManropeFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = ManropeFontFamily),
    )
}

val LocalGlass = androidx.compose.runtime.staticCompositionLocalOf { Glass }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NeuroVerseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // UI redesign requirement: keep the experience consistently dark.
    val colorScheme = ObsidianColorScheme

    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlass provides Glass
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = ManropeTypography,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
