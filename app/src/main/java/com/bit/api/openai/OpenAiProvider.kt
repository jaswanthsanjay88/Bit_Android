package com.bit.api.openai

import com.bit.api.*

class OpenAiProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_OPENAI
    override val defaultBaseUrl: String = "https://api.openai.com/v1"

    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        val isReasoningModel = config.modelId.startsWith("o1") ||
            config.modelId.startsWith("o3") ||
            config.modelId.startsWith("o4") ||
            config.modelId.startsWith("gpt-5")
        return if (config.thinkingEnabled && isReasoningModel) {
            val effort = ThinkingLevels.openAiEffort(config.thinkingLevel)
            request.copy(reasoningEffort = effort)
        } else request
    }
}
