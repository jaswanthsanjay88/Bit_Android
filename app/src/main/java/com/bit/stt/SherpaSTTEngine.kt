package com.bit.stt

import android.content.Context
import android.util.Log
import com.bit.data.AppSettingsDataStore
import com.bit.global.AppPaths
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sherpa ONNX Whisper STT engine.
 *
 * Wraps the Sherpa ONNX offline recognizer for on-device speech-to-text.
 * Automatically loads the recognizer on-demand for transcription and unloads
 * immediately after to maintain a small RAM footprint (~75 MB).
 */
object SherpaSTTEngine {

    private const val TAG = "SherpaSTTEngine"

    // Model file names
    private const val ENCODER_FILE = "encoder.int8.onnx"
    private const val DECODER_FILE = "decoder.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    const val MODEL_REPO = "csukuangfj/sherpa-onnx-whisper-tiny.en"
    const val MODEL_DISPLAY_NAME = "Whisper Tiny (English)"
    const val MODEL_ID = "sherpa-whisper-tiny"

    val MODEL_FILES = listOf(
        ModelFileInfo(ENCODER_FILE, "$MODEL_REPO/resolve/main/$ENCODER_FILE", "Encoder"),
        ModelFileInfo(DECODER_FILE, "$MODEL_REPO/resolve/main/$DECODER_FILE", "Decoder"),
        ModelFileInfo(TOKENS_FILE, "$MODEL_REPO/resolve/main/$TOKENS_FILE", "Tokens")
    )

    data class ModelFileInfo(
        val fileName: String,
        val downloadUrl: String,
        val displayName: String
    )

    // ── State ──

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isLibraryAvailable = MutableStateFlow(true)
    val isLibraryAvailable: StateFlow<Boolean> = _isLibraryAvailable.asStateFlow()

    /**
     * Check if model files are downloaded and ready.
     */
    fun hasModelFiles(context: Context): Boolean {
        val dir = AppPaths.sttModel(context)
        return dir.exists() &&
                File(dir, ENCODER_FILE).exists() &&
                File(dir, DECODER_FILE).exists() &&
                File(dir, TOKENS_FILE).exists()
    }

    /**
     * Initialize/verify status of STT model files.
     */
    fun initialize(context: Context): Boolean {
        val ready = hasModelFiles(context)
        _isModelLoaded.value = ready
        return ready
    }

    /**
     * Transcribe PCM 16kHz 16-bit mono audio data to text.
     * Automatically loads and releases the recognizer to minimize RAM.
     *
     * @param context Android context
     * @param audioData Raw PCM bytes (16kHz, mono, 16-bit little-endian)
     * @return Transcribed text, or empty string if error
     */
    suspend fun transcribe(context: Context, audioData: ByteArray): String = withContext(Dispatchers.Default) {
        if (!hasModelFiles(context)) {
            Log.w(TAG, "Cannot transcribe: Model files not available")
            return@withContext ""
        }

        _isProcessing.value = true
        var recognizer: OfflineRecognizer? = null
        try {
            val appSettings = AppSettingsDataStore(context)
            val numThreads = appSettings.sttThreads.first().coerceIn(1, 4)
            val language = appSettings.sttLanguage.first()

            val modelDir = AppPaths.sttModel(context).absolutePath

            val whisperConfig = OfflineWhisperModelConfig(
                encoder = "$modelDir/$ENCODER_FILE",
                decoder = "$modelDir/$DECODER_FILE",
                language = if (language == "auto") "" else language,
                task = "transcribe",
                tailPaddings = -1
            )

            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = "$modelDir/$TOKENS_FILE",
                numThreads = numThreads
            )

            val config = OfflineRecognizerConfig(
                modelConfig = modelConfig
            )

            // Dynamic load of Whisper model
            recognizer = OfflineRecognizer(context.assets, config)

            // Convert PCM byte array to float array (normalized to [-1, 1])
            val samples = pcmToFloat(audioData)

            if (samples.isEmpty()) {
                Log.d(TAG, "Empty audio samples")
                return@withContext ""
            }

            // Create an OfflineStream
            val stream = recognizer.createStream()

            // Accept waveform
            stream.acceptWaveform(samples, sampleRate = 16000)

            // Decode
            recognizer.decode(stream)

            // Get result
            val result = recognizer.getResult(stream)
            val text = result?.text ?: ""

            // Release stream explicitly as memory is managed by C++
            stream.release()

            Log.i(TAG, "Transcription result (threads=$numThreads, lang=$language): '${text.take(100)}'")
            text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            ""
        } finally {
            // Unload/release recognizer immediately to reclaim ~75MB RAM
            try {
                recognizer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing recognizer: ${e.message}")
            }
            _isProcessing.value = false
        }
    }

    /**
     * Convert 16-bit PCM byte array to normalized float array.
     */
    private fun pcmToFloat(audioData: ByteArray): FloatArray {
        val shortBuffer = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val floats = FloatArray(shortBuffer.remaining())
        for (i in floats.indices) {
            floats[i] = shortBuffer.get(i).toFloat() / Short.MAX_VALUE
        }
        return floats
    }

    /**
     * Release references.
     */
    fun release() {
        _isModelLoaded.value = false
        _isProcessing.value = false
        Log.d(TAG, "SherpaSTTEngine released")
    }

    /**
     * Get a user-friendly status message.
     */
    fun getStatusMessage(context: Context): String {
        return when {
            hasModelFiles(context) -> "Whisper ready"
            else -> "STT model not downloaded"
        }
    }
}
