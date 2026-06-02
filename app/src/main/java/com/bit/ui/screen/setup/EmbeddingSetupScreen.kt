package com.bit.ui.screen.setup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bit.engine.EmbeddingEngine
import com.bit.engine.EmbeddingConfig
import com.bit.global.Standards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val DEFAULT_EMBEDDING_MODEL_URL =
    "https://huggingface.co/spaces/Void2377/neurov/resolve/main/all-MiniLM-L6-v2-Q5_K_M.gguf?download=true"
private const val FALLBACK_EMBEDDING_MODEL_URL =
    "https://huggingface.co/spaces/Void2377/neurov/resolve/main/all-MiniLM-L6-v2-Q5_K_M.gguf?download=true"
private const val EMBEDDING_MODEL_SUGGESTIONS =
    "Try embedding GGUF models (not chat models). Recommended: all-MiniLM-L6-v2-Q5_K_M.gguf"

private enum class EmbeddingSetupStage {
    PREPARING,
    DOWNLOADING,
    EXTRACTING,
    PROCESSING,
    VERIFYING,
    FINALIZING
}

private data class EmbeddingSetupProgress(
    val stage: EmbeddingSetupStage,
    val progress: Float,
    val status: String
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmbeddingSetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("Checking embedding model...") }
    var isDownloading by remember { mutableStateOf(false) }
    var hasExistingModel by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf(DEFAULT_EMBEDDING_MODEL_URL) }
    var isReadyToChooseModel by remember { mutableStateOf(false) }
    var downloadRequestUrl by remember { mutableStateOf<String?>(null) }
    var importRequestUri by remember { mutableStateOf<Uri?>(null) }

    val localModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Non-persistable providers may throw; normal read still works.
            }
            importRequestUri = uri
        }
    }

    LaunchedEffect(Unit) {
        val modelPath = EmbeddingEngine.getModelPath(context)
        if (EmbeddingEngine.isModelFileValid(modelPath)) {
            hasExistingModel = true
            statusMessage = "Embedding model already installed. You can keep it or replace it below."
            isReadyToChooseModel = true
        } else {
            statusMessage = "Choose a model to download"
            isReadyToChooseModel = true
        }
    }

    LaunchedEffect(downloadRequestUrl) {
        val requestUrl = downloadRequestUrl ?: return@LaunchedEffect
        isDownloading = true
        isReadyToChooseModel = false
        downloadProgress = 0f
        statusMessage = "Preparing download..."

        val result = downloadEmbeddingModel(
            context = context,
            modelUrl = requestUrl,
            onProgress = { update ->
                downloadProgress = update.progress
                statusMessage = update.status
            }
        )

        isDownloading = false
        downloadRequestUrl = null

        if (result.isSuccess) {
            statusMessage = "Setup complete!"
            onSetupComplete()
        } else {
            statusMessage = "Error: ${result.exceptionOrNull()?.message ?: "Download failed"}"
            isReadyToChooseModel = true
        }
    }

    LaunchedEffect(importRequestUri) {
        val uri = importRequestUri ?: return@LaunchedEffect
        isDownloading = true
        isReadyToChooseModel = false
        downloadProgress = 0f
        statusMessage = "Preparing local model import..."

        val result = importEmbeddingModelFromUri(
            context = context,
            modelUri = uri,
            onProgress = { update ->
                downloadProgress = update.progress
                statusMessage = update.status
            }
        )

        isDownloading = false
        importRequestUri = null

        if (result.isSuccess) {
            statusMessage = "Setup complete!"
            onSetupComplete()
        } else {
            statusMessage = "Error: ${result.exceptionOrNull()?.message ?: "Import failed"}"
            isReadyToChooseModel = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isDownloading) {
                LoadingIndicator(
                    modifier = Modifier.size(64.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(Standards.SpacingXl))

                Text(
                    text = "Setting up BIT",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(Standards.SpacingLg))

                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(Standards.SpacingSm))

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            } else if (isReadyToChooseModel) {
                Text(
                    text = "Embedding Model Setup",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Standards.SpacingMd))

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Standards.SpacingLg))

                Button(
                    onClick = {
                        downloadRequestUrl = DEFAULT_EMBEDDING_MODEL_URL
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download Default Model")
                }

                Spacer(modifier = Modifier.height(Standards.SpacingMd))

                Button(
                    onClick = {
                        localModelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select Local GGUF Model")
                }

                Spacer(modifier = Modifier.height(Standards.SpacingMd))

                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Custom GGUF URL") },
                    placeholder = { Text("https://huggingface.co/.../model.gguf") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(Standards.SpacingSm))

                TextButton(
                    onClick = {
                        val trimmed = customUrl.trim()
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                            downloadRequestUrl = trimmed
                        } else {
                            statusMessage = "Please enter a valid URL (http/https)."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download Custom Model")
                }

                Spacer(modifier = Modifier.height(Standards.SpacingSm))
                Text(
                    text = "Supported presets: all-MiniLM-L6-v2-Q5_K_M.gguf",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )

                if (hasExistingModel) {
                    Spacer(modifier = Modifier.height(Standards.SpacingSm))
                    TextButton(
                        onClick = onSetupComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue With Current Model")
                    }
                }
            } else {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (statusMessage.startsWith("Error")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private suspend fun downloadEmbeddingModel(
    context: android.content.Context,
    modelUrl: String,
    onProgress: (EmbeddingSetupProgress) -> Unit
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.PREPARING, 0f, "Preparing download..."))

        val modelPath = EmbeddingEngine.getModelPath(context)
        modelPath.parentFile?.mkdirs()

        val tempPath = modelPath.resolveSibling("${modelPath.name}.part")
        val tempZipPath = modelPath.resolveSibling("${modelPath.name}.zip.tmp")
        if (tempPath.exists()) tempPath.delete()
        if (tempZipPath.exists()) tempZipPath.delete()
        if (modelPath.exists()) modelPath.delete()

        val connection = openFinalConnection(modelUrl)
        val fileSize = connection.contentLengthLong
        val isZipDownload = modelUrl.substringBefore('?').endsWith(".zip", ignoreCase = true)

        connection.inputStream.use { inputStream ->
            val downloadTarget = if (isZipDownload) tempZipPath else tempPath
            downloadTarget.outputStream().use { outputStream ->
                val buffer = ByteArray(65_536)
                var totalBytesRead = 0L
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (fileSize > 0) {
                        val progress = (totalBytesRead.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f)
                        onProgress(
                            EmbeddingSetupProgress(
                                stage = EmbeddingSetupStage.DOWNLOADING,
                                progress = (progress * 0.8f).coerceIn(0f, 0.8f),
                                status = "Downloading: ${(progress * 100).toInt()}%"
                            )
                        )
                    } else {
                        onProgress(
                            EmbeddingSetupProgress(
                                stage = EmbeddingSetupStage.DOWNLOADING,
                                progress = 0f,
                                status = "Downloading: ${totalBytesRead / (1024 * 1024)}MB"
                            )
                        )
                    }
                }
                outputStream.flush()
            }
        }
        connection.disconnect()

        if (isZipDownload) {
            onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.EXTRACTING, 0.82f, "Extracting model archive..."))
            val extracted = extractFirstGgufFromZip(
                zipFile = tempZipPath,
                outputFile = tempPath,
                onExtractProgress = { extractedCount, totalCount, entryName ->
                    val fraction = if (totalCount > 0) {
                        extractedCount.toFloat() / totalCount.toFloat()
                    } else {
                        0f
                    }
                    onProgress(
                        EmbeddingSetupProgress(
                            stage = EmbeddingSetupStage.EXTRACTING,
                            progress = 0.82f + (fraction * 0.1f).coerceIn(0f, 0.1f),
                            status = if (entryName.isNotBlank()) {
                                "Extracting: $entryName ($extractedCount/$totalCount)"
                            } else {
                                "Extracting model archive..."
                            }
                        )
                    )
                }
            )
            tempZipPath.delete()
            if (!extracted) {
                return@withContext Result.failure(Exception("ZIP archive did not contain a .gguf model file"))
            }
        }

        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.PROCESSING, 0.93f, "Processing model file..."))

        if (!tempPath.renameTo(modelPath)) {
            tempPath.copyTo(modelPath, overwrite = true)
            tempPath.delete()
        }

        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.VERIFYING, 0.97f, "Verifying model integrity..."))

        if (!EmbeddingEngine.isModelFileValid(modelPath)) {
            val reason = EmbeddingEngine.getModelValidationError(modelPath)
            modelPath.delete()
            return@withContext Result.failure(Exception("Download failed - $reason"))
        }

        val loadValidation = validateEmbeddingEngineLoad(modelPath)
        if (loadValidation.isFailure) {
            val reason = loadValidation.exceptionOrNull()?.message ?: "Model failed runtime validation"

            if (modelUrl == DEFAULT_EMBEDDING_MODEL_URL) {
                onProgress(
                    EmbeddingSetupProgress(
                        EmbeddingSetupStage.FINALIZING,
                        0.98f,
                        "Default model incompatible. Retrying with all-MiniLM fallback..."
                    )
                )

                modelPath.delete()
                return@withContext downloadEmbeddingModel(
                    context = context,
                    modelUrl = FALLBACK_EMBEDDING_MODEL_URL,
                    onProgress = onProgress
                )
            }

            modelPath.delete()
            return@withContext Result.failure(
                Exception("Model is not compatible with embedding engine: $reason. $EMBEDDING_MODEL_SUGGESTIONS")
            )
        }

        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.FINALIZING, 1f, "Finalizing setup..."))

        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private suspend fun importEmbeddingModelFromUri(
    context: android.content.Context,
    modelUri: Uri,
    onProgress: (EmbeddingSetupProgress) -> Unit
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.PREPARING, 0f, "Preparing local import..."))

        val modelPath = EmbeddingEngine.getModelPath(context)
        modelPath.parentFile?.mkdirs()

        val tempPath = modelPath.resolveSibling("${modelPath.name}.part")
        if (tempPath.exists()) tempPath.delete()
        if (modelPath.exists()) modelPath.delete()

        val contentResolver = context.contentResolver
        val totalSize = contentResolver.openFileDescriptor(modelUri, "r")?.use { it.statSize } ?: -1L

        contentResolver.openInputStream(modelUri)?.use { input ->
            tempPath.outputStream().use { output ->
                val buffer = ByteArray(65_536)
                var read: Int
                var copied = 0L

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copied += read

                    if (totalSize > 0) {
                        val progress = (copied.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                        onProgress(
                            EmbeddingSetupProgress(
                                stage = EmbeddingSetupStage.PROCESSING,
                                progress = (progress * 0.9f).coerceIn(0f, 0.9f),
                                status = "Importing local model: ${(progress * 100).toInt()}%"
                            )
                        )
                    } else {
                        onProgress(
                            EmbeddingSetupProgress(
                                stage = EmbeddingSetupStage.PROCESSING,
                                progress = 0f,
                                status = "Importing local model: ${copied / (1024 * 1024)}MB"
                            )
                        )
                    }
                }
                output.flush()
            }
        } ?: return@withContext Result.failure(Exception("Unable to read selected file"))

        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.FINALIZING, 0.95f, "Finalizing local model..."))
        if (!tempPath.renameTo(modelPath)) {
            tempPath.copyTo(modelPath, overwrite = true)
            tempPath.delete()
        }

        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.VERIFYING, 0.98f, "Verifying local model..."))
        if (!EmbeddingEngine.isModelFileValid(modelPath)) {
            val reason = EmbeddingEngine.getModelValidationError(modelPath)
            modelPath.delete()
            return@withContext Result.failure(Exception("Selected file is not a valid GGUF model: $reason"))
        }

        val loadValidation = validateEmbeddingEngineLoad(modelPath)
        if (loadValidation.isFailure) {
            val reason = loadValidation.exceptionOrNull()?.message ?: "Model failed runtime validation"
            modelPath.delete()
            return@withContext Result.failure(
                Exception("Selected model is not compatible: $reason. $EMBEDDING_MODEL_SUGGESTIONS")
            )
        }

        onProgress(EmbeddingSetupProgress(EmbeddingSetupStage.FINALIZING, 1f, "Local model imported successfully"))
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun extractFirstGgufFromZip(
    zipFile: File,
    outputFile: File,
    onExtractProgress: (extractedCount: Int, totalCount: Int, entryName: String) -> Unit
): Boolean {
    val totalEntries = try {
        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            var count = 0
            while (true) {
                val entry = zipInput.nextEntry ?: break
                if (!entry.isDirectory) count++
                zipInput.closeEntry()
            }
            count
        }
    } catch (_: Exception) {
        0
    }

    ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
        var extracted = 0
        while (true) {
            val entry = zipInput.nextEntry ?: break
            if (!entry.isDirectory) {
                extracted++
                onExtractProgress(extracted, totalEntries, entry.name)

                if (entry.name.endsWith(".gguf", ignoreCase = true)) {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(65_536)
                        var read: Int
                        while (zipInput.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                    zipInput.closeEntry()
                    return true
                }
            }
            zipInput.closeEntry()
        }
    }

    return false
}

/**
 * Opens an [HttpURLConnection] to [startUrl], manually following up to 10 HTTP redirects.
 * Unlike [URL.openConnection] with [HttpURLConnection.setInstanceFollowRedirects],
 * this approach ensures we land on the final CDN URL where Content-Length is sent correctly.
 */
private fun openFinalConnection(startUrl: String): HttpURLConnection {
    var url = startUrl
    var redirects = 0
    while (true) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode in 300..399) {
            val location = conn.getHeaderField("Location")
                ?: throw IllegalStateException("Redirect with no Location header at: $url")
            conn.disconnect()
            url = location
            if (++redirects > 10) throw IllegalStateException("Too many redirects")
        } else {
            return conn
        }
    }
}

private suspend fun validateEmbeddingEngineLoad(modelFile: File): Result<Unit> {
    val validator = EmbeddingEngine()
    return try {
        val initResult = validator.initialize(EmbeddingConfig(modelPath = modelFile.absolutePath))
        if (initResult.isSuccess) {
            validator.close()
            Result.success(Unit)
        } else {
            validator.close()
            Result.failure(initResult.exceptionOrNull() ?: Exception("Unknown model init failure"))
        }
    } catch (e: Exception) {
        validator.close()
        Result.failure(e)
    }
}