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
    primary = MonoPrimary,
    onPrimary = CarbonTextOnPrimary,
    primaryContainer = CarbonSurfaceHighest,
    onPrimaryContainer = CarbonTextPrimary,
    secondary = MonoSecondary,
    onSecondary = CarbonTextOnPrimary,
    secondaryContainer = CarbonSurfaceHigh,
    onSecondaryContainer = CarbonTextPrimary,
    tertiary = MonoSecondary,
    onTertiary = CarbonTextOnPrimary,
    tertiaryContainer = CarbonSurfaceHigh,
    onTertiaryContainer = CarbonTextPrimary,

    background = CarbonBlack,
    onBackground = CarbonTextPrimary,

    surface = CarbonSurface,
    onSurface = CarbonTextPrimary,
    surfaceVariant = CarbonSurfaceHigh,
    onSurfaceVariant = CarbonTextSecondary,
    surfaceTint = MonoPrimary,
    inverseSurface = Color(0xFFD8EAF5),
    inverseOnSurface = Color(0xFF0E161C),
    outline = Color(0xFF666666),
    outlineVariant = Color(0xFF3D3D3D),
    scrim = Color(0xFF000000),

    error = MonoError,
    onError = CarbonTextPrimary,
    errorContainer = Color(0xFF351717),
    onErrorContainer = Color(0xFFFFC8C8),

    // Kept for legacy UI references that use info as tertiary-like accent
    inversePrimary = MonoPrimaryDim
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
