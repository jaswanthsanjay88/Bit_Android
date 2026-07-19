package com.bit.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays synthesized TTS audio (WAV/PCM) via Android AudioTrack.
 * Handles WAV header parsing and completion callbacks.
 */
class AudioPlaybackManager {

    private val _playbackAmplitude = MutableStateFlow(0f)
    val playbackAmplitude: StateFlow<Float> = _playbackAmplitude.asStateFlow()

    companion object {
        private const val TAG = "AudioPlaybackManager"
        const val TTS_SAMPLE_RATE = 22050
    }

    private var audioTrack: AudioTrack? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    /**
     * Play WAV or raw PCM audio data. Suspends until playback completes.
     */
    suspend fun play(audioData: ByteArray, sampleRate: Int = TTS_SAMPLE_RATE) {
        if (audioData.isEmpty()) {
            Log.w(TAG, "No audio data to play")
            return
        }

        stop() // stop any existing playback

        isPlaying = true
        Log.i(TAG, "Starting playback (${audioData.size} bytes)")

        withContext(Dispatchers.IO) {
            try {
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                // Detect WAV header and find PCM data offset
                val headerSize = findPcmDataOffset(audioData)
                val pcmData = audioData.copyOfRange(headerSize, audioData.size)

                Log.d(TAG, "PCM data: ${pcmData.size} bytes (skipped $headerSize byte header)")

                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize.coerceAtLeast(pcmData.size))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack?.write(pcmData, 0, pcmData.size)
                audioTrack?.play()

                // Wait for playback to complete
                val durationMs = (pcmData.size.toDouble() / (sampleRate * 2) * 1000).toLong()
                Log.d(TAG, "Expected duration: ${durationMs}ms")

                val bytesPer50ms = (sampleRate * 0.05 * 2).toInt()
                var elapsed = 0L
                while (isPlaying && elapsed < durationMs &&
                    audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
                ) {
                    val sliceIndex = (elapsed / 50).toInt()
                    val startOffset = sliceIndex * bytesPer50ms
                    _playbackAmplitude.value = calculateRms(pcmData, startOffset, bytesPer50ms)
                    
                    delay(50)
                    elapsed += 50
                }

                Log.i(TAG, "Playback completed")
            } catch (e: Exception) {
                Log.e(TAG, "Playback error: ${e.message}")
            } finally {
                stopInternal()
            }
        }
    }

    private fun calculateRms(pcmData: ByteArray, startOffset: Int, length: Int): Float {
        if (length <= 0 || startOffset + length > pcmData.size) return 0f
        var sum = 0.0
        val numSamples = length / 2
        for (i in 0 until numSamples) {
            val idx = startOffset + i * 2
            if (idx + 1 >= pcmData.size) break
            val low = pcmData[idx].toInt() and 0xFF
            val high = pcmData[idx + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            sum += sample * sample
        }
        val mean = sum / numSamples
        val rms = Math.sqrt(mean)
        return (rms / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    fun stop() {
        isPlaying = false
        stopInternal()
    }

    private fun stopInternal() {
        isPlaying = false
        _playbackAmplitude.value = 0f
        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.pause()
                audioTrack?.flush()
            }
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        audioTrack = null
    }

    /**
     * Parse WAV header to find the PCM data chunk offset.
     * Returns 0 for raw PCM (no header).
     */
    private fun findPcmDataOffset(data: ByteArray): Int {
        if (data.size <= 44) return 0

        // Check RIFF header
        val isWav = data[0] == 'R'.code.toByte() &&
                data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() &&
                data[3] == 'F'.code.toByte()

        if (!isWav) return 0

        // Scan for "data" chunk
        var offset = 12 // skip RIFF header
        while (offset + 8 <= data.size) {
            val chunkId = String(data, offset, 4, Charsets.US_ASCII)
            val chunkSize = (data[offset + 4].toInt() and 0xFF) or
                    ((data[offset + 5].toInt() and 0xFF) shl 8) or
                    ((data[offset + 6].toInt() and 0xFF) shl 16) or
                    ((data[offset + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                return offset + 8
            }
            offset += 8 + chunkSize
        }
        return 44 // fallback for standard WAV
    }

    fun release() {
        stop()
    }
}
