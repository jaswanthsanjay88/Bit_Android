package com.bit.api

import com.bit.api.anthropic.AnthropicProvider
import com.bit.api.gemini.GeminiProvider
import com.bit.api.ollama.OllamaProvider
import com.bit.api.openai.CustomOpenAiProvider
import com.bit.api.openai.DeepSeekProvider
import com.bit.api.openai.OpenAiProvider
import com.bit.api.openai.OpenRouterProvider
import com.bit.api.openai.QwenProvider

object LlmProviderResolver {
    
    fun resolveProvider(endpoint: String, model: String): LlmProvider {
        val url = endpoint.lowercase()
        return when {
            url.contains("googleapis.com") || url.contains("google") -> GeminiProvider()
            url.contains("anthropic.com") || url.contains("anthropic") -> AnthropicProvider()
            url.contains("deepseek") -> DeepSeekProvider()
            url.contains("dashscope") || url.contains("aliyun") || url.contains("qwen") -> QwenProvider()
            url.contains("openrouter") -> OpenRouterProvider()
            url.contains("localhost:11434") || url.contains("ollama") -> OllamaProvider()
            url.contains("api.openai.com") -> OpenAiProvider()
            else -> {
                val baseUrl = cleanBaseUrl(endpoint)
                CustomOpenAiProvider("Custom", baseUrl)
            }
        }
    }

    fun cleanBaseUrl(endpoint: String): String {
        var clean = endpoint
        val suffixToRemove = listOf(
            "/chat/completions",
            "/completions",
            "/api/chat",
            "/api/generate"
        )
        for (suffix in suffixToRemove) {
            if (clean.endsWith(suffix, ignoreCase = true)) {
                clean = clean.substring(0, clean.length - suffix.length)
            }
        }
        return clean.trimEnd('/')
    }
    
    fun cleanApiKey(authHeader: String?): String {
        val trimmed = authHeader?.trim() ?: return ""
        if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            return trimmed.substring(7).trim()
        }
        return trimmed
    }
}
