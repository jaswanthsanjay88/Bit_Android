package com.bit.ui.theme

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
 * Centralized glassmorphic palette.
 * All glass surfaces, borders, and highlights reference these tokens
 * instead of inline Color(...).copy(alpha = ...) calls.
 */
object Glass {
    // ── Surfaces ──
    /** Primary glass surface — cards, overlays, modals (Opaque Dark Obsidian) */
    val Surface = Color(0xFA0F0F12)
    /** Elevated glass surface — active cards, sheets */
    val SurfaceElevated = Color(0xFC121216)
    /** Subtle glass surface — inactive chips, background layers */
    val SurfaceSubtle = Color(0xF208080B)
    /** Medium glass surface — hover states, secondary panels */
    val SurfaceMedium = Color(0xFC0D0D10)

    // ── Borders ──
    /** Standard glass border */
    val Border = Color(0x26FFFFFF)              // ~15% white
    /** Subtle glass border — cards, sections */
    val BorderSubtle = Color(0x1AFFFFFF)        // ~10% white
    /** Active/focused border */
    val BorderActive = Color(0x40FFFFFF)        // ~25% white

    // ── Accent Colors ──
    /** Primary accent — white */
    val AccentPrimary = Color(0xFFFFFFFF)       // Pure White
    /** Secondary accent — silver */
    val AccentSecondary = Color(0xFFE5E5E5)     // Silver
    /** Tertiary accent — neutral gray */
    val AccentTertiary = Color(0xFFD4D4D4)      // Neutral Gray
    /** Warm accent — white */
    val AccentWarm = Color(0xFFFFFFFF)          // Pure White

    // ── Accent Surfaces (low alpha for backgrounds) ──
    val AccentPrimarySurface = Color(0x26FFFFFF)    // ~15% white
    val AccentSecondarySurface = Color(0x1AFFFFFF)  // ~10% white
    val AccentTertiarySurface = Color(0x1AFFFFFF)   // ~10% white
    val AccentWarmSurface = Color(0x1AFFFFFF)       // ~10% white

    // ── Semantic Status Colors (Monochrome Scale) ──
    val StatusSuccess = Color(0xFFFFFFFF)       // White
    val StatusWarning = Color(0xFFBDBDBD)       // Muted Gray
    val StatusError = Color(0xFF8E8E8E)         // Darker Gray
    val StatusInfo = Color(0xFFD0D0D0)          // Light Gray

    val StatusSuccessSurface = Color(0x1AFFFFFF)
    val StatusWarningSurface = Color(0x0DFFFFFF)
    val StatusErrorSurface = Color(0x26FFFFFF)
    val StatusInfoSurface = Color(0x14FFFFFF)

    // ── Text on Glass ──
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFBDBDBD)
    val TextMuted = Color(0xFF8E8E8E)
    val TextOnAccent = Color(0xFF0A0A0A)

    // ── Scrim & Overlay (Increased background blocking) ──
    val Scrim = Color(0xF2000000)               // 95% black
    val ScrimLight = Color(0xD9000000)          // 85% black

    // ── Divider ──
    val Divider = Color(0x1AFFFFFF)             // ~10% white
    val DividerStrong = Color(0x33FFFFFF)       // ~20% white
}
