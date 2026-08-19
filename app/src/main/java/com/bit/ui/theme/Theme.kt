package com.bit.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.data.AppSettingsDataStore

val LocalGlass = androidx.compose.runtime.compositionLocalOf { DarkGlassTokens }
val LocalDarkMode = compositionLocalOf { true }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NeuroVerseTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dataStore = remember(context) { AppSettingsDataStore(context.applicationContext) }

    val colorModeStr by dataStore.colorMode.collectAsStateWithLifecycle(initialValue = "SYSTEM")
    val dynamicColorEnabled by dataStore.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val themePresetId by dataStore.themePresetId.collectAsStateWithLifecycle(initialValue = "obsidian")
    val fontFamilyStr by dataStore.fontFamily.collectAsStateWithLifecycle(initialValue = "MANROPE")
    val customFontPath by dataStore.customFontPath.collectAsStateWithLifecycle(initialValue = "")
    val fontScale by dataStore.fontScale.collectAsStateWithLifecycle(initialValue = 1.0f)

    val systemIsDark = isSystemInDarkTheme()
    val colorMode = remember(colorModeStr) {
        runCatching { ColorMode.valueOf(colorModeStr) }.getOrDefault(ColorMode.SYSTEM)
    }

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> systemIsDark
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val preset = findBitPresetTheme(themePresetId)
            if (darkTheme) preset.darkScheme else preset.lightScheme
        }
    }

    val builtinFont = remember(fontFamilyStr) {
        runCatching { BuiltinFont.valueOf(fontFamilyStr) }.getOrDefault(BuiltinFont.MANROPE)
    }
    val resolvedFamily = remember(builtinFont, customFontPath) {
        resolveFontFamily(builtinFont, customFontPath)
    }
    val typography = remember(resolvedFamily, fontScale) {
        createBitTypography(resolvedFamily, fontScale)
    }

    // Update status bar & navigation bar appearance
    val view = LocalView.current
    if (!view.isInEditMode && view.context is Activity) {
        val window = (view.context as Activity).window
        val statusBarColor = colorScheme.surface
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = statusBarColor.luminance() > 0.5f
                isAppearanceLightNavigationBars = !darkTheme
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = statusBarColor.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.surface.toArgb()
        }
    }

    CompositionLocalProvider(
        LocalGlass provides if (darkTheme) DarkGlassTokens else LightGlassTokens,
        LocalDarkMode provides darkTheme
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = BitShapes,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
