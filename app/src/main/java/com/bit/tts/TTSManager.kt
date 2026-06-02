package com.bit.tts

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.bit.global.AppPaths
import com.bit.service.AudioPlaybackManager
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SuppressLint("StaticFieldLeak")
object TTSManager {

    private const val TAG = "TTSManager"

    private var tts: OfflineTts? = null
    private var context: Context? = null
    private val playbackManager = AudioPlaybackManager()
    private var speakJob: Job? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPlayingMsgId = MutableStateFlow<String?>(null)
    val currentPlayingMsgId: StateFlow<String?> = _currentPlayingMsgId.asStateFlow()

    private val _synthProgress = MutableStateFlow(0f)
    val synthProgress: StateFlow<Float> = _synthProgress.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    fun init(appContext: Context, autoLoad: Boolean = true) {
        context = appContext.applicationContext
        Log.d(TAG, "TTSManager initialized (sherpa-onnx OfflineTts)")

        // Populate available voices (e.g. speaker IDs 0..9)
        _availableVoices.value = (0..9).map { it.toString() }

        if (autoLoad) {
            val modelsDir = AppPaths.ttsModel(appContext)
            if (modelsDir.exists() && modelsDir.isDirectory) {
                val success = loadModel(modelsDir.absolutePath)
                Log.d(TAG, "Auto-loaded TTS model: $success")
            }
        }
    }

    fun loadModel(modelDir: String, useNNAPI: Boolean = false): Boolean {
        val ctx = context ?: return false

        // Clean up previous OfflineTts instance to avoid native double-destroy crash / memory leak
        try {
            tts?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing old OfflineTts instance", e)
        }
        tts = null
        _isModelLoaded.value = false

        return try {
            val dir = File(modelDir)
            if (!dir.exists() || !dir.isDirectory) {
                Log.e(TAG, "Model directory not found: $modelDir")
                return false
            }

            val modelFile = findFileRecursive(dir) { it.name.endsWith(".onnx") }
            val tokensFile = findFileRecursive(dir) { it.name == "tokens.txt" }
            val voicesFile = findFileRecursive(dir) { it.name == "voices.bin" }
            val espeakDir = findDirRecursive(dir, "espeak-ng-data")
            val dictDir = findDirRecursive(dir, "dict")

            // Lexicon can be single (lexicon.txt) or multiple comma-separated (for Kokoro v1.0+)
            val lexiconFiles = findFilesRecursive(dir) {
                it.name == "lexicon.txt" || (it.name.startsWith("lexicon-") && it.name.endsWith(".txt"))
            }

            if (modelFile == null || tokensFile == null) {
                Log.e(TAG, "Missing model files (onnx/tokens) in $modelDir")
                return false
            }

            val config = if (voicesFile != null) {
                // Kokoro model configuration
                Log.d(TAG, "Configuring Kokoro model with voices: ${voicesFile.name}")
                val lexiconPaths = lexiconFiles.joinToString(",") { it.absolutePath }
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model = modelFile.absolutePath,
                    voices = voicesFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    dataDir = espeakDir?.absolutePath ?: "",
                    lexicon = lexiconPaths,
                    lang = "en",
                    dictDir = dictDir?.absolutePath ?: "",
                    lengthScale = 1.0f
                )
                val modelConfig = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(), // empty VITS config
                    kokoro = kokoroConfig,
                    numThreads = 2,
                    debug = false
                )
                OfflineTtsConfig(
                    model = modelConfig,
                    ruleFsts = ""
                )
            } else {
                // VITS / Piper model configuration
                Log.d(TAG, "Configuring VITS/Piper model: ${modelFile.name}")
                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    lexicon = lexiconFiles.firstOrNull { it.name == "lexicon.txt" }?.absolutePath 
                        ?: lexiconFiles.firstOrNull()?.absolutePath ?: "",
                    tokens = tokensFile.absolutePath,
                    dataDir = espeakDir?.absolutePath ?: "",
                    dictDir = "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                )
                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = 2,
                    debug = false
                )
                OfflineTtsConfig(
                    model = modelConfig,
                    ruleFsts = ""
                )
            }

            tts = OfflineTts(null, config)
            val numSpeakers = tts?.numSpeakers() ?: 1
            _availableVoices.value = (0 until numSpeakers).map { it.toString() }
            _isModelLoaded.value = true
            Log.d(TAG, "TTS model loaded from $modelDir with $numSpeakers speakers")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TTS model", e)
            _isModelLoaded.value = false
            false
        }
    }

    fun isLoaded(): Boolean = _isModelLoaded.value

    private data class PlaybackChunk(val pcm: ByteArray, val sampleRate: Int)

    suspend fun speak(text: String, settings: TTSSettings = TTSSettings(), msgId: String? = null) {
        val currentTts = tts ?: return
        if (!_isModelLoaded.value) {
            Log.w(TAG, "TTS model not loaded, cannot speak")
            return
        }

        stopPlayback()

        _currentPlayingMsgId.value = msgId
        _isPlaying.value = true
        _isSynthesizing.value = true
        _synthProgress.value = 0f

        // Chunk text at sentence boundaries (./?!/…/;/\n)
        val regex = Regex("(?<=[.?!\\n…;])\\s*|(?<=/)\\s*")
        val sentences = text.split(regex).map { it.trim() }.filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            _isPlaying.value = false
            _currentPlayingMsgId.value = null
            _isSynthesizing.value = false
            return
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val chunkChannel = Channel<PlaybackChunk>(Channel.UNLIMITED)

        speakJob = scope.launch {
            // Producer: Synthesize chunks sequentially on Dispatchers.IO
            val producer = launch(Dispatchers.IO) {
                try {
                    val numSpeakers = currentTts.numSpeakers()
                    val sid = (settings.voice.toIntOrNull() ?: 0).coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
                    val speed = settings.speed.coerceIn(0.5f, 2.0f)
                    
                    sentences.forEachIndexed { index, sentence ->
                        if (speakJob?.isCancelled == true || !_isPlaying.value) return@forEachIndexed
                        
                        Log.d(TAG, "Synthesizing chunk $index: '$sentence'")
                        val audio = currentTts.generate(sentence, sid, speed)
                        val pcm = floatToPcm(audio.samples)
                        chunkChannel.send(PlaybackChunk(pcm, audio.sampleRate))
                        
                        _synthProgress.value = (index + 1).toFloat() / sentences.size
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during synthesis", e)
                } finally {
                    chunkChannel.close()
                    _isSynthesizing.value = false
                }
            }

            // Consumer: Play chunks sequentially via AudioPlaybackManager
            try {
                for (chunk in chunkChannel) {
                    if (speakJob?.isCancelled == true || !_isPlaying.value) break
                    playbackManager.play(chunk.pcm, chunk.sampleRate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during playback", e)
            } finally {
                producer.join()
                _isPlaying.value = false
                _currentPlayingMsgId.value = null
                _isSynthesizing.value = false
            }
        }

        speakJob?.join()
    }

    fun stopPlayback() {
        speakJob?.cancel()
        speakJob = null
        playbackManager.stop()
        _isPlaying.value = false
        _currentPlayingMsgId.value = null
        _isSynthesizing.value = false
        _synthProgress.value = 0f
    }

    fun getModelDirectory(): String? {
        val ctx = context ?: return null
        val dir = AppPaths.ttsModel(ctx)
        return if (dir.exists()) dir.absolutePath else null
    }

    private fun floatToPcm(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            val shortVal = (f.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(shortVal)
        }
        return bytes
    }

    private fun findFileRecursive(dir: File, predicate: (File) -> Boolean): File? {
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && predicate(f)) {
                return f
            }
        }
        for (f in files) {
            if (f.isDirectory) {
                val found = findFileRecursive(f, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findDirRecursive(dir: File, dirName: String): File? {
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory) {
                if (f.name == dirName) {
                    return f
                }
                val found = findDirRecursive(f, dirName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFilesRecursive(dir: File, predicate: (File) -> Boolean): List<File> {
        val list = mutableListOf<File>()
        findFilesRecursiveHelper(dir, predicate, list)
        return list
    }

    private fun findFilesRecursiveHelper(dir: File, predicate: (File) -> Boolean, list: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && predicate(f)) {
                list.add(f)
            } else if (f.isDirectory) {
                findFilesRecursiveHelper(f, predicate, list)
            }
        }
    }
}
