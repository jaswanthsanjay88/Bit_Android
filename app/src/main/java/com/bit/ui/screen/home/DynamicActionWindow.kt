package com.bit.ui.screen.home

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.models.table_schema.Model
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DynamicActionWindow(
    chatViewModel: ChatViewModel,
    modelViewModel: LLMModelViewModel,
    loadedRagCount: Int = 0,
    enabledToolCount: Int = 0,
    isMemoryEnabled: Boolean = false,
    ttsModelLoaded: Boolean = false,
    onModelSelectedNavigate: (Model) -> Unit = {}
) {
    val context = LocalContext.current
    val installedModels by modelViewModel.installedModels.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentModelID by modelViewModel.currentModelID.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = Motion.content()),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(Standards.RadiusLg)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── HEADER: Active Model & Status ──
            val activeModel = installedModels.find { it.id == currentModelID }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (activeModel != null) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    )

                    Column {
                        Text(
                            text = activeModel?.modelName ?: "No Model Active",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (activeModel != null) "Ready for inference" else "Select a model below to load",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (activeModel != null) {
                    TextButton(
                        onClick = { modelViewModel.unloadModel() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Unload", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── BODY: Quick Model Picker ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SELECT MODEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                if (installedModels.isEmpty()) {
                    Text(
                        text = "No models installed. Open the store to download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        installedModels.forEach { model ->
                            val isSelected = model.id == currentModelID
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            modelViewModel.unloadModel()
                                        } else {
                                            modelViewModel.loadModel(model)
                                            onModelSelectedNavigate(model)
                                        }
                                        chatViewModel.hideDynamicWindow()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model.modelName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = model.providerType.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── FOOTER: Resources & Options ──
            val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
            val isTextModelLoaded by modelViewModel.isGgufModelLoaded.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Active badges & memory stats
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val memoryUsage by produceState("--") {
                        value = withContext(Dispatchers.IO) {
                            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            val memoryInfo = ActivityManager.MemoryInfo()
                            activityManager.getMemoryInfo(memoryInfo)
                            val usedGB = (memoryInfo.totalMem - memoryInfo.availMem).toDouble() / (1024.0 * 1024.0 * 1024.0)
                            val totalGB = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
                            String.format("%.1f GB / %.1f GB", usedGB, totalGB)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = TnIcons.Cpu,
                            contentDescription = "RAM",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = memoryUsage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Compact Badges
                    val hasActiveSubsystems = loadedRagCount > 0 || enabledToolCount > 0 || isMemoryEnabled || ttsModelLoaded
                    if (hasActiveSubsystems) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            if (loadedRagCount > 0) {
                                CompactBadge("RAG", MaterialTheme.colorScheme.primary)
                            }
                            if (enabledToolCount > 0) {
                                CompactBadge("Tools", MaterialTheme.colorScheme.tertiary)
                            }
                            if (isMemoryEnabled) {
                                CompactBadge("Memory", MaterialTheme.colorScheme.secondary)
                            }
                            if (ttsModelLoaded) {
                                CompactBadge("TTS", MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }

                // Right side: Thinking Mode Switch
                if (isTextModelLoaded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Thinking",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = chatState.thinkingEnabled,
                            onCheckedChange = { chatViewModel.setThinkingMode(it) },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(Standards.SpacingXs),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = Standards.SpacingXxs),
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(color, CircleShape)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
