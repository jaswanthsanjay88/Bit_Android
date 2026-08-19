package com.bit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Carbon Monochrome Palette ────────────────────────────────────────────────

// Core dark layers
val CarbonBlack = Color(0xFF000000)
val CarbonSurface = Color(0xFF121212)
val CarbonSurfaceHigh = Color(0xFF1E1E1E)
val CarbonSurfaceHighest = Color(0xFF262626)

// Neutral accents (white/gray only)
val MonoPrimary = Color(0xFFF5F5F5)
val MonoPrimaryDim = Color(0xFFE0E0E0)
val MonoSecondary = Color(0xFFBDBDBD)

// Functional colors
val MonoError = Color(0xFFFF6E6E)
val MonoWarning = Color(0xFFFFCA6B)

// Typography colors
val CarbonTextPrimary = Color(0xFFFFFFFF)
val CarbonTextSecondary = Color(0xFFD0D0D0)
val CarbonTextDisabled = Color(0xFF8E8E8E)
val CarbonTextOnPrimary = Color(0xFF111111)

// ── Glassmorphic Design Tokens ───────────────────────────────────────────────

/**
 * Centralized glassmorphic palette tokens supporting light and dark themes.
 */
data class GlassTokens(
    val Surface: Color,
    val SurfaceElevated: Color,
    val SurfaceSubtle: Color,
    val SurfaceMedium: Color,
    val Border: Color,
    val BorderSubtle: Color,
    val BorderActive: Color,
    val AccentPrimary: Color,
    val AccentSecondary: Color,
    val AccentTertiary: Color,
    val AccentWarm: Color,
    val AccentPrimarySurface: Color,
    val AccentSecondarySurface: Color,
    val AccentTertiarySurface: Color,
    val AccentWarmSurface: Color,
    val StatusSuccess: Color,
    val StatusWarning: Color,
    val StatusError: Color,
    val StatusInfo: Color,
    val StatusSuccessSurface: Color,
    val StatusWarningSurface: Color,
    val StatusErrorSurface: Color,
    val StatusInfoSurface: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextMuted: Color,
    val TextOnAccent: Color,
    val Scrim: Color,
    val ScrimLight: Color,
    val Divider: Color,
    val DividerStrong: Color
)

val DarkGlassTokens = GlassTokens(
    Surface = Color(0xFA0F0F12),
    SurfaceElevated = Color(0xFC121216),
    SurfaceSubtle = Color(0xF208080B),
    SurfaceMedium = Color(0xFC0D0D10),
    Border = Color(0x26FFFFFF),
    BorderSubtle = Color(0x1AFFFFFF),
    BorderActive = Color(0x40FFFFFF),
    AccentPrimary = Color(0xFFFFFFFF),
    AccentSecondary = Color(0xFFE5E5E5),
    AccentTertiary = Color(0xFFD4D4D4),
    AccentWarm = Color(0xFFFFFFFF),
    AccentPrimarySurface = Color(0x26FFFFFF),
    AccentSecondarySurface = Color(0x1AFFFFFF),
    AccentTertiarySurface = Color(0x1AFFFFFF),
    AccentWarmSurface = Color(0x1AFFFFFF),
    StatusSuccess = Color(0xFFFFFFFF),
    StatusWarning = Color(0xFFBDBDBD),
    StatusError = Color(0xFF8E8E8E),
    StatusInfo = Color(0xFFD0D0D0),
    StatusSuccessSurface = Color(0x1AFFFFFF),
    StatusWarningSurface = Color(0x0DFFFFFF),
    StatusErrorSurface = Color(0x26FFFFFF),
    StatusInfoSurface = Color(0x14FFFFFF),
    TextPrimary = Color(0xFFF5F5F5),
    TextSecondary = Color(0xFFBDBDBD),
    TextMuted = Color(0xFF8E8E8E),
    TextOnAccent = Color(0xFF0A0A0A),
    Scrim = Color(0xF2000000),
    ScrimLight = Color(0xD9000000),
    Divider = Color(0x1AFFFFFF),
    DividerStrong = Color(0x33FFFFFF)
)

val LightGlassTokens = GlassTokens(
    Surface = Color(0xF2F2F2F6),
    SurfaceElevated = Color(0xFFFFFFFF),
    SurfaceSubtle = Color(0xEAEAECEF),
    SurfaceMedium = Color(0xF0E8E8ED),
    Border = Color(0x1F000000),
    BorderSubtle = Color(0x14000000),
    BorderActive = Color(0x3D000000),
    AccentPrimary = Color(0xFF1C1B1F),
    AccentSecondary = Color(0xFF49454F),
    AccentTertiary = Color(0xFF79747E),
    AccentWarm = Color(0xFF1C1B1F),
    AccentPrimarySurface = Color(0x14000000),
    AccentSecondarySurface = Color(0x0F000000),
    AccentTertiarySurface = Color(0x0A000000),
    AccentWarmSurface = Color(0x14000000),
    StatusSuccess = Color(0xFF107C41),
    StatusWarning = Color(0xFF8A5100),
    StatusError = Color(0xFFB3261E),
    StatusInfo = Color(0xFF005691),
    StatusSuccessSurface = Color(0x1A107C41),
    StatusWarningSurface = Color(0x1A8A5100),
    StatusErrorSurface = Color(0x1AB3261E),
    StatusInfoSurface = Color(0x1A005691),
    TextPrimary = Color(0xFF1C1B1F),
    TextSecondary = Color(0xFF49454F),
    TextMuted = Color(0xFF79747E),
    TextOnAccent = Color(0xFFFFFFFF),
    Scrim = Color(0x66000000),
    ScrimLight = Color(0x40000000),
    Divider = Color(0x14000000),
    DividerStrong = Color(0x29000000)
)

object Glass {
    val Surface: Color @Composable get() = LocalGlass.current.Surface
    val SurfaceElevated: Color @Composable get() = LocalGlass.current.SurfaceElevated
    val SurfaceSubtle: Color @Composable get() = LocalGlass.current.SurfaceSubtle
    val SurfaceMedium: Color @Composable get() = LocalGlass.current.SurfaceMedium

    val Border: Color @Composable get() = LocalGlass.current.Border
    val BorderSubtle: Color @Composable get() = LocalGlass.current.BorderSubtle
    val BorderActive: Color @Composable get() = LocalGlass.current.BorderActive

    val AccentPrimary: Color @Composable get() = LocalGlass.current.AccentPrimary
    val AccentSecondary: Color @Composable get() = LocalGlass.current.AccentSecondary
    val AccentTertiary: Color @Composable get() = LocalGlass.current.AccentTertiary
    val AccentWarm: Color @Composable get() = LocalGlass.current.AccentWarm

    val AccentPrimarySurface: Color @Composable get() = LocalGlass.current.AccentPrimarySurface
    val AccentSecondarySurface: Color @Composable get() = LocalGlass.current.AccentSecondarySurface
    val AccentTertiarySurface: Color @Composable get() = LocalGlass.current.AccentTertiarySurface
    val AccentWarmSurface: Color @Composable get() = LocalGlass.current.AccentWarmSurface

    val StatusSuccess: Color @Composable get() = LocalGlass.current.StatusSuccess
    val StatusWarning: Color @Composable get() = LocalGlass.current.StatusWarning
    val StatusError: Color @Composable get() = LocalGlass.current.StatusError
    val StatusInfo: Color @Composable get() = LocalGlass.current.StatusInfo

    val StatusSuccessSurface: Color @Composable get() = LocalGlass.current.StatusSuccessSurface
    val StatusWarningSurface: Color @Composable get() = LocalGlass.current.StatusWarningSurface
    val StatusErrorSurface: Color @Composable get() = LocalGlass.current.StatusErrorSurface
    val StatusInfoSurface: Color @Composable get() = LocalGlass.current.StatusInfoSurface

    val TextPrimary: Color @Composable get() = LocalGlass.current.TextPrimary
    val TextSecondary: Color @Composable get() = LocalGlass.current.TextSecondary
    val TextMuted: Color @Composable get() = LocalGlass.current.TextMuted
    val TextOnAccent: Color @Composable get() = LocalGlass.current.TextOnAccent

    val Scrim: Color @Composable get() = LocalGlass.current.Scrim
    val ScrimLight: Color @Composable get() = LocalGlass.current.ScrimLight

    val Divider: Color @Composable get() = LocalGlass.current.Divider
    val DividerStrong: Color @Composable get() = LocalGlass.current.DividerStrong
}

// ── Strict Monochrome Color Palette for BIT ──
object BitColors {
    val Background = Color(0xFF000000)      // true black, OLED-friendly
    val Surface     = Color(0xFF0D0D0D)      // cards, sheets
    val SurfaceAlt  = Color(0xFF1A1A1A)      // pressed/selected state
    val Border      = Color(0xFF262626)      // hairlines only
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF8C8C8C)
    val TextTertiary  = Color(0xFF5C5C5C)
    val Inverse     = Color(0xFFFFFFFF)      // primary CTA fill
    val OnInverse   = Color(0xFF000000)      // text on white CTA
}

