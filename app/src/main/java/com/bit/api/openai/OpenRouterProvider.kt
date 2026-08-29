package com.bit.api.openai

import com.bit.api.*
import com.bit.api.util.StreamingThinkTagParser

private val BOLD_TITLE_REGEX = Regex("\\*\\*(.*?)\\*\\*")
private val HEADING_TITLE_REGEX = Regex("(?m)^#+\\s*(.*)$")

private fun extractThoughtTitle(text: String): String? =
    BOLD_TITLE_REGEX.find(text)?.groupValues?.get(1)
        ?: HEADING_TITLE_REGEX.find(text)?.groupValues?.get(1)

class OpenRouterProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_OPEN_ROUTER
    override val defaultBaseUrl: String = "https://openrouter.ai/api/v1"

    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        val reasoning = if (config.thinkingEnabled) {
            OpenAiReasoning(
                effort = if (!config.thinkingBudgetEnabled) ThinkingLevels.openRouterEffort(config.thinkingLevel) else null,
                maxTokens = config.thinkingBudgetTokens.takeIf { config.thinkingBudgetEnabled && it > 0 }?.coerceIn(1, 131072)
            )
        } else null
        return request.copy(
            reasoning = reasoning,
            plugins = if (config.googleSearchEnabled) listOf(OpenAiPlugin(id = "web")) else null
        )
    }

    override fun getExtraHeaders(config: ProviderConfig): Map<String, String> = mapOf(
        "HTTP-Referer" to "https://github.com/jaswanthsanjay88/Bit_Android",
        "X-Title" to "BIT"
    )

    override suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        if (config.thinkingEnabled) {
            delta.reasoningDetails?.forEach { detail ->
                if (detail.type == "reasoning.text" || detail.type == "text") {
                    detail.text?.let {
                        if (it.isNotEmpty()) {
                            emit(StreamEvent.ThoughtChunk(it, extractThoughtTitle(it)))
                        }
                    }
                }
            }
            delta.reasoningContent?.let {
                if (it.isNotEmpty()) {
                    emit(StreamEvent.ThoughtChunk(it, extractThoughtTitle(it)))
                }
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) emit(StreamEvent.TextChunk(content))
        }
    }
}
