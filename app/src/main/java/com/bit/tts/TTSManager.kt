package com.bit.tts

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.bit.di.AppContainer
import com.bit.global.AppPaths
import com.bit.service.AudioPlaybackManager
import com.dark.ai_sherpa.OfflineTts
import com.dark.ai_sherpa.OfflineTtsConfig
import com.dark.ai_sherpa.OfflineTtsModelConfig
import com.dark.ai_sherpa.OfflineTtsVitsModelConfig
import com.dark.ai_sherpa.OfflineTtsKokoroModelConfig
import com.dark.ai_sherpa.GeneratedAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SuppressLint("StaticFieldLeak")
object TTSManager {

    private const val TAG = "TTSManager"

    private val nativeLock = Any()
    @Volatile
    private var nativeLoaded = false
    private val supportedChars = HashSet<Char>()

    @Volatile
    private var tts: OfflineTts? = null
    private var context: Context? = null
    private val playbackManager = AudioPlaybackManager()
    private var speakJob: Job? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    val playbackAmplitude: StateFlow<Float> = playbackManager.playbackAmplitude

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

    @JvmStatic
    private external fun loadLibraryGlobal(libPath: String): Boolean

    @JvmStatic
    fun loadNativeLibraries(appContext: Context? = null) {
        val ctx = appContext ?: context
        if (ctx == null) {
            Log.w(TAG, "loadNativeLibraries called without Context — deferring until init()")
            return
        }
        synchronized(nativeLock) {
            if (nativeLoaded) return
            try {
                System.loadLibrary("file_ops")
                val nativeDir = ctx.applicationInfo.nativeLibraryDir
                val loadedOnnx = loadLibraryGlobal("$nativeDir/libonnxruntime.so")
                Log.d(TAG, "loadLibraryGlobal(libonnxruntime.so): $loadedOnnx")
                val loadedOnnxJni = loadLibraryGlobal("$nativeDir/libonnxruntime4j_jni.so")
                Log.d(TAG, "loadLibraryGlobal(libonnxruntime4j_jni.so): $loadedOnnxJni")
                val loadedSherpa = loadLibraryGlobal("$nativeDir/libai_sherpa.so")
                Log.d(TAG, "loadLibraryGlobal(libai_sherpa.so): $loadedSherpa")
            } catch (e: Throwable) {
                Log.w(TAG, "loadLibraryGlobal failed: ${e.message}")
            }
            try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
            try { System.loadLibrary("onnxruntime") } catch (_: Throwable) {}
            try { System.loadLibrary("onnxruntime4j_jni") } catch (_: Throwable) {}
            try {
                System.loadLibrary("ai_sherpa")
                nativeLoaded = true
                Log.d(TAG, "Loaded libai_sherpa.so successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "ai_sherpa System.loadLibrary failed: ${e.message}")
            }
        }
    }

    fun init(appContext: Context, autoLoad: Boolean = true) {
        context = appContext.applicationContext
        Log.d(TAG, "TTSManager initialized (sherpa-onnx OfflineTts)")
        loadNativeLibraries(appContext)

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
        val ctx = context
        loadNativeLibraries(ctx)
        if (ctx == null) return false

        stopPlayback()

        // Clean up previous OfflineTts instance to avoid native double-destroy crash / memory leak
        synchronized(nativeLock) {
            try {
                tts?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing old OfflineTts instance", e)
            }
            tts = null
            _isModelLoaded.value = false
        }

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

            val lexiconPath = lexiconFiles.firstOrNull { it.name == "lexicon.txt" }?.absolutePath 
                ?: lexiconFiles.firstOrNull()?.absolutePath ?: ""

            val isKokoro = voicesFile != null

            val vitsConfig = if (isKokoro) {
                OfflineTtsVitsModelConfig()
            } else {
                OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    lexicon = lexiconPath,
                    tokens = tokensFile.absolutePath,
                    dataDir = espeakDir?.absolutePath ?: ""
                )
            }

            val kokoroConfig = if (isKokoro) {
                val dictDirPath = dictDir?.absolutePath ?: lexiconFiles.firstOrNull()?.parentFile?.absolutePath ?: ""
                val kokoroLexiconPath = lexiconFiles.map { it.absolutePath }.joinToString(",")
                OfflineTtsKokoroModelConfig(
                    model = modelFile.absolutePath,
                    voices = voicesFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    dataDir = espeakDir?.absolutePath ?: "",
                    dictDir = dictDirPath,
                    lexicon = kokoroLexiconPath,
                    lengthScale = 1.0f
                )
            } else {
                OfflineTtsKokoroModelConfig()
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    kokoro = kokoroConfig,
                    numThreads = 2,
                    debug = true,
                    provider = "cpu"
                )
            )

            val newTts = synchronized(nativeLock) {
                OfflineTts.fromFile(config)
            }
            tts = newTts
            
            // Parse tokens.txt to build supported character vocabulary
            synchronized(nativeLock) {
                supportedChars.clear()
                try {
                    tokensFile.forEachLine { line ->
                        val index = line.lastIndexOf(' ')
                        if (index > 0) {
                            val token = line.substring(0, index)
                            for (ch in token) {
                                supportedChars.add(ch.lowercaseChar())
                                supportedChars.add(ch.uppercaseChar())
                            }
                        }
                    }
                    Log.d(TAG, "Parsed ${supportedChars.size} supported characters from tokens.txt")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing tokens.txt", e)
                }
            }

            val numSpeakers = synchronized(nativeLock) {
                newTts.numSpeakers
            }
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

    private val CODE_FENCE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`[^`]*`")
    private val EMPHASIS = Regex("[*_]{1,3}")
    private val LINK = Regex("\\[([^]]+)]\\([^)]+\\)")
    private val HEADER = Regex("(?m)^#+\\s*")
    private val WHITESPACE = Regex("\\s+")

    private fun sanitize(text: String): String {
        val noCode = text.replace(CODE_FENCE, " ")
        val noInlineCode = noCode.replace(INLINE_CODE, " ")
        val noEmphasis = noInlineCode.replace(EMPHASIS, "")
        val noLinks = noEmphasis.replace(LINK) { it.groupValues[1] }
        val noHeaders = noLinks.replace(HEADER, "")
        return noHeaders.replace(WHITESPACE, " ").trim()
    }

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

        val cleaned = sanitize(text)

        // Chunk text at sentence boundaries (./?!/…/;/\n)
        val regex = Regex("(?<=[.?!\\n…;])\\s*|(?<=/)\\s*")
        val rawSentences = cleaned.split(regex)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { char -> char.isLetterOrDigit() } }

        val sentences = mutableListOf<String>()
        for (s in rawSentences) {
            sentences.addAll(chunkSentence(s, maxChars = 150))
        }

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
                    val numSpeakers = synchronized(nativeLock) {
                        if (currentTts == tts) currentTts.numSpeakers else 0
                    }
                    val sid = (settings.voice.toIntOrNull() ?: 0).coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
                    val speed = settings.speed.coerceIn(0.5f, 2.0f)
                    
                    sentences.forEachIndexed { index, sentence ->
                        if (speakJob?.isCancelled == true || !_isPlaying.value || currentTts != tts) return@forEachIndexed
                        
                        // Check if the sentence has any letters or digits that are supported by the model's token vocabulary
                        val isSupported = synchronized(nativeLock) {
                            if (supportedChars.isNotEmpty()) {
                                sentence.any { char -> char.isLetterOrDigit() && supportedChars.contains(char) }
                            } else {
                                sentence.any { char -> char.isLetterOrDigit() }
                            }
                        }
                        if (!isSupported) {
                            Log.w(TAG, "Skipping sentence with no supported characters: '$sentence'")
                            return@forEachIndexed
                        }

                        Log.d(TAG, "Synthesizing chunk $index: '$sentence'")
                        val audio = synchronized(nativeLock) {
                            if (speakJob?.isCancelled == true || !_isPlaying.value || currentTts != tts) {
                                null
                            } else {
                                currentTts.generate(sentence, sid, speed)
                            }
                        }
                        if (audio == null) return@forEachIndexed
                        
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
        
        // 1. Try to find the active TTS model in the metadata repository
        try {
            val repository = com.bit.di.AppContainer.getModelRepository()
            val activeModelPath = kotlinx.coroutines.runBlocking {
                try {
                    val models = repository.getAllModels().firstOrNull()
                    val activeModel = models?.find { it.providerType == com.bit.models.enums.ProviderType.TTS && it.isActive }
                    activeModel?.modelPath
                } catch (e: Exception) {
                    null
                }
            }
            if (activeModelPath != null) {
                val dir = File(activeModelPath)
                if (dir.exists()) return dir.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active TTS model from DB, falling back", e)
        }

        // 2. Fallback to legacy path
        val legacyDir = AppPaths.ttsModel(ctx)
        return if (legacyDir.exists()) legacyDir.absolutePath else null
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

    private fun chunkSentence(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val chunks = mutableListOf<String>()
        val words = sentence.split(" ")
        var currentChunk = StringBuilder()
        for (word in words) {
            if (word.length > maxChars) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk = StringBuilder()
                }
                var i = 0
                while (i < word.length) {
                    val end = (i + maxChars).coerceAtMost(word.length)
                    chunks.add(word.substring(i, end))
                    i += maxChars
                }
                continue
            }
            if (currentChunk.isNotEmpty() && currentChunk.length + word.length + 1 > maxChars) {
                chunks.add(currentChunk.toString())
                currentChunk = StringBuilder()
            }
            if (currentChunk.isNotEmpty()) {
                currentChunk.append(" ")
            }
            currentChunk.append(word)
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }
        return chunks
    }
}
