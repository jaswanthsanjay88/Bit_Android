package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedVisibility
import com.bit.ui.theme.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import java.util.Locale

// ── DeviceInfoCard ──

@Composable
internal fun DeviceInfoCard(deviceInfo: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    val entries = deviceInfo.entries.toList()
    val previewEntries = entries.take(3)
    val remainingEntries = entries.drop(3)

    GlassSectionCard(
        title = "Device Information",
        icon = TnIcons.Prompt,
        iconTint = Glass.AccentSecondary,
        trailing = {
            if (remainingEntries.isNotEmpty()) {
                ActionButton(
                    onClickListener = { expanded = !expanded },
                    icon = if (expanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
            previewEntries.forEach { (key, value) ->
                DeviceInfoRow(
                    label = key.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                    },
                    value = value
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = Motion.Enter,
                exit = Motion.Exit
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                    remainingEntries.forEach { (key, value) ->
                        DeviceInfoRow(
                            label = key.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                            },
                            value = value
                        )
                    }
                }
            }
        }
    }
}

// ── DeviceInfoRow ──

@Composable
internal fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}
