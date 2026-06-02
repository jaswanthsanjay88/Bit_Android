package com.bit.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device Whisper STT engine using ONNX Runtime.
 *
 * Loads Whisper Tiny EN model (encoder + decoder) and performs
 * speech-to-text transcription from 16kHz PCM audio.
 *
 * Pipeline: PCM bytes -> Float32 -> mel spectrogram -> encoder -> decoder -> text
 */
class WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngine"
        private const val N_MELS = 80
        private const val N_FRAMES = 3000
        private const val MAX_DECODE_TOKENS = 224
        private const val ENCODER_FILE = "whisper-tiny-encoder.onnx"
        private const val DECODER_FILE = "whisper-tiny-decoder.onnx"
        private const val VOCAB_FILE = "vocab.json"
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private val tokenizer = WhisperTokenizer()

    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Load the Whisper ONNX model from a directory containing
     * encoder, decoder, and vocab files.
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(modelDir)
            if (!dir.exists()) {
                Log.e(TAG, "Model directory does not exist: $modelDir")
                return@withContext false
            }

            val encoderFile = File(dir, ENCODER_FILE)
            val decoderFile = File(dir, DECODER_FILE)
            val vocabFile = File(dir, VOCAB_FILE)

            if (!encoderFile.exists() || !decoderFile.exists()) {
                Log.e(TAG, "Encoder or decoder ONNX file not found in $modelDir")
                return@withContext false
            }

            env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            encoderSession = env!!.createSession(encoderFile.absolutePath, sessionOptions)
            decoderSession = env!!.createSession(decoderFile.absolutePath, sessionOptions)

            // Load tokenizer
            if (vocabFile.exists()) {
                tokenizer.load(vocabFile)
            } else {
                tokenizer.loadBuiltinVocab()
            }

            isLoaded = true
            Log.i(TAG, "Whisper engine loaded from $modelDir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Whisper engine: ${e.message}", e)
            unload()
            false
        }
    }

    /**
     * Transcribe PCM 16-bit audio data to text.
     *
     * @param pcmData 16kHz, mono, 16-bit PCM audio bytes
     * @return Transcribed text or empty string on failure
     */
    suspend fun transcribe(pcmData: ByteArray): String = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            Log.e(TAG, "Engine not loaded")
            return@withContext ""
        }

        try {
            // 1. Convert PCM bytes to float32 samples
            val audioSamples = MelSpectrogram.pcmToFloat(pcmData)
            Log.d(TAG, "Audio samples: ${audioSamples.size} (~${audioSamples.size / 16000f}s)")

            // 2. Compute mel spectrogram [80 x 3000]
            val melSpec = MelSpectrogram.compute(audioSamples)
            Log.d(TAG, "Mel spectrogram computed: ${melSpec.size} values")

            // 3. Run encoder
            val encoderOutput = runEncoder(melSpec)
                ?: return@withContext ""

            // 4. Run decoder (greedy search)
            val tokenIds = runDecoder(encoderOutput)
            Log.d(TAG, "Decoded ${tokenIds.size} tokens")

            // 5. Decode tokens to text
            val text = tokenizer.decode(tokenIds)
            Log.i(TAG, "Transcription: $text")

            text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
            ""
        }
    }

    /**
     * Run the encoder on mel spectrogram features.
     * Input shape: [1, N_MELS, N_FRAMES]
     * Output: encoder hidden states tensor
     */
    private fun runEncoder(melSpec: FloatArray): OnnxTensor? {
        val ortEnv = env ?: return null
        val session = encoderSession ?: return null

        return try {
            val inputShape = longArrayOf(1, N_MELS.toLong(), N_FRAMES.toLong())
            val inputBuffer = FloatBuffer.wrap(melSpec)
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)

            val inputs = mapOf("mel" to inputTensor)
            val result = session.run(inputs)

            // Get encoder output (first output)
            val outputTensor = result.get(0) as? OnnxTensor
            Log.d(TAG, "Encoder output shape: ${outputTensor?.info?.shape?.contentToString()}")

            // Clone the tensor data so we can close the result
            if (outputTensor != null) {
                val data = outputTensor.floatBuffer
                val shape = outputTensor.info.shape
                val cloned = FloatArray(data.remaining())
                data.get(cloned)
                val clonedBuffer = FloatBuffer.wrap(cloned)
                inputTensor.close()
                result.close()
                OnnxTensor.createTensor(ortEnv, clonedBuffer, shape)
            } else {
                inputTensor.close()
                result.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder error: ${e.message}", e)
            null
        }
    }

    /**
     * Run greedy decoding using the decoder.
     * Feeds encoder output + token IDs iteratively.
     */
    private fun runDecoder(encoderOutput: OnnxTensor): List<Int> {
        val ortEnv = env ?: return emptyList()
        val session = decoderSession ?: return emptyList()

        val outputTokens = mutableListOf<Int>()

        try {
            // Start with initial prompt tokens
            val initialTokens = tokenizer.getInitialTokens()
            val tokenList = initialTokens.toMutableList()

            for (step in 0 until MAX_DECODE_TOKENS) {
                // Create token input tensor
                val tokenArray = tokenList.map { it.toLong() }.toLongArray()
                val tokenShape = longArrayOf(1, tokenArray.size.toLong())
                val tokenTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(tokenArray), tokenShape)

                val inputs = mapOf(
                    "encoder_hidden_states" to encoderOutput,
                    "input_ids" to tokenTensor
                )

                val result = session.run(inputs)
                val logits = result.get(0) as? OnnxTensor

                if (logits == null) {
                    tokenTensor.close()
                    result.close()
                    break
                }

                // Get logits for the last position and find argmax
                val logitData = logits.floatBuffer
                val logitShape = logits.info.shape
                val vocabSize = logitShape[logitShape.size - 1].toInt()
                val lastPositionOffset = (tokenList.size - 1) * vocabSize

                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = 0
                for (v in 0 until vocabSize) {
                    val idx = lastPositionOffset + v
                    if (idx < logitData.limit()) {
                        val value = logitData.get(idx)
                        if (value > maxVal) {
                            maxVal = value
                            maxIdx = v
                        }
                    }
                }

                tokenTensor.close()
                result.close()

                // Check for end of transcript
                if (tokenizer.isEot(maxIdx)) {
                    break
                }

                // Skip special/timestamp tokens in output
                if (!tokenizer.isSpecial(maxIdx)) {
                    outputTokens.add(maxIdx)
                }

                tokenList.add(maxIdx)
            }

            encoderOutput.close()
        } catch (e: Exception) {
            Log.e(TAG, "Decoder error: ${e.message}", e)
            try { encoderOutput.close() } catch (_: Exception) {}
        }

        return outputTokens
    }

    fun unload() {
        try {
            encoderSession?.close()
            decoderSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing sessions: ${e.message}")
        }
        encoderSession = null
        decoderSession = null
        isLoaded = false
        Log.i(TAG, "Whisper engine unloaded")
    }
}
