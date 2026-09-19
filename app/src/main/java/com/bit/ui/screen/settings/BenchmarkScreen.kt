package com.bit.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.benchmark.*
import com.bit.di.AppContainer
import com.bit.models.engine_schema.GgufEngineSchema
import com.bit.global.Standards
import com.bit.global.DeviceTuner
import com.bit.global.HardwareScanner
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import com.bit.ui.components.GlassCard
import com.bit.ui.components.GlassDivider
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.LocalBitHaptics
import com.bit.worker.ActiveModelSession
import com.bit.worker.LlmModelWorker
import com.bit.worker.ModelDataParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    installedModels: List<Model>,
    onBack: () -> Unit,
    onNavigateToModelStore: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    // Show all local GGUF models (including locally uploaded SAF content:// models and downloaded models)
    val localGgufModels = remember(installedModels) {
        val list = installedModels.filter { it.providerType == ProviderType.GGUF }.toMutableList()
        val workerModel = LlmModelWorker.lastLoadedGgufModel
        if (workerModel != null && workerModel.providerType == ProviderType.GGUF && list.none { it.id == workerModel.id }) {
            list.add(0, workerModel)
        }
        list
    }

    // Benchmark runner instance
    val runner = remember { ToolBenchmarkRunner(scope) }
    val isRunning by runner.isRunning.collectAsStateWithLifecycle()
    val progressIndex by runner.progressIndex.collectAsStateWithLifecycle()
    val totalTests by runner.totalTests.collectAsStateWithLifecycle()
    val statusMessage by runner.statusMessage.collectAsStateWithLifecycle()
    val completedResults by runner.completedResults.collectAsStateWithLifecycle()
    val summary by runner.summary.collectAsStateWithLifecycle()

    // Currently selected model
    var selectedModel by remember(localGgufModels) {
        val activeId = ActiveModelSession.currentModelId.value
        val activeMatch = localGgufModels.find { it.id == activeId }
        mutableStateOf(activeMatch ?: localGgufModels.firstOrNull())
    }

    val currentGgufId by LlmModelWorker.currentGgufModelId.collectAsStateWithLifecycle()
    val activeSessionId by ActiveModelSession.currentModelId.collectAsStateWithLifecycle()
    val isGgufLoaded by LlmModelWorker.isGgufModelLoaded.collectAsStateWithLifecycle()

    var isModelLoading by remember { mutableStateOf(false) }
    var warmupEnabled by remember { mutableStateOf(true) }

    // SAF local GGUF file picker
    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            scope.launch(Dispatchers.IO) {
                try {
                    val parser = ModelDataParser()
                    val fileName = parser.getFileNameFromUri(context, uri)
                    val fileSize = parser.getFileSizeFromUri(context, uri)
                    val hash = parser.checksumSHA256FromUri(context, uri)
                    val newModel = Model(
                        id = hash,
                        modelPath = uri.toString(),
                        modelName = fileName,
                        pathType = PathType.CONTENT_URI,
                        providerType = ProviderType.GGUF,
                        fileSize = fileSize,
                        isActive = true
                    )
                    val repo = AppContainer.getModelRepository()
                    repo.insertModel(newModel)

                    val appSettings = com.bit.data.AppSettingsDataStore(context)
                    val tuningEnabled = appSettings.hardwareTuningEnabled.firstOrNull() ?: true
                    val loadingParams = if (tuningEnabled) {
                        val perfMode = appSettings.performanceMode.firstOrNull() ?: com.bit.global.PerformanceMode.BALANCED
                        val modelSizeMB = ((fileSize) / (1024 * 1024)).toInt()
                        val profile = HardwareScanner.scan(context)
                        DeviceTuner.tune(profile, modelSizeMB, fileName, perfMode)
                    } else {
                        com.bit.models.engine_schema.GgufLoadingParams()
                    }
                    val schema = GgufEngineSchema(loadingParams = loadingParams)
                    repo.insertConfig(
                        ModelConfig(
                            modelId = newModel.id,
                            modelLoadingParams = schema.toLoadingJson(),
                            modelInferenceParams = schema.toInferenceJson()
                        )
                    )
                    withContext(Dispatchers.Main) {
                        selectedModel = newModel
                        Toast.makeText(context, "Added $fileName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to import model: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            // ── Section 1: Local GGUF Model Selector ──
            item {
                Text(
                    text = "Select Local GGUF Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Benchmark prefill TTFT, decode tokens/second, and BFCL tool calling accuracy on local device hardware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (localGgufModels.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Standards.SpacingLg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                        ) {
                            Icon(
                                imageVector = TnIcons.Gauge,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No Local GGUF Models Found",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pick a local .gguf file from device storage or download one from the Model Store.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        haptics.selection()
                                        ggufPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                    },
                                    shape = RoundedCornerShape(Standards.RadiusMd)
                                ) {
                                    Icon(TnIcons.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pick Local File")
                                }
                                if (onNavigateToModelStore != null) {
                                    OutlinedButton(
                                        onClick = {
                                            haptics.selection()
                                            onNavigateToModelStore()
                                        },
                                        shape = RoundedCornerShape(Standards.RadiusMd)
                                    ) {
                                        Icon(TnIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Model Store")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    GlassSectionCard(
                        title = "Local GGUF Models",
                        icon = TnIcons.Cpu,
                        trailing = {
                            TextButton(
                                onClick = {
                                    haptics.selection()
                                    ggufPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(TnIcons.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pick File", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    ) {
                        localGgufModels.forEachIndexed { index, model ->
                            val isSelected = selectedModel?.id == model.id
                            val isLoaded = (currentGgufId == model.id || activeSessionId == model.id) && isGgufLoaded

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isRunning) {
                                        haptics.selection()
                                        selectedModel = model
                                    }
                                    .padding(vertical = 10.dp, horizontal = Standards.SpacingSm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Radio selection dot
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = 2.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = model.modelName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )

                                        if (isLoaded) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = "LOADED",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    val fileSizeStr = model.fileSize?.takeIf { it > 0 }?.let { formatFileSize(it) } ?: "Local GGUF"
                                    val sourceLabel = when {
                                        model.pathType == PathType.CONTENT_URI || model.modelPath.startsWith("content://") -> "Locally Uploaded"
                                        model.modelPath.isNotBlank() && !model.modelPath.startsWith("content://") -> File(model.modelPath).name
                                        else -> "Local Model"
                                    }
                                    Text(
                                        text = "$fileSizeStr • $sourceLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (index < localGgufModels.size - 1) {
                                GlassDivider()
                            }
                        }
                    }
                }

                // ── Section 2: Benchmark Configuration & Actions ──
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Standards.SpacingMd),
                            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Warm-up Round",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Prefills 1 dummy token to page weights into memory before measuring",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = warmupEnabled,
                                    onCheckedChange = { warmupEnabled = it },
                                    enabled = !isRunning
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (isRunning) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val progressFraction = if (totalTests > 0) progressIndex.toFloat() / totalTests.toFloat() else 0f
                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Running test $progressIndex of $totalTests",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "${(progressFraction * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = statusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    OutlinedButton(
                                        onClick = {
                                            haptics.selection()
                                            runner.cancel()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(Standards.RadiusMd)
                                    ) {
                                        Icon(TnIcons.PlayerStop, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Stop Benchmark")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val model = selectedModel ?: return@Button
                                        haptics.generationStart()
                                        scope.launch {
                                            // Load model if not active
                                            val isLoaded = (LlmModelWorker.currentGgufModelId.value == model.id || ActiveModelSession.currentModelId.value == model.id) && LlmModelWorker.isGgufModelLoaded.value
                                            if (!isLoaded) {
                                                isModelLoading = true
                                                try {
                                                    val repo = AppContainer.getModelRepository()
                                                    val cfg = repo.getConfigByModelId(model.id) ?: ModelConfig(
                                                        modelId = model.id,
                                                        modelLoadingParams = null,
                                                        modelInferenceParams = null
                                                    )
                                                    val loadSuccess = if (model.pathType == PathType.CONTENT_URI || model.modelPath.startsWith("content://")) {
                                                        val uri = Uri.parse(model.modelPath)
                                                        LlmModelWorker.loadGgufModelFromUri(
                                                            context = context,
                                                            uri = uri,
                                                            modelName = model.modelName,
                                                            modelConfig = cfg
                                                        )
                                                    } else {
                                                        LlmModelWorker.loadGgufModel(model, cfg)
                                                    }
                                                    if (loadSuccess) {
                                                        LlmModelWorker.setCurrentGgufModelId(model.id)
                                                        ActiveModelSession.set(model.id, ProviderType.GGUF)
                                                    } else {
                                                        Toast.makeText(context, "Failed to load model into memory", Toast.LENGTH_SHORT).show()
                                                        isModelLoading = false
                                                        return@launch
                                                    }
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
                                                    isModelLoading = false
                                                    return@launch
                                                }
                                                isModelLoading = false
                                            }

                                            runner.startBenchmark(model = model, warmupFirst = warmupEnabled)
                                        }
                                    },
                                    enabled = selectedModel != null && !isModelLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(Standards.RadiusMd)
                                ) {
                                    if (isModelLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Loading model into memory...")
                                    } else {
                                        Icon(TnIcons.PlayerPlay, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Run BFCL Benchmark on ${selectedModel?.modelName ?: "Selected"}")
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Section 3: Benchmark Summary Dashboard ──
                summary?.let { sum ->
                    item {
                        BenchmarkSummaryDashboard(
                            summary = sum,
                            onCopyReport = {
                                haptics.selection()
                                val report = formatBenchmarkReport(sum)
                                clipboardManager.setText(AnnotatedString(report))
                                Toast.makeText(context, "Benchmark report copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                // ── Section 4: Live / Completed Test Results Breakdown ──
                val currentResults = if (isRunning) completedResults else summary?.testResults ?: completedResults
                if (currentResults.isNotEmpty()) {
                    item {
                        Text(
                            text = "Test Case Results (${currentResults.size} evaluated)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    items(currentResults) { result ->
                        TestCaseResultCard(result = result)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BenchmarkSummaryDashboard(
    summary: BenchmarkSummary,
    onCopyReport: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Benchmark Scorecard",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = summary.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onCopyReport,
                    shape = RoundedCornerShape(Standards.RadiusMd),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(TnIcons.Copy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy Report", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Hero AST Match Rate
            val scoreColor = when {
                summary.astMatchRate >= 80f -> Color(0xFF4CAF50)
                summary.astMatchRate >= 50f -> Color(0xFFFFA000)
                else -> Color(0xFFE53935)
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd),
                color = scoreColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BFCL AST Accuracy",
                            style = MaterialTheme.typography.labelMedium,
                            color = scoreColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.1f", summary.astMatchRate)}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor
                        )
                    }
                    Text(
                        text = "${summary.passedCount} / ${summary.totalTests} Passed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 4-Card Performance Metric Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                MetricMiniCard(
                    title = "Prefill TTFT",
                    value = "${summary.avgTtftMs.toInt()} ms",
                    subtitle = "Prompt eval latency",
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCard(
                    title = "Decode Speed",
                    value = "${String.format(Locale.US, "%.1f", summary.avgDecodeTps)} t/s",
                    subtitle = "Generation tok/s",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                MetricMiniCard(
                    title = "Tool Selection",
                    value = "${summary.toolSelectionAccuracy.toInt()}%",
                    subtitle = "Target identification",
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCard(
                    title = "False Positive",
                    value = "${summary.falsePositiveRate.toInt()}%",
                    subtitle = "Hallucination rate",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricMiniCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Standards.RadiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun TestCaseResultCard(result: TestCaseResult) {
    var expanded by remember { mutableStateOf(false) }
    val isPassed = result.matchResult.isMatch

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingSm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusColor = if (isPassed) Color(0xFF4CAF50) else Color(0xFFE53935)
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPassed) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = result.testCase.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${result.testCase.category.label} • TTFT: ${result.ttftMs.toInt()}ms • ${String.format(Locale.US, "%.1f", result.decodeTps)} t/s",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassDivider()

                    Text(
                        text = "Query Prompt:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = result.testCase.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (result.testCase.expectedCall != null) {
                        Text(
                            text = "Expected Tool Call:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${result.testCase.expectedCall.name}(${result.testCase.expectedCall.arguments})",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            text = "Expected: NO tool call (Pure natural language)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Raw Generated Output:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(Standards.RadiusSm),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = result.rawResponse.trim().ifEmpty { "<empty output>" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!isPassed && result.matchResult.mismatchReason != null) {
                        Text(
                            text = "Failure Reason: ${result.matchResult.mismatchReason}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

private fun formatBenchmarkReport(summary: BenchmarkSummary): String {
    return buildString {
        appendLine("=== BIT On-Device Model Benchmark Report ===")
        appendLine("Model: ${summary.modelName}")
        appendLine("Path: ${summary.modelPath}")
        appendLine("BFCL AST Match Rate: ${String.format(Locale.US, "%.1f", summary.astMatchRate)}% (${summary.passedCount}/${summary.totalTests} passed)")
        appendLine("Tool Selection Accuracy: ${String.format(Locale.US, "%.1f", summary.toolSelectionAccuracy)}%")
        appendLine("Argument Validity: ${String.format(Locale.US, "%.1f", summary.argumentValidityRate)}%")
        appendLine("False Positive Rate: ${String.format(Locale.US, "%.1f", summary.falsePositiveRate)}%")
        appendLine("Avg TTFT: ${summary.avgTtftMs.toInt()} ms")
        appendLine("Avg Decode Speed: ${String.format(Locale.US, "%.1f", summary.avgDecodeTps)} tokens/sec")
        appendLine("Avg Latency: ${String.format(Locale.US, "%.1f", summary.avgLatencyMs / 1000f)} s")
        appendLine("\n--- Breakdown ---")
        for ((idx, test) in summary.testResults.withIndex()) {
            val status = if (test.matchResult.isMatch) "PASS" else "FAIL"
            appendLine("[${idx + 1}] $status: ${test.testCase.title} (TTFT: ${test.ttftMs.toInt()}ms, ${String.format(Locale.US, "%.1f", test.decodeTps)} t/s)")
            if (!test.matchResult.isMatch) {
                appendLine("     Reason: ${test.matchResult.mismatchReason}")
            }
        }
    }
}
