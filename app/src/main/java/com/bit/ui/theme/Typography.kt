package com.bit.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bit.R
import java.io.File

@OptIn(ExperimentalTextApi::class)
val ManropeFontFamily = FontFamily(
    Font(
        resId = R.font.manrope,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight)
        )
    )
)

@OptIn(ExperimentalTextApi::class)
val MapleMonoFontFamily = FontFamily(
    Font(
        resId = R.font.maple_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight)
        )
    )
)

val GoogleSansFontFamily = FontFamily(
    Font(resId = R.font.google_sans_flex)
)

val GoogleSansCodeFontFamily = FontFamily(
    Font(resId = R.font.google_sans_code)
)

// Legacy alias — used throughout the codebase
val maple = MapleMonoFontFamily

fun resolveFontFamily(builtinFont: BuiltinFont, customFontPath: String?): FontFamily {
    return when (builtinFont) {
        BuiltinFont.SYSTEM -> FontFamily.Default
        BuiltinFont.MANROPE -> ManropeFontFamily
        BuiltinFont.GOOGLE_SANS -> GoogleSansFontFamily
        BuiltinFont.MAPLE_MONO -> MapleMonoFontFamily
        BuiltinFont.GOOGLE_SANS_CODE -> GoogleSansCodeFontFamily
        BuiltinFont.CUSTOM -> {
            if (!customFontPath.isNullOrBlank()) {
                val file = File(customFontPath)
                if (file.exists()) {
                    try {
                        val typeface = Typeface.createFromFile(file)
                        if (typeface != null) {
                            return FontFamily(typeface)
                        }
                    } catch (e: Exception) {
                        // fallback
                    }
                }
            }
            ManropeFontFamily
        }
    }
}

fun createBitTypography(fontFamily: FontFamily, scale: Float = 1.0f): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily, fontSize = (57 * s).sp, lineHeight = (64 * s).sp),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily, fontSize = (45 * s).sp, lineHeight = (52 * s).sp),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily, fontSize = (36 * s).sp, lineHeight = (44 * s).sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily, fontSize = (32 * s).sp, lineHeight = (40 * s).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily, fontSize = (28 * s).sp, lineHeight = (36 * s).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily, fontSize = (24 * s).sp, lineHeight = (32 * s).sp),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily, fontSize = (22 * s).sp, lineHeight = (28 * s).sp),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily, fontSize = (16 * s).sp, lineHeight = (24 * s).sp),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily, fontSize = (14 * s).sp, lineHeight = (20 * s).sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily, fontSize = (15 * s).sp, lineHeight = (22 * s).sp, letterSpacing = 0.15.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily, fontSize = (14 * s).sp, lineHeight = (20 * s).sp, letterSpacing = 0.15.sp),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily, fontSize = (12 * s).sp, lineHeight = (18 * s).sp, letterSpacing = 0.15.sp),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily, fontSize = (14 * s).sp, lineHeight = (20 * s).sp),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily, fontSize = (12 * s).sp, lineHeight = (16 * s).sp),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily, fontSize = (11 * s).sp, lineHeight = (14 * s).sp),
    )
}
