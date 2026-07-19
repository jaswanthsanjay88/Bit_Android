package com.bit.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Captures audio from the device microphone at 16kHz mono 16-bit PCM.
 * Emits audio data as a Flow of ByteArray chunks for STT consumption.
 */
class AudioCaptureService(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureService"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_MS = 100
    }

    private var audioRecord: AudioRecord? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecordingState: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    fun hasRecordPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Start capturing audio. Returns a Flow that emits PCM byte chunks every [CHUNK_SIZE_MS] ms.
     */
    fun startCapture(): Flow<ByteArray> = callbackFlow {
        if (!hasRecordPermission()) {
            Log.e(TAG, "No RECORD_AUDIO permission")
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val chunkSize = (SAMPLE_RATE * 2 * CHUNK_SIZE_MS) / 1000

        try {
            var record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize.coerceAtLeast(chunkSize * 2)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize.coerceAtLeast(chunkSize * 2)
                )
            }
            audioRecord = record

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                close(IllegalStateException("AudioRecord initialization failed"))
                return@callbackFlow
            }

            // Enable Acoustic Echo Cancellation (AEC) if available
            try {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    aec = android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                    aec?.enabled = true
                    Log.i(TAG, "Acoustic Echo Canceler enabled (session ID: ${audioRecord!!.audioSessionId})")
                } else {
                    Log.w(TAG, "Acoustic Echo Canceler is not available on this device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable Acoustic Echo Canceler: ${e.message}", e)
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            Log.i(TAG, "Audio capture started (${SAMPLE_RATE}Hz, chunk=$chunkSize bytes)")

            val readJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(chunkSize)
                while (isActive && _isRecording.value) {
                    val bytesRead = audioRecord?.read(buffer, 0, chunkSize) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        _audioLevel.value = calculateRMS(chunk)
                        trySend(chunk)
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            }

            awaitClose {
                Log.d(TAG, "Flow closing, stopping audio capture")
                readJob.cancel()
                stopCaptureInternal()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio capture: ${e.message}")
            stopCaptureInternal()
            close(e)
        }
    }

    fun stopCapture() {
        _isRecording.value = false
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        try {
            aec?.enabled = false
            aec?.release()
            aec = null
            Log.d(TAG, "Acoustic Echo Canceler released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Acoustic Echo Canceler: ${e.message}")
        }
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            _isRecording.value = false
            _audioLevel.value = 0f
            Log.d(TAG, "Audio capture stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture: ${e.message}")
        }
    }

    /**
     * Calculate RMS (Root Mean Square) for audio level visualization.
     * Returns a value in range 0.0 .. ~0.3 for typical speech.
     */
    fun calculateRMS(audioData: ByteArray): Float {
        if (audioData.isEmpty()) return 0f
        val shorts = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        var sum = 0.0
        while (shorts.hasRemaining()) {
            val sample = shorts.get().toFloat() / Short.MAX_VALUE
            sum += sample * sample
        }
        return sqrt(sum / (audioData.size / 2)).toFloat()
    }

    fun isRecording(): Boolean = isRecordingState.value

    fun release() {
        stopCapture()
    }
}
