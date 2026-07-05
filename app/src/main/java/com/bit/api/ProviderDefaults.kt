package com.bit.api

object ProviderDefaults {
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"

    fun isOpenAiCompatibleEmbedding(name: String): Boolean =
        name == Constants.PROVIDER_OPENAI || name == Constants.PROVIDER_OPEN_ROUTER

    fun openAiCompatibleBaseUrl(baseUrls: Map<String, String>): String =
        baseUrls[Constants.PROVIDER_OPENAI] ?: OPENAI_BASE_URL

    fun embeddingBaseUrl(provider: String): String = when (provider.lowercase()) {
        "openai" -> OPENAI_BASE_URL
        "mistral" -> "https://api.mistral.ai/v1"
        "open router", "openrouter" -> "https://openrouter.ai/api/v1"
        "voyage ai", "voyage" -> "https://api.voyageai.com/v1"
        "siliconflow" -> "https://api.siliconflow.cn/v1"
        "ollama" -> "http://localhost:11434/v1"
        else -> OPENAI_BASE_URL
    }
}
