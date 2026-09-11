package com.bit.ui.screen.model_store

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.data.AppSettingsDataStore
import com.bit.global.PerformanceMode
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.components.StandardCard
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * Hardware Inference Headroom Estimator Card.
 * Displays real-time device RAM metrics, safe model budget, and capacity tier.
 */
@Composable
fun HardwareHeadroomCard(
    deviceInfo: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var totalRamMB by remember { mutableLongStateOf(0L) }
    var availRamMB by remember { mutableLongStateOf(0L) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            if (am != null) {
                am.getMemoryInfo(memInfo)
                totalRamMB = memInfo.totalMem / (1024 * 1024)
                availRamMB = memInfo.availMem / (1024 * 1024)
            }
        } catch (_: Exception) {}
    }

    val usedRamMB = (totalRamMB - availRamMB).coerceAtLeast(0L)
    val ramUsagePercent = if (totalRamMB > 0) (usedRamMB.toFloat() / totalRamMB.toFloat()).coerceIn(0f, 1f) else 0.5f
    val safeModelBudgetMB = (availRamMB * 0.65f).toInt()
    val safeModelBudgetGB = safeModelBudgetMB / 1024f

    val capabilityVerdict = when {
        availRamMB >= 6000 -> "Optimal: Can run 7B Q4_K_M or 3B Q8_0 comfortably"
        availRamMB >= 3200 -> "Good: Can run 1B - 3B models with full KV cache"
        availRamMB >= 1800 -> "Moderate: Recommended 1B models or compact quantization"
        else -> "Constrained: Recommended 0.5B models or cloud endpoints"
    }

    StandardCard(
        title = "Inference Headroom & Memory",
        icon = TnIcons.Gauge,
        trailing = {
            ActionButton(
                onClickListener = { expanded = !expanded },
                icon = if (expanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // RAM Progress Bar Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RAM Utilization",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(ramUsagePercent * 100).toInt()}% (${usedRamMB / 1024}G / ${totalRamMB / 1024}G)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { ramUsagePercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (ramUsagePercent > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Safe Headroom Stat Pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        TnIcons.CircleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Safe Model Weight Budget: ~${String.format("%.1f", safeModelBudgetGB)} GB",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = capabilityVerdict,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = Motion.Enter,
                exit = Motion.Exit
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HeadroomStatRow("Available Headroom", "${String.format("%.1f", availRamMB / 1024f)} GB Free")
                    HeadroomStatRow("KV Cache Allocation", "~${(safeModelBudgetMB * 0.35f).toInt()} MB reserved")
                    HeadroomStatRow("Recommended Context", if (availRamMB >= 4000) "4096 - 8192 tokens" else "2048 tokens")
                }
            }
        }
    }
}

@Composable
private fun HeadroomStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Architecture & Research Quick Filters row for Model Explorer.
 */
@Composable
fun ArchitectureFilterRow(
    selectedArchitecture: String,
    onSelectArchitecture: (displayName: String, searchKeyword: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        "All" to "",
        "Llama 3" to "llama-3.2 gguf",
        "Qwen 2.5" to "qwen2.5 gguf",
        "DeepSeek R1" to "deepseek-r1 gguf",
        "Mistral" to "mistral gguf",
        "Gemma 2" to "gemma-2 gguf",
        "Phi-4" to "phi-4 gguf",
        "Embeddings" to "bge gguf"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        filters.forEach { (name, keyword) ->
            val isSelected = selectedArchitecture == name
            FilterChip(
                selected = isSelected,
                onClick = { onSelectArchitecture(name, keyword) },
                label = {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

/**
 * Engine Runtime Tuning Card for controlling local LLM inference parameters.
 */
@Composable
fun EngineRuntimeTuningCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettingsDataStore(context) }
    val currentMode by appSettings.performanceMode.collectAsStateWithLifecycle(initialValue = PerformanceMode.BALANCED)
    val sttThreads by appSettings.sttThreads.collectAsStateWithLifecycle(initialValue = 2)

    var expanded by remember { mutableStateOf(false) }

    StandardCard(
        title = "Engine Runtime Tuning",
        icon = TnIcons.Adjustments,
        trailing = {
            ActionButton(
                onClickListener = { expanded = !expanded },
                icon = if (expanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Performance Mode Selector
            Text(
                text = "Performance Profile",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PerformanceMode.values().forEach { mode ->
                    val isSelected = currentMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            scope.launch { appSettings.savePerformanceMode(mode) }
                        },
                        label = {
                            Text(
                                text = mode.name.lowercase().replaceFirstChar { it.uppercase() }.replace("_", " "),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Thread Allocation
                    Text(
                        text = "CPU Inference Threads: $sttThreads",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 4).forEach { count ->
                            val isSelected = sttThreads == count
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    scope.launch { appSettings.updateSttThreads(count) }
                                },
                                label = {
                                    Text(
                                        text = "$count Thread${if (count > 1) "s" else ""}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    Text(
                        text = "Model threads and KV cache quantization are auto-calibrated by DeviceTuner to avoid CPU thermal throttling.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
