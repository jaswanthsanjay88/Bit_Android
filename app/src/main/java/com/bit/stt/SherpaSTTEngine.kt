package com.bit.stt

import android.content.Context
import android.util.Log
import com.bit.data.AppSettingsDataStore
import com.bit.global.AppPaths
import com.dark.ai_sherpa.OfflineModelConfig
import com.dark.ai_sherpa.OfflineRecognizer
import com.dark.ai_sherpa.OfflineRecognizerConfig
import com.dark.ai_sherpa.OfflineWhisperModelConfig
import com.dark.ai_sherpa.OfflineStream
import com.dark.ai_sherpa.OfflineRecognizerResult
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

    @Volatile
    private var persistentRecognizer: OfflineRecognizer? = null

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
        com.bit.tts.TTSManager.loadNativeLibraries(context)
        val ready = hasModelFiles(context)
        _isModelLoaded.value = ready
        return ready
    }

    /**
     * Warm-load the Whisper STT recognizer for a continuous Live Voice session.
     */
    suspend fun enterLiveSession(context: Context): Boolean = withContext(Dispatchers.IO) {
        com.bit.tts.TTSManager.loadNativeLibraries(context)
        if (!hasModelFiles(context)) return@withContext false
        try {
            if (persistentRecognizer != null) return@withContext true
            val appSettings = AppSettingsDataStore(context)
            val numThreads = appSettings.sttThreads.first().coerceIn(1, 4)
            val language = appSettings.sttLanguage.first()
            val modelDir = AppPaths.sttModel(context).absolutePath

            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$modelDir/$ENCODER_FILE",
                        decoder = "$modelDir/$DECODER_FILE",
                        language = if (language == "auto") "" else language,
                        task = "transcribe",
                        tailPaddings = -1
                    ),
                    tokens = "$modelDir/$TOKENS_FILE",
                    numThreads = numThreads
                )
            )
            persistentRecognizer = OfflineRecognizer.fromFile(config)
            Log.d(TAG, "SherpaSTTEngine live session entered (model kept warm in RAM)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter STT live session", e)
            false
        }
    }

    /**
     * Close and reclaim persistent STT recognizer RAM upon exiting Live Voice session.
     */
    fun exitLiveSession() {
        try {
            persistentRecognizer?.close()
            persistentRecognizer = null
            Log.d(TAG, "SherpaSTTEngine live session exited (RAM reclaimed)")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing persistent recognizer: ${e.message}")
        }
    }

    /**
     * Transcribe PCM 16kHz 16-bit mono audio data to text.
     * Automatically uses persistent live recognizer if warm, or one-shot load/unload.
     */
    suspend fun transcribe(context: Context, audioData: ByteArray): String = withContext(Dispatchers.Default) {
        com.bit.tts.TTSManager.loadNativeLibraries(context)
        if (!hasModelFiles(context)) {
            Log.w(TAG, "Cannot transcribe: Model files not available")
            return@withContext ""
        }

        _isProcessing.value = true
        var tempRecognizer: OfflineRecognizer? = null
        try {
            val activeRecognizer = persistentRecognizer ?: run {
                val appSettings = AppSettingsDataStore(context)
                val numThreads = appSettings.sttThreads.first().coerceIn(1, 4)
                val language = appSettings.sttLanguage.first()
                val modelDir = AppPaths.sttModel(context).absolutePath

                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "$modelDir/$ENCODER_FILE",
                            decoder = "$modelDir/$DECODER_FILE",
                            language = if (language == "auto") "" else language,
                            task = "transcribe",
                            tailPaddings = -1
                        ),
                        tokens = "$modelDir/$TOKENS_FILE",
                        numThreads = numThreads
                    )
                )
                OfflineRecognizer.fromFile(config).also { tempRecognizer = it }
            }

            val samples = pcmToFloat(audioData)
            if (samples.isEmpty()) {
                Log.d(TAG, "Empty audio samples")
                return@withContext ""
            }

            val stream = activeRecognizer.createStream()
            stream.acceptWaveform(sampleRate = 16000, samples = samples)
            activeRecognizer.decode(stream)
            val result = activeRecognizer.getResult(stream)
            val text = result.text
            stream.close()

            cleanTranscribedText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            ""
        } finally {
            try {
                tempRecognizer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing temp recognizer: ${e.message}")
            }
            _isProcessing.value = false
        }
    }

    /**
     * Clean Whisper STT output by stripping noise/music tags and silence hallucinations.
     */
    fun cleanTranscribedText(rawText: String): String {
        var text = rawText.trim()
        text = text.replace(Regex("(?i)\\[(sound|music|noise|laughter|blank_audio|applause|cheering|sigh|snicker|cough|groan|gasp|throat clearing|audio|video|background noise|unintelligible|inaudible)]"), "")
        text = text.replace(Regex("(?i)\\((sound|music|noise|laughter|blank_audio|applause|cheering|sigh|snicker|cough|groan|gasp|throat clearing|audio|video|background noise|unintelligible|inaudible)\\)"), "")
        text = text.trim()

        val lower = text.lowercase().removeSuffix(".")
        val silenceHallucinations = setOf(
            "thank you", "thanks for watching", "subtitles by", "subtitles by amara.org",
            "amara.org", "mbc", "bye", "subscribe", "like and subscribe"
        )
        if (lower in silenceHallucinations) {
            return ""
        }

        if (text.length < 2 || !text.any { it.isLetterOrDigit() }) {
            return ""
        }

        return text
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
