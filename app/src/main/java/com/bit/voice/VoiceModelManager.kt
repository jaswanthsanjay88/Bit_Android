package com.bit.voice

import android.util.Log
import com.bit.data.AppSettingsDataStore
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.repo.ModelRepository
import com.bit.service.server.LocalSttEngine
import com.bit.service.server.LocalTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceModelManager"

@Singleton
class VoiceModelManager @Inject constructor(
    private val modelRepo: ModelRepository,
    private val appSettings: AppSettingsDataStore,
    private val ttsPlayer: TtsPlayer,
    private val sttRecorder: SttRecorder,
    private val ttsEngine: LocalTtsEngine,
    private val sttEngine: LocalSttEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ttsLock = Mutex()
    private val sttLock = Mutex()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val speakingId: StateFlow<String?> = ttsPlayer.speakingId
    val isRecording: StateFlow<Boolean> = sttRecorder.isRecording
    val recordingAmplitude: StateFlow<Float> = sttRecorder.amplitude

    fun clearError() { _error.value = null }

    suspend fun hasTts(): Boolean = findActiveTts() != null
    suspend fun hasStt(): Boolean = findActiveStt() != null

    suspend fun unloadStt() {
        try { sttEngine.unload() } catch (_: Exception) {}
    }

    suspend fun unloadTts() {
        ttsPlayer.stop()
        try { ttsEngine.unload() } catch (_: Exception) {}
    }

    fun sttPermissionGranted(): Boolean = sttRecorder.hasPermission()

    private suspend fun findActiveTts(): Model? {
        val models = modelRepo.getAllModels().firstOrNull()?.filter { it.providerType == ProviderType.TTS } ?: emptyList()
        if (models.isEmpty()) return null
        return models.first()
    }

    private suspend fun findActiveStt(): Model? {
        val models = modelRepo.getAllModels().firstOrNull()?.filter { it.providerType == ProviderType.STT } ?: emptyList()
        if (models.isEmpty()) return null
        return models.first()
    }

    suspend fun speak(messageId: String, text: String): Boolean {
        val ok = ensureTtsLoaded() ?: return false
        if (!ok) return false
        ttsPlayer.speak(messageId, text)
        return true
    }

    fun stopSpeaking() { ttsPlayer.stop() }

    suspend fun startRecording(): Boolean {
        if (!sttRecorder.hasPermission()) {
            _error.value = "Microphone permission required"
            return false
        }
        if (findActiveStt() == null) {
            _error.value = "No STT model installed. Import one in Voice settings."
            return false
        }
        val started = sttRecorder.start()
        if (!started) _error.value = "Failed to start recording"
        return started
    }

    fun cancelRecording() { sttRecorder.cancel() }

    suspend fun stopRecordingAndRecognize(): String? = withContext(Dispatchers.IO) {
        val samples = sttRecorder.stop()
        if (samples.isEmpty()) {
            _error.value = "No audio captured"
            return@withContext null
        }
        val loaded = ensureSttLoaded() ?: return@withContext null
        if (!loaded) return@withContext null
        val text = sttEngine.recognize(samples, SttRecorder.SAMPLE_RATE)
        if (text == null) {
            _error.value = "Transcription failed"
        } else if (text.isBlank()) {
            _error.value = "No speech detected"
        }
        text
    }

    private suspend fun ensureTtsLoaded(): Boolean? = ttsLock.withLock {
        val model = findActiveTts() ?: run {
            _error.value = "No TTS model installed. Import one in Voice settings."
            return@withLock null
        }
        if (ttsEngine.isLoaded && ttsEngine.loadedId() == model.id) return@withLock true
        val configJson = modelRepo.getConfigByModelId(model.id)?.modelLoadingParams ?: "{}"
        if (configJson.isBlank() || configJson == "{}") {
            _error.value = "TTS model ${model.modelName} has no config. Re-import it."
            return@withLock false
        }
        try {
            val ok = ttsEngine.ensureLoaded(model.id, configJson)
            if (!ok) _error.value = "Failed to load TTS model ${model.modelName}"
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "loadTtsModel failed", t)
            _error.value = t.message ?: "TTS load failed"
            false
        }
    }

    private suspend fun ensureSttLoaded(): Boolean? = sttLock.withLock {
        val model = findActiveStt() ?: run {
            _error.value = "No STT model installed. Import one in Voice settings."
            return@withLock null
        }
        if (sttEngine.isLoaded && sttEngine.loadedId() == model.id) return@withLock true
        val configJson = modelRepo.getConfigByModelId(model.id)?.modelLoadingParams ?: "{}"
        if (configJson.isBlank() || configJson == "{}") {
            _error.value = "STT model ${model.modelName} has no config. Re-import it."
            return@withLock false
        }
        try {
            val ok = sttEngine.ensureLoaded(model.id, configJson)
            if (!ok) _error.value = "Failed to load STT model ${model.modelName}"
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "loadSttModel failed", t)
            _error.value = t.message ?: "STT load failed"
            false
        }
    }
}
