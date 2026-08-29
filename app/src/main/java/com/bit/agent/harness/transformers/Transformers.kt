package com.bit.agent.harness.transformers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Message Transformer interface for modifying prompts and responses in the generation pipeline.
 */
interface Transformer {
    fun transform(text: String): String
}

/**
 * Replaces dynamic placeholders ({{ date }}, {{ time }}, {{ platform }}) in prompts.
 */
class PlaceholderTransformer : Transformer {
    override fun transform(text: String): String {
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        return text
            .replace("{{ date }}", dateFormat)
            .replace("{{ time }}", timeFormat)
            .replace("{{ platform }}", "Android")
            .replace("{{ os }}", "Android " + android.os.Build.VERSION.RELEASE)
    }
}

/**
 * Parses and extracts <think>...</think> reasoning traces from raw model streams.
 */
class ThinkTagTransformer : Transformer {
    override fun transform(text: String): String {
        return text.replace(Regex("""<think>[\s\S]*?</think>"""), "").trim()
    }

    companion object {
        fun extractReasoning(text: String): String? {
            val match = Regex("""<think>([\s\S]*?)(?:</think>|$)""").find(text)
            return match?.groupValues?.get(1)?.trim()
        }
    }
}
