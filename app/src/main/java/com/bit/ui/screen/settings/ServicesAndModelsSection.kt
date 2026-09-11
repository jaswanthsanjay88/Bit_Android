package com.bit.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics

/**
 * Pure Material 3 Expressive UI for Services & Models.
 * Clean, unified theme without distracting rainbow accents.
 * Directly navigates to Advanced Model Configuration on item click.
 */
fun LazyListScope.servicesAndModelsSection(
    hardwareTuningEnabled: Boolean,
    installedModels: List<Model>,
    tokenState: HfTokenState,
    testResult: HfTestResult?,
    onModelSelected: (Model) -> Unit,
    onEmbeddingSetup: () -> Unit,
    onModelEditor: () -> Unit,
    onNavigateToModelStore: () -> Unit = {},
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onTestConnection: () -> Unit
) {
    // ── 1. MATERIAL 3 HERO STATUS CARD ──
    item(key = "services_hero_card") {
        MaterialHeroCard(
            modelCount = installedModels.size,
            onNavigateToModelStore = onNavigateToModelStore,
            onModelEditor = onModelEditor
        )
    }

    // ── 2. HARDWARE PERFORMANCE NOTICE (If tuning enabled) ──
    if (hardwareTuningEnabled) {
        item(key = "hardware_tuning_banner") {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FilledIconButton(
                        onClick = {},
                        enabled = false,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hardware Performance Engine Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Model threads, context window, and quantization parameters are automatically tuned for your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // ── 3. CONFIGURED MODELS SECTION ──
    item(key = "models_list_header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONFIGURED MODELS & ENGINES",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text("${installedModels.size}")
            }
        }
    }

    if (installedModels.isEmpty()) {
        item(key = "models_empty_card") {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onNavigateToModelStore,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Text(
                        text = "No Models Installed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Download on-device GGUF / Whisper weights or connect cloud APIs from the Model Store.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(
                        onClick = onNavigateToModelStore,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Browse Model Catalog")
                    }
                }
            }
        }
    } else {
        item(key = "models_items_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    installedModels.forEachIndexed { index, model ->
                        MaterialModelItemRow(
                            model = model,
                            onClick = { onModelSelected(model) }
                        )
                        if (index < installedModels.size - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 4. RAG EMBEDDINGS & ADVANCED CONFIGURATION ──
    item(key = "rag_and_config_cards") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // RAG Vector Embedding Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onEmbeddingSetup() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "RAG Embedding Model Setup",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = {
                        Text("Configure vector embedding model used for document & memory search")
                    },
                    leadingContent = {
                        FilledTonalIconButton(
                            onClick = onEmbeddingSetup,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }

            // Advanced Model Configuration Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onModelEditor() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Advanced Model Config Editor",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = {
                        Text("Edit raw temperature, top-k, context window, threads, and prompts")
                    },
                    leadingContent = {
                        FilledTonalIconButton(
                            onClick = onModelEditor,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        }
    }

    // ── 5. HUGGINGFACE HUB ACCESS TOKEN CARD ──
    item(key = "huggingface_token_card") {
        MaterialHuggingFaceCard(
            tokenState = tokenState,
            testResult = testResult,
            onSaveToken = onSaveToken,
            onClearToken = onClearToken,
            onTestConnection = onTestConnection
        )
    }
}

// ── MATERIAL 3 HERO CARD ──

@Composable
private fun MaterialHeroCard(
    modelCount: Int,
    onNavigateToModelStore: () -> Unit,
    onModelEditor: () -> Unit
) {
    val bitHaptics = LocalBitHaptics.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FilledTonalIconButton(
                    onClick = onNavigateToModelStore,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = TnIcons.Sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Services & Models",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$modelCount configured engines • Local LLM, Cloud API, STT, TTS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "Manage neural weights, inference parameters, and token authentication for your models.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        bitHaptics.pop()
                        onNavigateToModelStore()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Model Store")
                }

                FilledTonalButton(
                    onClick = {
                        bitHaptics.selection()
                        onModelEditor()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Config Editor")
                }
            }
        }
    }
}

// ── MATERIAL 3 MODEL ITEM ROW ──

@Composable
private fun MaterialModelItemRow(
    model: Model,
    onClick: () -> Unit
) {
    val bitHaptics = LocalBitHaptics.current

    val (iconVector, badgeLabel) = when (model.providerType) {
        ProviderType.GGUF -> Pair(TnIcons.Cpu, "GGUF")
        ProviderType.API -> Pair(TnIcons.World, "API")
        ProviderType.STT -> Pair(TnIcons.Microphone, "STT")
        ProviderType.TTS -> Pair(TnIcons.Volume, "TTS")
        ProviderType.DIFFUSION -> Pair(TnIcons.Photo, "DIFFUSION")
        ProviderType.VLM -> Pair(TnIcons.Eye, "VISION")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                bitHaptics.selection()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Standard Material 3 Icon Surface
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Title and description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.modelName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtext = when (model.providerType) {
                ProviderType.STT -> "Speech-to-Text Model"
                ProviderType.TTS -> "Text-to-Speech Engine"
                ProviderType.API -> "Cloud Inference API"
                ProviderType.GGUF -> "Local Tensor Engine"
                ProviderType.DIFFUSION -> "Image Generation Pipeline"
                ProviderType.VLM -> "Multimodal Vision Model"
            }
            Text(
                text = subtext,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Provider Badge
        SuggestionChip(
            onClick = onClick,
            label = {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = null,
            modifier = Modifier.height(28.dp)
        )

        // Chevron
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Configure",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// ── MATERIAL 3 HUGGINGFACE HUB CARD ──

@Composable
private fun MaterialHuggingFaceCard(
    tokenState: HfTokenState,
    testResult: HfTestResult?,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onTestConnection: () -> Unit
) {
    val bitHaptics = LocalBitHaptics.current
    var tokenInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HuggingFace Hub Access",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Gated weights authentication",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Standard Material 3 Status FilterChip
                FilterChip(
                    selected = tokenState == HfTokenState.SET,
                    onClick = {},
                    label = {
                        Text(
                            text = when (tokenState) {
                                HfTokenState.SET -> "Connected"
                                HfTokenState.NOT_SET -> "Not Configured"
                                HfTokenState.SAVING -> "Saving..."
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            Text(
                text = "Authenticate your account to download gated models (Llama 3.2, Gemma 2, Mistral) directly to local storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Token input field
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HuggingFace Access Token (hf_...)") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide token" else "Show token"
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp)
            )

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        bitHaptics.pop()
                        onSaveToken(tokenInput)
                        tokenInput = ""
                    },
                    enabled = tokenInput.isNotBlank() && tokenState != HfTokenState.SAVING,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Token")
                }

                if (tokenState == HfTokenState.SET) {
                    FilledTonalButton(
                        onClick = {
                            bitHaptics.selection()
                            onTestConnection()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Test")
                    }

                    OutlinedButton(
                        onClick = {
                            bitHaptics.reject()
                            onClearToken()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear")
                    }
                }
            }

            // Test Result feedback
            AnimatedVisibility(visible = testResult != null) {
                testResult?.let { res ->
                    val isSuccess = res is HfTestResult.Success
                    val isTesting = res is HfTestResult.Testing
                    val message = when (res) {
                        is HfTestResult.Success -> "Connected as @${res.username}"
                        is HfTestResult.Failed -> "Connection failed: ${res.error}"
                        is HfTestResult.Testing -> "Testing connection to HuggingFace Hub..."
                    }
                    Surface(
                        color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer else if (isTesting) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else if (isTesting) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
