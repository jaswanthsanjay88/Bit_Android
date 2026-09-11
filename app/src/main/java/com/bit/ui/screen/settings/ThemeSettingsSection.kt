package com.bit.ui.screen.settings

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.ui.components.GlassCard
import com.bit.ui.components.GlassDivider
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.SettingsSwitchRow
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.BitPresetThemes
import com.bit.ui.theme.BuiltinFont
import com.bit.ui.theme.ColorMode
import com.bit.ui.theme.Glass
import com.bit.viewmodel.SettingsViewModel

internal fun LazyListScope.themeSettingsSection(
    viewModel: SettingsViewModel
) {
    item {
        val context = LocalContext.current
        val colorMode by viewModel.colorMode.collectAsStateWithLifecycle()
        val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
        val themePresetId by viewModel.themePresetId.collectAsStateWithLifecycle()
        val fontFamily by viewModel.fontFamily.collectAsStateWithLifecycle()
        val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
        val customFonts by viewModel.customFontsList.collectAsStateWithLifecycle()

        val fontPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                val displayName = uri.lastPathSegment ?: "custom_font.ttf"
                viewModel.importCustomFont(uri, displayName) { success ->
                    if (success) {
                        Toast.makeText(context, "Font imported successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to load font file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        GlassSectionCard(
            title = "Appearance & Theming",
            icon = TnIcons.Palette,
            description = "Personalize colors, Material You dynamic theming, fonts, and typography scales"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)) {

                // ── 1. Color Mode (System / Light / Dark) ──
                Text(
                    text = "Color Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf(
                        Triple(ColorMode.SYSTEM, "System", TnIcons.Sparkles),
                        Triple(ColorMode.LIGHT, "Light", TnIcons.Sun),
                        Triple(ColorMode.DARK, "Dark", TnIcons.Moon)
                    )

                    modes.forEach { (mode, label, icon) ->
                        val isSelected = colorMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.setColorMode(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                GlassDivider()

                // ── 2. Dynamic Color (Material You) ──
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsSwitchRow(
                        title = "Dynamic Color (Material You)",
                        description = "Harmonize colors with your Android wallpaper Monet palette",
                        checked = dynamicColorEnabled,
                        onCheckedChange = { viewModel.setDynamicColorEnabled(it) }
                    )

                    GlassDivider()
                }

                // ── 3. Preset Theme Palettes ──
                AnimatedVisibility(visible = !dynamicColorEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Preset Color Palettes",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BitPresetThemes.chunked(2).forEach { rowPresets ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPresets.forEach { preset ->
                                        val isSelected = themePresetId.equals(preset.id, ignoreCase = true)
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { viewModel.setThemePresetId(preset.id) },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .background(preset.primaryColor)
                                                )
                                                Text(
                                                    text = preset.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (rowPresets.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        GlassDivider()
                    }
                }

                // ── 4. Font Family Selector ──
                Text(
                    text = "App Typography",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fontOptions = listOf(
                        BuiltinFont.MANROPE,
                        BuiltinFont.GOOGLE_SANS,
                        BuiltinFont.SYSTEM,
                        BuiltinFont.MAPLE_MONO,
                        BuiltinFont.GOOGLE_SANS_CODE
                    )

                    fontOptions.forEach { font ->
                        val isSelected = fontFamily == font
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setFontFamily(font) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                0.8.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = font.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Custom Font Picker Button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                fontPickerLauncher.launch("*/*")
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Import Custom Font (.ttf / .otf)...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // List of imported custom fonts
                    if (customFonts.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Imported Fonts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        customFonts.forEach { item ->
                            val isSelected = fontFamily == BuiltinFont.CUSTOM && viewModel.customFontPath.value == item.path
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setCustomFontPath(item.path)
                                        viewModel.setFontFamily(BuiltinFont.CUSTOM)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(
                                    0.8.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteCustomFont(item.path) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                GlassDivider()

                // ── 5. Font Scale Slider with Live Preview ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Font Scale",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.2f", fontScale)}x",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = fontScale,
                    onValueChange = { viewModel.setFontScale(it) },
                    valueRange = 0.85f..1.30f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                // Live Preview Container
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Live Typography Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "The quick brown fox jumps over the lazy dog. 0123456789",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "code_preview() { return \"BIT Android AI Engine\"; }",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.bit.ui.theme.MapleMonoFontFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
