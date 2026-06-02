package com.bit.engine

import kotlin.math.*

/**
 * Pure Kotlin mel spectrogram computation for Whisper STT.
 * Converts 16kHz Float32 audio to 80-channel log-mel spectrogram.
 *
 * Whisper expects: 80 mel bins, 25ms window (400 samples), 10ms hop (160 samples),
 * padded/trimmed to exactly 3000 frames (30 seconds).
 */
object MelSpectrogram {

    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 400
    private const val HOP_LENGTH = 160
    private const val N_MELS = 80
    private const val CHUNK_LENGTH_S = 30
    private const val N_FRAMES = CHUNK_LENGTH_S * SAMPLE_RATE / HOP_LENGTH // 3000

    // Pre-computed Hann window
    private val hannWindow: FloatArray by lazy {
        FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
        }
    }

    // Pre-computed mel filterbank
    private val melFilters: Array<FloatArray> by lazy {
        createMelFilterbank(SAMPLE_RATE, N_FFT, N_MELS)
    }

    /**
     * Compute log-mel spectrogram from float32 audio samples.
     * Returns a FloatArray of shape [N_MELS x N_FRAMES] in row-major order.
     */
    fun compute(audioSamples: FloatArray): FloatArray {
        // Pad or trim to exactly 30 seconds
        val targetLength = SAMPLE_RATE * CHUNK_LENGTH_S
        val padded = if (audioSamples.size >= targetLength) {
            audioSamples.copyOf(targetLength)
        } else {
            FloatArray(targetLength).also {
                audioSamples.copyInto(it)
            }
        }

        // STFT
        val numFrames = N_FRAMES
        val fftSize = N_FFT / 2 + 1 // 201 frequency bins

        // Compute magnitude spectrogram
        val magnitudes = Array(numFrames) { FloatArray(fftSize) }
        val realBuf = FloatArray(N_FFT)
        val imagBuf = FloatArray(N_FFT)

        for (frame in 0 until numFrames) {
            val start = frame * HOP_LENGTH
            // Apply Hann window
            for (i in 0 until N_FFT) {
                val sampleIdx = start + i
                realBuf[i] = if (sampleIdx < padded.size) padded[sampleIdx] * hannWindow[i] else 0f
                imagBuf[i] = 0f
            }
            // In-place FFT
            fft(realBuf, imagBuf, N_FFT)
            // Compute magnitude squared
            for (k in 0 until fftSize) {
                magnitudes[frame][k] = realBuf[k] * realBuf[k] + imagBuf[k] * imagBuf[k]
            }
        }

        // Apply mel filterbank: [N_MELS x fftSize] * [fftSize x numFrames] -> [N_MELS x numFrames]
        val melSpec = FloatArray(N_MELS * numFrames)
        for (mel in 0 until N_MELS) {
            val filter = melFilters[mel]
            for (frame in 0 until numFrames) {
                var sum = 0f
                for (k in 0 until fftSize) {
                    sum += filter[k] * magnitudes[frame][k]
                }
                melSpec[mel * numFrames + frame] = sum
            }
        }

        // Log scale with clamping
        val maxVal = melSpec.maxOrNull() ?: 1e-10f
        val logOffset = 1e-10f
        for (i in melSpec.indices) {
            melSpec[i] = ln((melSpec[i].coerceAtLeast(logOffset)).toDouble()).toFloat()
        }

        // Normalize: (x - max) / (max - min) * 4 + 4 (Whisper normalization)
        val logMax = melSpec.maxOrNull() ?: 0f
        val logMin = (logMax - 8.0f) // clamp floor
        for (i in melSpec.indices) {
            melSpec[i] = melSpec[i].coerceAtLeast(logMin)
            melSpec[i] = (melSpec[i] - logMin) / (logMax - logMin) * 4.0f - 4.0f
        }

        return melSpec
    }

    /**
     * Convert PCM 16-bit byte array to float32 samples normalized to [-1, 1].
     */
    fun pcmToFloat(pcmData: ByteArray): FloatArray {
        val numSamples = pcmData.size / 2
        val floats = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            floats[i] = sample.toFloat() / 32768f
        }
        return floats
    }

    // -- FFT (Cooley-Tukey radix-2, in-place) --

    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m; m /= 2
            }
            j += m
        }

        // FFT butterfly
        var step = 2
        while (step <= n) {
            val halfStep = step / 2
            val angleStep = -2.0 * PI / step
            for (k in 0 until halfStep) {
                val angle = angleStep * k
                val wr = cos(angle).toFloat()
                val wi = sin(angle).toFloat()
                var i = k
                while (i < n) {
                    val jj = i + halfStep
                    val tr = wr * real[jj] - wi * imag[jj]
                    val ti = wr * imag[jj] + wi * real[jj]
                    real[jj] = real[i] - tr
                    imag[jj] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
            }
            step *= 2
        }
    }

    // -- Mel filterbank creation --

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    private fun createMelFilterbank(
        sampleRate: Int, nFft: Int, nMels: Int
    ): Array<FloatArray> {
        val fftSize = nFft / 2 + 1
        val fMin = 0f
        val fMax = sampleRate / 2f
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // Mel points evenly spaced in mel domain
        val melPoints = FloatArray(nMels + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (nMels + 1))
        }

        // Convert to FFT bin indices
        val binPoints = IntArray(melPoints.size) { i ->
            ((melPoints[i] * nFft / sampleRate).toInt()).coerceIn(0, fftSize - 1)
        }

        return Array(nMels) { m ->
            val filter = FloatArray(fftSize)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            for (k in left until center) {
                if (center > left) {
                    filter[k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (right > center) {
                    filter[k] = (right - k).toFloat() / (right - center)
                }
            }
            filter
        }
    }

    private fun Float.pow(exp: Float): Float = this.toDouble().pow(exp.toDouble()).toFloat()
}
