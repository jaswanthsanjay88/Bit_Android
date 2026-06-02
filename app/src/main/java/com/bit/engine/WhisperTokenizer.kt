package com.bit.engine

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Whisper BPE tokenizer for decoding ONNX model output token IDs to text.
 * Loads Whisper vocabulary mapping token IDs to text strings.
 * Handles special tokens for language, task, timestamps, and control flow.
 */
class WhisperTokenizer {

    companion object {
        private const val TAG = "WhisperTokenizer"

        // Whisper special token IDs (Whisper Tiny EN)
        const val SOT = 50257           // start of transcript
        const val EOT = 50256           // end of transcript
        const val TRANSCRIBE = 50358    // transcribe task
        const val TRANSLATE = 50357     // translate task
        const val EN = 50258            // English language
        const val NO_TIMESTAMPS = 50362 // no timestamps
        const val TIMESTAMP_BEGIN = 50363
    }

    private var idToToken: Map<Int, String> = emptyMap()
    private var tokenToId: Map<String, Int> = emptyMap()
    private var isLoaded = false

    /**
     * Load vocabulary from a vocab.json file.
     */
    fun load(vocabFile: File): Boolean {
        return try {
            val json = JSONObject(vocabFile.readText())
            val map = mutableMapOf<Int, String>()
            val reverseMap = mutableMapOf<String, Int>()

            json.keys().forEach { key ->
                val id = json.getInt(key)
                // Decode unicode escapes in BPE tokens
                val decoded = decodeBpeToken(key)
                map[id] = decoded
                reverseMap[decoded] = id
            }

            idToToken = map
            tokenToId = reverseMap
            isLoaded = true
            Log.i(TAG, "Loaded ${map.size} tokens from vocab")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab: ${e.message}")
            false
        }
    }

    /**
     * Load a minimal built-in vocabulary for English transcription
     * when no vocab.json is available.
     */
    fun loadBuiltinVocab() {
        // Build a minimal ASCII + common token mapping
        val map = mutableMapOf<Int, String>()

        // ASCII printable characters (tokens 0-255 map to bytes)
        for (i in 0..255) {
            map[i] = i.toChar().toString()
        }

        // BPE merged tokens start at 256 - we'll use raw byte fallback
        idToToken = map
        isLoaded = true
        Log.i(TAG, "Loaded built-in minimal vocab (${map.size} tokens)")
    }

    /**
     * Decode a sequence of token IDs to text.
     */
    fun decode(tokenIds: List<Int>): String {
        if (!isLoaded) {
            Log.w(TAG, "Tokenizer not loaded, returning raw IDs")
            return tokenIds.joinToString(" ")
        }

        val sb = StringBuilder()
        for (id in tokenIds) {
            // Skip special tokens
            if (id >= SOT || id == EOT) continue
            if (id >= TIMESTAMP_BEGIN) continue

            val token = idToToken[id]
            if (token != null) {
                sb.append(token)
            }
        }

        return cleanupText(sb.toString())
    }

    /**
     * Check if a token ID is the end-of-transcript marker.
     */
    fun isEot(tokenId: Int): Boolean = tokenId == EOT

    /**
     * Check if a token ID is a timestamp token.
     */
    fun isTimestamp(tokenId: Int): Boolean = tokenId >= TIMESTAMP_BEGIN

    /**
     * Check if a token ID is a special token.
     */
    fun isSpecial(tokenId: Int): Boolean = tokenId >= EOT

    /**
     * Get the initial decoder prompt tokens for English transcription.
     */
    fun getInitialTokens(): IntArray {
        return intArrayOf(SOT, EN, TRANSCRIBE, NO_TIMESTAMPS)
    }

    private fun decodeBpeToken(token: String): String {
        // Whisper BPE uses byte-level encoding with special Unicode chars
        // The token text maps bytes 0-255 to Unicode code points 256+
        return try {
            val sb = StringBuilder()
            for (ch in token) {
                val code = ch.code
                // Whisper's BPE byte encoder maps bytes to specific Unicode range
                if (code >= 256) {
                    // Map back from Unicode to byte
                    sb.append(bpeByteToChar(code))
                } else {
                    sb.append(ch)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            token
        }
    }

    private fun bpeByteToChar(code: Int): Char {
        // Whisper's GPT-2 style byte encoder reverse mapping
        return when {
            code in 256..288 -> (code - 256).toChar()      // bytes 0-32
            code == 289 -> 127.toChar()                     // DEL
            code in 290..322 -> (code - 162).toChar()       // bytes 128-160
            else -> code.toChar()
        }
    }

    private fun cleanupText(text: String): String {
        return text
            .replace("  ", " ")
            .trim()
    }
}
