package com.bit.api.anthropic

import com.bit.api.*
import com.bit.api.util.buildToolCallId
import com.bit.api.util.prepareMessages
import com.bit.api.util.ProviderRetryPolicy
import com.bit.api.util.asRetryableTransportError
import com.bit.network.HttpClient
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val thinking: AnthropicThinking? = null,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    val tools: List<AnthropicTool>? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    val display: String? = null
)

@Serializable
internal data class AnthropicOutputConfig(
    val effort: String
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentPart>
)

@Serializable
internal data class AnthropicContentPart(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    val source: AnthropicImageSource? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val message: AnthropicMessageInfo? = null,
    val usage: AnthropicUsage? = null,
    val index: Int? = null
)

@Serializable
internal data class AnthropicDelta(
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val type: String? = null
)

@Serializable
internal data class AnthropicContentBlock(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val thinking: String? = null,
    val signature: String? = null
)

@Serializable
internal data class AnthropicMessageInfo(
    val usage: AnthropicUsage? = null
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null
)

private enum class ClaudeFamily { NO_THINKING, BUDGET_THINKING, TRANSITIONAL_4_6, CURRENT_ADAPTIVE }

private fun classifyClaudeFamily(model: String): ClaudeFamily {
    val m = model.lowercase()
    if (m.contains("claude-3-opus") || m.contains("claude-3-sonnet") || m.contains("claude-3-haiku") ||
        m.contains("claude-3.0") || m.contains("claude-3-0") ||
        m.contains("claude-3.5") || m.contains("claude-3-5")) return ClaudeFamily.NO_THINKING
    if (m.contains("claude-3.7") || m.contains("claude-3-7") ||
        m.contains("claude-4.0") || m.contains("claude-4-0") ||
        m.contains("claude-4.5") || m.contains("claude-4-5")) return ClaudeFamily.BUDGET_THINKING
    if (m.contains("4-6") || m.contains("4.6")) return ClaudeFamily.TRANSITIONAL_4_6
    return ClaudeFamily.CURRENT_ADAPTIVE
}

class AnthropicProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_ANTHROPIC
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
        val modelName = config.modelId

        val validatedPath = prepareMessages(messages, config.maxContextWindow)

        val apiMessages = buildList {
            var i = 0
            while (i < validatedPath.size) {
                val msg = validatedPath[i]
                when {
                    msg.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                        add(buildAssistantToolUse(msg))
                        i++
                        if (i < validatedPath.size && validatedPath[i].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                            val resultBlocks = mutableListOf<AnthropicContentPart>()
                            while (i < validatedPath.size && validatedPath[i].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                                resultBlocks.addAll(buildToolResultBlocks(validatedPath[i]))
                                i++
                            }
                            add(AnthropicMessage(role = "user", content = resultBlocks))
                        }
                    }
                    msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                        i++
                    }
                    else -> {
                        add(buildNormalMessage(if (config.includeImages) msg else msg.copy(images = emptyList())))
                        i++
                    }
                }
            }
        }

        val isLegacyClaude = modelName == "claude-3-opus-20240229" ||
            modelName == "claude-3-sonnet-20240229" ||
            modelName == "claude-3-haiku-20240307"
        val supportsAdaptiveThinking = modelName.contains("4-6") ||
            modelName.contains("4.6") ||
            modelName.contains("4-7") ||
            modelName.contains("4.7") ||
            modelName.contains("4-8") ||
            modelName.contains("4.8") ||
            modelName.contains("fable", ignoreCase = true) ||
            modelName.contains("mythos", ignoreCase = true)
        val thinkingBudget = (
            if (config.thinkingBudgetEnabled) config.thinkingBudgetTokens else ThinkingLevels.DefaultBudgetTokens
        ).coerceIn(1024, 128000)
        val thinking = if (config.thinkingEnabled && modelName.startsWith("claude") && !isLegacyClaude) {
            if (supportsAdaptiveThinking && !config.thinkingBudgetEnabled) {
                AnthropicThinking(type = "adaptive", display = "summarized")
            } else {
                AnthropicThinking(type = "enabled", budgetTokens = thinkingBudget, display = "summarized")
            }
        } else null
        val outputConfig = if (config.thinkingEnabled && !config.thinkingBudgetEnabled && modelName.startsWith("claude") && !isLegacyClaude && supportsAdaptiveThinking) {
            AnthropicOutputConfig(effort = ThinkingLevels.anthropicEffort(config.thinkingLevel))
        } else null

        val family = classifyClaudeFamily(modelName)
        val allowsSamplingParams = family != ClaudeFamily.CURRENT_ADAPTIVE

        val anthropicTools = config.tools?.map { td ->
            AnthropicTool(
                name = td.function.name,
                description = td.function.description,
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(td.function.parameters.type),
                        "properties" to JsonObject(
                            td.function.parameters.properties.mapValues { (_, prop) ->
                                val propMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                                    "type" to JsonPrimitive(prop.type),
                                    "description" to JsonPrimitive(prop.description)
                                )
                                if (prop.items != null) {
                                    propMap["items"] = JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive(prop.items.type),
                                            "description" to JsonPrimitive(prop.items.description)
                                        )
                                    )
                                }
                                JsonObject(propMap)
                            }
                        ),
                        "required" to kotlinx.serialization.json.JsonArray(
                            td.function.parameters.required.map { JsonPrimitive(it) }
                        )
                    )
                )
            )
        }

        val requestBody = AnthropicRequest(
            model = modelName,
            messages = apiMessages,
            system = config.systemPrompt,
            thinking = thinking,
            outputConfig = outputConfig,
            maxTokens = config.maxTokens ?: when {
                thinking?.budgetTokens != null -> maxOf(thinking.budgetTokens + 8192, 16384)
                thinking?.type == "adaptive" -> 32768
                else -> 8192
            },
            tools = anthropicTools,
            temperature = config.temperature.takeIf { allowsSamplingParams },
            topP = config.topP.takeIf { allowsSamplingParams }
        )

        try {
            val url = "$baseUrl/messages"
            val headers = mutableMapOf("Content-Type" to "application/json")
            headers["x-api-key"] = config.apiKey
            headers["anthropic-version"] = "2023-06-01"
            val requestBodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
            Log.d("AgoraAPI", "[Anthropic] REQ → $baseUrl/messages | model=$modelName | msgs=${apiMessages.size} | thinking=${thinking != null} | tools=${anthropicTools?.size ?: 0}")
            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            val retryableCodes = setOf(429, 502, 503, 504)
            var attempt = 0
            var done = false

            while (attempt < maxAttempts && !done) {
                attempt++
                val handle = try {
                    HttpClient.streamPost(url, requestBodyJson, headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val retryable = e.asRetryableTransportError()
                    if (retryable != null && attempt < maxAttempts) {
                        Log.w("AgoraAPI", "[Anthropic] Transport error on attempt $attempt/$maxAttempts (${e.javaClass.simpleName}), retrying...")
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(ProviderRetryPolicy.delayMillis(attempt))
                        continue
                    }
                    throw e
                }
                try {
                if (handle.code == 200) {
                    done = true
                    var line: String? = null
                    var currentType: String? = null
                    var toolUseId: String? = null
                    var toolUseName: String? = null
                    var toolUseArgs = StringBuilder()
                    var thinkingSignature: String? = null
                    var messageInputTokens = 0

                    sseLoop@ while (currentCoroutineContext().isActive) {
                        try {
                            line = handle.readLine()
                            if (line == null) break
                        } catch (e: java.net.SocketTimeoutException) {
                            if (!currentCoroutineContext().isActive) break
                            throw e
                        }
                        if (line.startsWith("event:")) {
                            currentType = line.substring(6).trim()
                        } else if (line.startsWith("data:")) {
                            val jsonStr = line.substring(5).trim()
                            if (currentType == "error") {
                                try {
                                    val errorJson = json.decodeFromString<OpenAiErrorResponse>(jsonStr)
                                    emit(StreamEvent.Error(GenerationError.Api(code = errorJson.error.code ?: "error", type = errorJson.error.type, message = errorJson.error.message)))
                                } catch (e: Exception) {
                                    emit(StreamEvent.Error(GenerationError.Network(statusCode = handle.code, message = jsonStr)))
                                }
                                break@sseLoop
                            }
                            try {
                                val event = json.decodeFromString<AnthropicStreamEvent>(jsonStr)
                                when (event.type) {
                                    "message_stop" -> {
                                        break@sseLoop
                                    }
                                    "message_start" -> {
                                        event.message?.usage?.inputTokens?.let { messageInputTokens = it }
                                    }
                                    "content_block_start" -> {
                                        event.contentBlock?.let { block ->
                                            when (block.type) {
                                                "thinking" -> {
                                                    block.signature?.takeIf { it.isNotBlank() }?.let { thinkingSignature = it }
                                                }
                                                "tool_use" -> {
                                                    toolUseId = block.id
                                                    toolUseName = block.name
                                                    toolUseArgs = StringBuilder()
                                                }
                                            }
                                        }
                                    }
                                    "content_block_delta" -> {
                                        event.delta?.let { delta ->
                                            when (delta.type) {
                                                "input_json_delta" -> {
                                                    delta.partialJson?.let { toolUseArgs.append(it) }
                                                }
                                                else -> {
                                                    delta.text?.let { emit(StreamEvent.TextChunk(it)) }
                                                    delta.thinking?.let {
                                                        if (delta.signature != null) thinkingSignature = delta.signature
                                                        emit(StreamEvent.ThoughtChunk(it, null, thinkingSignature))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "content_block_stop" -> {
                                        if (toolUseId != null && toolUseName != null) {
                                            emit(StreamEvent.ToolCallRequest(
                                                toolUseId!!, toolUseName!!, toolUseArgs.toString()
                                            ))
                                            toolUseId = null
                                            toolUseName = null
                                        }
                                        thinkingSignature = null
                                    }
                                    "message_delta" -> {
                                        event.usage?.let { u ->
                                            val total = messageInputTokens + (u.outputTokens ?: 0)
                                            emit(StreamEvent.UsageUpdate(total))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                            }
                        }
                    }
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("Stream cancelled")
                    }
                } else {
                    val errorRaw = handle.errorBody ?: "Unknown error"
                    Log.e("AgoraAPI", "[Anthropic] ERR ${handle.code}: $errorRaw")

                    if (handle.code in retryableCodes && attempt < maxAttempts) {
                        Log.w("AgoraAPI", "[Anthropic] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${ProviderRetryPolicy.delayMillis(attempt)}ms...")
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(ProviderRetryPolicy.delayMillis(attempt))
                    } else {
                        val genError = try {
                            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                            GenerationError.Api(code = errorJson.error.code ?: handle.code.toString(), type = errorJson.error.type, message = errorJson.error.message)
                        } catch (_: Exception) {
                            GenerationError.Network(statusCode = handle.code, message = errorRaw)
                        }
                        emit(StreamEvent.Error(genError))
                    }
                }
                } finally { handle.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildAssistantToolUse(msg: ChatMessage): AnthropicMessage {
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            val blocks = toolSegs.map { seg -> buildToolUseBlock(seg.toolCallId, seg.toolName, seg.toolArgs) }
            return AnthropicMessage(role = "assistant", content = blocks)
        }
        val tc = msg.toolCall ?: return AnthropicMessage(role = "assistant", content = listOf(
            AnthropicContentPart(type = "text", text = "Continue")
        ))
        val block = buildToolUseBlock(tc.toolCallId, tc.toolName, tc.arguments)
        return AnthropicMessage(role = "assistant", content = listOf(block))
    }

    private fun buildToolUseBlock(id: String?, name: String?, args: String?): AnthropicContentPart {
        val toolId = id ?: buildToolCallId(name ?: "", args ?: "{}", "tool_")
        val input = try {
            json.parseToJsonElement(args ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) { JsonObject(emptyMap()) }
        return AnthropicContentPart(type = "tool_use", id = toolId, name = name ?: "", input = input)
    }

    private fun buildToolResultBlocks(msg: ChatMessage): List<AnthropicContentPart> {
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            return toolSegs.map { seg ->
                val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
                AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = seg.toolResult ?: "")
            }
        }
        val tc = msg.toolCall ?: return emptyList()
        val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
        return listOf(AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = tc.result))
    }

    private fun buildNormalMessage(msg: ChatMessage): AnthropicMessage {
        val parts = mutableListOf<AnthropicContentPart>()
        val imagePaths = if (msg.participant == Participant.USER) msg.images else emptyList()
        for (imagePath in imagePaths) {
            val encoded = com.bit.api.util.encodeImageToBase64(imagePath)
            if (encoded != null) {
                val (mimeType, base64) = encoded
                parts.add(AnthropicContentPart(
                    type = "image",
                    source = AnthropicImageSource(mediaType = mimeType, data = base64)
                ))
            }
        }
        if (msg.text.isNotEmpty()) {
            parts.add(AnthropicContentPart(type = "text", text = msg.text))
        }
        if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = "Continue"))
        val role = if (msg.participant == Participant.USER) "user" else "assistant"
        return AnthropicMessage(role = role, content = parts)
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
            val responseText = HttpClient.fetchModels(
                "$effectiveBaseUrl/messages",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
            ) ?: run {
                Log.e("AgoraAPI", "Failed to fetch Anthropic models: empty response")
                return@withContext emptyList()
            }
            json.decodeFromString<AnthropicModelsResponse>(responseText).data.map { it.id }
        } catch (e: Exception) {
            Log.e("AgoraAPI", "Failed to fetch Anthropic models", e)
            emptyList()
        }
    }
}

@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)
