package com.dark.gguf_lib

import org.json.JSONObject

/**
 * CharacterEngine - Personality, mood, and uncensored mode control.
 *
 * Stub implementation — the native character engine methods were removed
 * from the SDK. This shim preserves the API surface so the host app
 * compiles; personality/mood/uncensored calls are no-ops until the
 * native side re-adds them.
 */
class CharacterEngine(private val engine: GGMLEngine) {

    private var _personality: Personality? = null
    private var _mood: Mood = Mood.NEUTRAL
    private var _uncensored = false

    fun setPersonality(personality: Personality) {
        _personality = personality
        // Apply via updateSamplerParams since native personality API was removed
        try {
            val json = JSONObject().apply {
                put("temperature", personality.temperature)
                put("topP", personality.topP)
                put("repeatPenalty", personality.repetitionPenalty)
                if (personality.topK != -1) put("topK", personality.topK)
                if (personality.minP != -1.0f) put("minP", personality.minP)
            }
            engine.updateSamplerParams(json.toString())
        } catch (_: Exception) {}
    }

    fun setMood(mood: Mood) {
        _mood = mood
    }

    fun getContext(): String {
        val p = _personality ?: return ""
        return buildString {
            if (p.name.isNotEmpty()) append("Character: ${p.name}\n")
            if (p.persona.isNotEmpty()) append("Persona: ${p.persona}\n")
            if (_mood != Mood.NEUTRAL) append("Mood: ${_mood.name.lowercase()}\n")
        }.trim()
    }

    fun setUncensored(enabled: Boolean) {
        _uncensored = enabled
    }

    val isUncensored: Boolean get() = _uncensored

    fun setCustomMood(tempMod: Float, topPMod: Float, repPenaltyMod: Float) {
        _mood = Mood.CUSTOM
    }

    fun buildPrompt(userPrompt: String): String {
        val ctx = getContext()
        return if (ctx.isNotEmpty()) "$ctx\n\n$userPrompt" else userPrompt
    }

    fun calcVectors(prompt: String, onProgress: ((Float) -> Unit)? = null): FloatArray? = null
    fun applyVectors(data: FloatArray, strength: Float = 1.0f, ilStart: Int = -1, ilEnd: Int = -1): Boolean = false
    fun clearVectors(): Boolean = true
}

// ---- Data classes ----

enum class Mood {
    NEUTRAL, HAPPY, SAD, EXCITED, CALM, ANGRY, CURIOUS, CREATIVE, FOCUSED, CUSTOM
}

data class Personality(
    val name: String,
    val persona: String,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val repetitionPenalty: Float = 1.1f,
    val creativity: Float = 0.5f,
    val verbosity: Float = 0.5f,
    val formality: Float = 0.5f,
    val topK: Int = -1,
    val minP: Float = -1.0f,
)

data class ControlVectorConfig(
    val path: String,
    val strength: Float = 1.0f,
)
