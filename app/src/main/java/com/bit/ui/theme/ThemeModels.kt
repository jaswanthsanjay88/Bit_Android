package com.bit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.bit.R

enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class BuiltinFont(val displayName: String) {
    SYSTEM("System Default"),
    MANROPE("Manrope (Modern)"),
    GOOGLE_SANS("Google Sans"),
    MAPLE_MONO("Maple Mono"),
    GOOGLE_SANS_CODE("Google Sans Code"),
    CUSTOM("Custom Font (.ttf/.otf)")
}

data class PresetTheme(
    val id: String,
    val name: String,
    val primaryColor: Color,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme
)

// ── 1. Obsidian Stealth (OLED Pure Black & Carbon) ───────────
private val ObsidianDark = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFE5E5E5),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFFD4D4D4),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF1E1E1E),
    onTertiaryContainer = Color(0xFFD4D4D4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFD0D0D0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2C2C2C),
    outline = Color(0xFF555555),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF000000)
)

private val ObsidianLight = lightColorScheme(
    primary = Color(0xFF18181B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF3F3F46),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF27272A),
    tertiary = Color(0xFF52525B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F9),
    surfaceContainer = Color(0xFFF1F1F3),
    surfaceContainerHigh = Color(0xFFEAEAED),
    surfaceContainerHighest = Color(0xFFE2E2E6),
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

// ── 2. Seafoam Mint (Teal / Emerald) ──────────────────────────
private val SeafoamMintDark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF7CF4E0),
    secondary = Color(0xFF99F6E4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF134E48),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF6EE7B7),
    onTertiary = Color(0xFF003825),
    background = Color(0xFF061412),
    onBackground = Color(0xFFE6FAF6),
    surface = Color(0xFF0B1F1C),
    onSurface = Color(0xFFE6FAF6),
    surfaceVariant = Color(0xFF1A332F),
    onSurfaceVariant = Color(0xFFA7C5BF),
    surfaceContainerLowest = Color(0xFF040E0D),
    surfaceContainerLow = Color(0xFF0F2623),
    surfaceContainer = Color(0xFF142E2A),
    surfaceContainerHigh = Color(0xFF1B3B36),
    surfaceContainerHighest = Color(0xFF234741),
    outline = Color(0xFF5F827C),
    outlineVariant = Color(0xFF294541),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val SeafoamMintLight = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2F1),
    onSecondaryContainer = Color(0xFF00332C),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF2FBF9),
    onBackground = Color(0xFF0D1E1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1E1B),
    surfaceVariant = Color(0xFFE0ECE9),
    onSurfaceVariant = Color(0xFF405350),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F9F7),
    surfaceContainer = Color(0xFFE6F4F1),
    surfaceContainerHigh = Color(0xFFDCEDE9),
    surfaceContainerHighest = Color(0xFFD3E6E2),
    outline = Color(0xFF708985),
    outlineVariant = Color(0xFFC0D8D4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 3. Ocean Cobalt (Deep Sapphire / Electric Blue) ───────────
private val OceanDark = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF002F6C),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF002F6C),
    secondaryContainer = Color(0xFF1E40AF),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF00354E),
    background = Color(0xFF080F1D),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF050914),
    surfaceContainerLow = Color(0xFF131D33),
    surfaceContainer = Color(0xFF17233D),
    surfaceContainerHigh = Color(0xFF1E2C4B),
    surfaceContainerHighest = Color(0xFF26375C),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF26334A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val OceanLight = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF1D4ED8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = Color(0xFF172554),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFE2E8F0),
    surfaceContainerHigh = Color(0xFFCBD5E1),
    surfaceContainerHighest = Color(0xFF94A3B8),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 4. Sakura Blossom (Rose Pink & Warm Blossom) ─────────────
private val SakuraDark = darkColorScheme(
    primary = Color(0xFFF472B6),
    onPrimary = Color(0xFF500724),
    primaryContainer = Color(0xFF831843),
    onPrimaryContainer = Color(0xFFFCE7F3),
    secondary = Color(0xFFFB7185),
    onSecondary = Color(0xFF4C0519),
    secondaryContainer = Color(0xFF881337),
    onSecondaryContainer = Color(0xFFFFE4E6),
    tertiary = Color(0xFFE879F9),
    onTertiary = Color(0xFF4A044E),
    background = Color(0xFF180A12),
    onBackground = Color(0xFFFDE8F1),
    surface = Color(0xFF22101B),
    onSurface = Color(0xFFFDE8F1),
    surfaceVariant = Color(0xFF3B1C2F),
    onSurfaceVariant = Color(0xFFD4A5BE),
    surfaceContainerLowest = Color(0xFF12050D),
    surfaceContainerLow = Color(0xFF281320),
    surfaceContainer = Color(0xFF301727),
    surfaceContainerHigh = Color(0xFF3B1E31),
    surfaceContainerHighest = Color(0xFF46253B),
    outline = Color(0xFF8E5975),
    outlineVariant = Color(0xFF462338),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val SakuraLight = lightColorScheme(
    primary = Color(0xFFDB2777),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFCE7F3),
    onPrimaryContainer = Color(0xFF700A38),
    secondary = Color(0xFFBE185D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFF1F2),
    onSecondaryContainer = Color(0xFF5C0724),
    tertiary = Color(0xFFC026D3),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDF4F8),
    onBackground = Color(0xFF240E1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF240E1B),
    surfaceVariant = Color(0xFFF5DCE7),
    onSurfaceVariant = Color(0xFF624352),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCEDF4),
    surfaceContainer = Color(0xFFF7E2EC),
    surfaceContainerHigh = Color(0xFFF2D6E3),
    surfaceContainerHighest = Color(0xFFEBCADA),
    outline = Color(0xFF9E7287),
    outlineVariant = Color(0xFFE2B7CC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 5. Sunset Amber (Warm Gold & Sunset Orange) ──────────────
private val SunsetAmberDark = darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF451A03),
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = Color(0xFFFEF3C7),
    secondary = Color(0xFFFB923C),
    onSecondary = Color(0xFF431407),
    secondaryContainer = Color(0xFF7C2D12),
    onSecondaryContainer = Color(0xFFFFEDD5),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color(0xFF451A03),
    background = Color(0xFF170E04),
    onBackground = Color(0xFFFEF3C7),
    surface = Color(0xFF231608),
    onSurface = Color(0xFFFEF3C7),
    surfaceVariant = Color(0xFF382512),
    onSurfaceVariant = Color(0xFFD4B99D),
    surfaceContainerLowest = Color(0xFF100902),
    surfaceContainerLow = Color(0xFF2B1C0B),
    surfaceContainer = Color(0xFF342310),
    surfaceContainerHigh = Color(0xFF3F2B16),
    surfaceContainerHighest = Color(0xFF4C351E),
    outline = Color(0xFF8F7050),
    outlineVariant = Color(0xFF49331E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val SunsetAmberLight = lightColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF632B00),
    secondary = Color(0xFFEA580C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFEDD5),
    onSecondaryContainer = Color(0xFF5F1D00),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFDF5),
    onBackground = Color(0xFF231505),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF231505),
    surfaceVariant = Color(0xFFF3E7D5),
    onSurfaceVariant = Color(0xFF5C4B3A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF3E6),
    surfaceContainer = Color(0xFFF4E9D7),
    surfaceContainerHigh = Color(0xFFEDE0CB),
    surfaceContainerHighest = Color(0xFFE4D5BF),
    outline = Color(0xFF907B65),
    outlineVariant = Color(0xFFD8C7B0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 6. Emerald Forest (Deep Forest Green) ────────────────────
private val EmeraldForestDark = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF003820),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF003915),
    secondaryContainer = Color(0xFF14532D),
    onSecondaryContainer = Color(0xFFBBF7D0),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF003730),
    background = Color(0xFF05130D),
    onBackground = Color(0xFFDCFCE7),
    surface = Color(0xFF0A1E15),
    onSurface = Color(0xFFDCFCE7),
    surfaceVariant = Color(0xFF153325),
    onSurfaceVariant = Color(0xFF98C4AF),
    surfaceContainerLowest = Color(0xFF030D08),
    surfaceContainerLow = Color(0xFF0E251B),
    surfaceContainer = Color(0xFF132D21),
    surfaceContainerHigh = Color(0xFF1A372A),
    surfaceContainerHighest = Color(0xFF224334),
    outline = Color(0xFF58826F),
    outlineVariant = Color(0xFF244436),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val EmeraldForestLight = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF003C26),
    secondary = Color(0xFF16A34A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF003D14),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF3FAF6),
    onBackground = Color(0xFF092015),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF092015),
    surfaceVariant = Color(0xFFDCEEE3),
    onSurfaceVariant = Color(0xFF3F584C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF8F2),
    surfaceContainer = Color(0xFFE4F2E9),
    surfaceContainerHigh = Color(0xFFDAEBE0),
    surfaceContainerHighest = Color(0xFFCFE3D6),
    outline = Color(0xFF6B8B7D),
    outlineVariant = Color(0xFFBBD3C5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 7. Lavender Iris (Royal Amethyst & Violet) ───────────────
private val LavenderIrisDark = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF2E1065),
    primaryContainer = Color(0xFF5B21B6),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFC084FC),
    onSecondary = Color(0xFF3B0764),
    secondaryContainer = Color(0xFF6B21A8),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = Color(0xFF818CF8),
    onTertiary = Color(0xFF1E1B4B),
    background = Color(0xFF0F0A1C),
    onBackground = Color(0xFFEDE9FE),
    surface = Color(0xFF17102A),
    onSurface = Color(0xFFEDE9FE),
    surfaceVariant = Color(0xFF2B1F47),
    onSurfaceVariant = Color(0xFFBEB0DA),
    surfaceContainerLowest = Color(0xFF0B0615),
    surfaceContainerLow = Color(0xFF1D1434),
    surfaceContainer = Color(0xFF23193E),
    surfaceContainerHigh = Color(0xFF2C204C),
    surfaceContainerHighest = Color(0xFF37295D),
    outline = Color(0xFF78679B),
    outlineVariant = Color(0xFF382958),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LavenderIrisLight = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF3B0764),
    secondary = Color(0xFF9333EA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E8FF),
    onSecondaryContainer = Color(0xFF4C0519),
    tertiary = Color(0xFF4F46E5),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F6FD),
    onBackground = Color(0xFF1A112C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A112C),
    surfaceVariant = Color(0xFFEBE4F7),
    onSurfaceVariant = Color(0xFF4E4166),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3EEFC),
    surfaceContainer = Color(0xFFEBE2F7),
    surfaceContainerHigh = Color(0xFFE2D6F1),
    surfaceContainerHighest = Color(0xFFD7C7EB),
    outline = Color(0xFF7F6E9A),
    outlineVariant = Color(0xFFCABEE0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 8. Monochrome Clean (Slate & Pure White) ─────────────────
private val MonochromeDark = darkColorScheme(
    primary = Color(0xFFF8FAFC),
    onPrimary = Color(0xFF020617),
    primaryContainer = Color(0xFF334155),
    onPrimaryContainer = Color(0xFFF1F5F9),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFF94A3B8),
    onTertiary = Color(0xFF020617),
    background = Color(0xFF020617),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0B1120),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF020617),
    surfaceContainerLow = Color(0xFF0E1726),
    surfaceContainer = Color(0xFF152033),
    surfaceContainerHigh = Color(0xFF1D2B42),
    surfaceContainerHighest = Color(0xFF263752),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFF87171),
    onError = Color(0xFF020617)
)

private val MonochromeLight = lightColorScheme(
    primary = Color(0xFF0F172A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E8F0),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = Color(0xFF334155),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF475569),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF020617),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF020617),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

// ── Preset Themes Registry ──────────────────────────────────
val BitPresetThemes = listOf(
    PresetTheme("obsidian", "Obsidian Stealth", Color(0xFF262626), ObsidianLight, ObsidianDark),
    PresetTheme("seafoam_mint", "Seafoam Mint", Color(0xFF0D9488), SeafoamMintLight, SeafoamMintDark),
    PresetTheme("ocean_blue", "Ocean Cobalt", Color(0xFF2563EB), OceanLight, OceanDark),
    PresetTheme("sakura_pink", "Sakura Blossom", Color(0xFFDB2777), SakuraLight, SakuraDark),
    PresetTheme("sunset_amber", "Sunset Amber", Color(0xFFD97706), SunsetAmberLight, SunsetAmberDark),
    PresetTheme("emerald_forest", "Emerald Forest", Color(0xFF059669), EmeraldForestLight, EmeraldForestDark),
    PresetTheme("lavender_iris", "Lavender Iris", Color(0xFF7C3AED), LavenderIrisLight, LavenderIrisDark),
    PresetTheme("monochrome", "Monochrome Clean", Color(0xFF334155), MonochromeLight, MonochromeDark)
)

fun findBitPresetTheme(id: String): PresetTheme {
    return BitPresetThemes.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BitPresetThemes.first()
}
